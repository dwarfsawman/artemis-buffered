package com.limelight.binding.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.SystemClock;

import androidx.test.core.app.ApplicationProvider;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class AudioDiagnosticsLoggerTest {
    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        deleteRecursively(new File(context.getFilesDir(), "audio-diagnostics"));
        new File(context.getCacheDir(), "audio-diagnostics.zip").delete();
    }

    @Test
    public void writesJsonLinesAndCreatesShareArchive() throws Exception {
        AudioDiagnosticsLogger logger = AudioDiagnosticsLogger.start(context);
        assertNotNull(logger);

        logger.record("aaudio_stats", "queuedMs", 38, "underrunCallbacks", 1);
        logger.close("test_complete");
        assertTrue(logger.awaitWriterForTest(2000));
        assertTrue(AudioDiagnosticsLogger.hasLogs(context));

        File directory = new File(context.getFilesDir(), "audio-diagnostics");
        File[] logs = directory.listFiles();
        assertNotNull(logs);
        assertEquals(1, logs.length);

        List<JSONObject> records = readRecords(logs[0]);
        assertEquals("session_start", records.get(0).getString("event"));
        assertEquals("aaudio_stats", records.get(1).getString("event"));
        assertEquals(38, records.get(1).getInt("queuedMs"));
        assertEquals("session_end", records.get(2).getString("event"));

        File archive = AudioDiagnosticsLogger.createShareArchive(context);
        assertNotNull(archive);
        try (ZipFile zip = new ZipFile(archive)) {
            assertNotNull(zip.getEntry(logs[0].getName()));
        }
    }

    @Test
    public void preservesElapsedRealtimeForDeferredNativeEvents() throws Exception {
        AudioDiagnosticsLogger logger = AudioDiagnosticsLogger.start(context);
        assertNotNull(logger);

        long eventElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos();
        logger.recordAtElapsedRealtimeNanos("aaudio_underrun",
                eventElapsedRealtimeNanos,
                "missingFrames", 192);
        logger.close("test_complete");
        assertTrue(logger.awaitWriterForTest(2000));

        File directory = new File(context.getFilesDir(), "audio-diagnostics");
        File[] logs = directory.listFiles();
        assertNotNull(logs);
        assertEquals(1, logs.length);

        List<JSONObject> records = readRecords(logs[0]);
        assertEquals("aaudio_underrun", records.get(1).getString("event"));
        assertEquals(eventElapsedRealtimeNanos,
                records.get(1).getLong("elapsedRealtimeNanos"));
        assertEquals(192, records.get(1).getInt("missingFrames"));
        assertTrue(records.get(1).getLong("sessionElapsedMs") >= 0);
    }

    @Test
    public void samplesDeferredFieldsBeforeSessionEnd() throws Exception {
        AudioDiagnosticsLogger logger = AudioDiagnosticsLogger.start(context);
        assertNotNull(logger);

        logger.recordDeferred("network_stats", () -> new Object[] {
                "rttMs", 12,
                "networkTransport", "wifi"
        });
        logger.close("test_complete");
        assertTrue(logger.awaitWriterForTest(2000));

        File[] logs = new File(context.getFilesDir(), "audio-diagnostics").listFiles();
        assertNotNull(logs);
        List<JSONObject> records = readRecords(logs[0]);
        assertEquals("network_stats", records.get(1).getString("event"));
        assertEquals(12, records.get(1).getInt("rttMs"));
        assertEquals("session_end", records.get(2).getString("event"));
    }

    private static List<JSONObject> readRecords(File file) throws Exception {
        List<JSONObject> records = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                records.add(new JSONObject(line));
            }
        }
        return records;
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }
}
