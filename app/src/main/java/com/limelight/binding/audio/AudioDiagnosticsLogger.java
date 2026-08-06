package com.limelight.binding.audio;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;

import com.limelight.BuildConfig;
import com.limelight.LimeLog;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes timestamped audio diagnostics without doing file I/O on the audio delivery thread.
 * Each line is a standalone JSON object so logs can be streamed into common analysis tools.
 */
public final class AudioDiagnosticsLogger {
    private static final String LOG_DIRECTORY = "audio-diagnostics";
    private static final String LOG_PREFIX = "audio-diagnostics-";
    private static final String LOG_SUFFIX = ".jsonl";
    private static final String SHARE_ARCHIVE = "audio-diagnostics.zip";
    private static final int MAX_SESSION_LOGS = 8;

    private final File logFile;
    private final long sessionStartElapsedNanos;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "AudioDiagnosticsWriter");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean closed = new AtomicBoolean();
    // Accessed only by the single writer executor thread.
    private BufferedWriter output;

    private AudioDiagnosticsLogger(File logFile) {
        this.logFile = logFile;
        this.sessionStartElapsedNanos = SystemClock.elapsedRealtimeNanos();
    }

    /** Returns null if diagnostics cannot be started. Streaming must continue in that case. */
    public static AudioDiagnosticsLogger start(Context context) {
        try {
            File directory = getLogDirectory(context);
            if (!directory.exists() && !directory.mkdirs()) {
                throw new IOException("Unable to create " + directory);
            }

            pruneOldLogs(directory, MAX_SESSION_LOGS - 1);
            File logFile = new File(directory, LOG_PREFIX + fileTimestamp(System.currentTimeMillis()) + LOG_SUFFIX);
            AudioDiagnosticsLogger logger = new AudioDiagnosticsLogger(logFile);
            logger.record("session_start",
                    "appVersion", BuildConfig.VERSION_NAME,
                    "versionCode", BuildConfig.VERSION_CODE,
                    "deviceManufacturer", Build.MANUFACTURER,
                    "deviceModel", Build.MODEL,
                    "androidSdk", Build.VERSION.SDK_INT);
            return logger;
        }
        catch (IOException e) {
            LimeLog.warning("Unable to start audio diagnostics: " + e.getMessage());
            return null;
        }
    }

    public void record(String event, Object... fields) {
        if (closed.get()) {
            return;
        }

        enqueueRecord(event,
                System.currentTimeMillis(),
                SystemClock.elapsedRealtimeNanos(),
                fields,
                false);
    }

    /** Records an event captured against Android's elapsed-realtime clock. */
    void recordAtElapsedRealtimeNanos(String event, long eventElapsedNanos, Object... fields) {
        if (closed.get()) {
            return;
        }

        long nowElapsedNanos = SystemClock.elapsedRealtimeNanos();
        long boundedElapsedNanos = Math.max(sessionStartElapsedNanos,
                Math.min(eventElapsedNanos, nowElapsedNanos));
        long eventWallTimeMs = System.currentTimeMillis() -
                (nowElapsedNanos - boundedElapsedNanos) / 1_000_000L;
        enqueueRecord(event, eventWallTimeMs, boundedElapsedNanos, fields, false);
    }

    public void close(String reason) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        enqueueRecord("session_end",
                System.currentTimeMillis(),
                SystemClock.elapsedRealtimeNanos(),
                new Object[] {"reason", reason},
                true);
        writer.shutdown();
    }

    private String buildRecord(String event, long wallTimeMs, long elapsedNanos, Object... fields) {
        JSONObject record = new JSONObject();
        try {
            record.put("timestamp", isoTimestamp(wallTimeMs));
            record.put("sessionElapsedMs",
                    Math.max(0, elapsedNanos - sessionStartElapsedNanos) / 1_000_000L);
            record.put("elapsedRealtimeNanos", elapsedNanos);
            record.put("event", event);

            if (fields.length % 2 != 0) {
                throw new IllegalArgumentException("Diagnostic fields must be key/value pairs");
            }
            for (int i = 0; i < fields.length; i += 2) {
                record.put(String.valueOf(fields[i]), fields[i + 1]);
            }
        }
        catch (JSONException e) {
            throw new IllegalArgumentException("Unable to encode diagnostic record", e);
        }
        return record + System.lineSeparator();
    }

    private void enqueueRecord(String event, long wallTimeMs, long elapsedNanos,
                               Object[] fields, boolean closeOutputAfterWrite) {
        try {
            writer.execute(() -> {
                append(buildRecord(event, wallTimeMs, elapsedNanos, fields));
                if (closeOutputAfterWrite) {
                    closeOutput();
                }
            });
        }
        catch (RejectedExecutionException ignored) {
            // A late renderer callback can race with shutdown. The session is already complete.
        }
    }

    private void append(String line) {
        try {
            if (output == null) {
                output = new BufferedWriter(new OutputStreamWriter(
                        new FileOutputStream(logFile, true), StandardCharsets.UTF_8));
            }
            output.write(line);
            output.flush();
        }
        catch (IOException e) {
            LimeLog.warning("Unable to write audio diagnostics: " + e.getMessage());
        }
    }

    private void closeOutput() {
        if (output == null) {
            return;
        }

        try {
            output.close();
        }
        catch (IOException e) {
            LimeLog.warning("Unable to close audio diagnostics: " + e.getMessage());
        }
        finally {
            output = null;
        }
    }

    public static File createShareArchive(Context context) throws IOException {
        File[] logs = listLogs(context);
        if (logs.length == 0) {
            return null;
        }

        File archive = new File(context.getCacheDir(), SHARE_ARCHIVE);
        byte[] buffer = new byte[8192];
        try (ZipOutputStream zip = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(archive)))) {
            for (File log : logs) {
                zip.putNextEntry(new ZipEntry(log.getName()));
                try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(log))) {
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        zip.write(buffer, 0, read);
                    }
                }
                zip.closeEntry();
            }
        }
        return archive;
    }

    static boolean hasLogs(Context context) {
        return listLogs(context).length != 0;
    }

    boolean awaitWriterForTest(long timeoutMs) throws InterruptedException {
        return writer.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS);
    }

    private static File getLogDirectory(Context context) {
        return new File(context.getFilesDir(), LOG_DIRECTORY);
    }

    private static File[] listLogs(Context context) {
        File[] logs = getLogDirectory(context).listFiles((directory, name) ->
                name.startsWith(LOG_PREFIX) && name.endsWith(LOG_SUFFIX));
        if (logs == null) {
            return new File[0];
        }
        Arrays.sort(logs, Comparator.comparing(File::getName));
        return logs;
    }

    private static void pruneOldLogs(File directory, int logsToKeep) {
        File[] logs = directory.listFiles((ignored, name) ->
                name.startsWith(LOG_PREFIX) && name.endsWith(LOG_SUFFIX));
        if (logs == null || logs.length <= logsToKeep) {
            return;
        }

        Arrays.sort(logs, Comparator.comparing(File::getName).reversed());
        for (int i = logsToKeep; i < logs.length; i++) {
            if (!logs[i].delete()) {
                LimeLog.warning("Unable to delete old audio diagnostic log: " + logs[i].getName());
            }
        }
    }

    private static String fileTimestamp(long timeMs) {
        return formatTimestamp(timeMs, "yyyyMMdd-HHmmss-SSS");
    }

    private static String isoTimestamp(long timeMs) {
        return formatTimestamp(timeMs, "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
    }

    private static String formatTimestamp(long timeMs, String pattern) {
        SimpleDateFormat formatter = new SimpleDateFormat(pattern, Locale.US);
        formatter.setTimeZone(TimeZone.getTimeZone("UTC"));
        return formatter.format(new Date(timeMs));
    }
}
