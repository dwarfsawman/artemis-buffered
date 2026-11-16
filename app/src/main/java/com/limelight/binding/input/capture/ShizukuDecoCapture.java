package com.limelight.binding.input.capture;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import rikka.shizuku.Shizuku;

/** Focus-bound Shizuku shell child, with an independent 1.5 s native watchdog. */
public final class ShizukuDecoCapture implements Closeable {
    public interface Eligibility { boolean isEligible(); }
    private static final String TAG = "ShizukuDeco";
    private static final int PERMISSION_REQUEST = 4046;
    private final Activity activity;
    private final Eligibility eligible;
    private final DecoEvdevStream stream;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Process process;
    private OutputStream heartbeat;
    private InputStream output, errors;
    private int generation;
    private boolean starting, active, closed, requestedPermission, warned;
    private long retryAfter;
    private long lastDiagnostic;
    private final Shizuku.OnRequestPermissionResultListener permissionListener = (code, result) -> {
        if (code == PERMISSION_REQUEST && result == PackageManager.PERMISSION_GRANTED) refresh();
    };
    private final Shizuku.OnBinderDeadListener deathListener = () -> main.post(this::stop);
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refresh();
            if (!closed) main.postDelayed(this, 250);
        }
    };

    public ShizukuDecoCapture(Activity activity, Eligibility eligible, DecoEvdevStream.Sink sink) {
        this.activity = activity; this.eligible = eligible; stream = new DecoEvdevStream(sink);
        Shizuku.addRequestPermissionResultListener(permissionListener);
        Shizuku.addBinderDeadListener(deathListener);
        main.post(tick);
    }

    private boolean hasCompatibleDevice() {
        // This compatibility path is only needed on Xiaomi; do not change other tablets.
        if (!"Xiaomi".equalsIgnoreCase(Build.MANUFACTURER) || Build.VERSION.SDK_INT < 26) return false;
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d != null && d.getVendorId() == 0x28bd && d.getProductId() == 0x0949
                    && d.getName().equals("Deco mini7W V2 Pen")) return true;
        }
        return false;
    }

    public boolean isActive() { return active; }

    /** Must be called on the main thread (also by focus/pause/grab callbacks). */
    public void refresh() {
        if (closed || !eligible.isEligible() || !hasCompatibleDevice()) { stop(); return; }
        try {
            if (!Shizuku.pingBinder()) {
                stop();
                warn("ペンカーソルを非表示にするにはShizukuを起動してください");
                return;
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                stop();
                if (!requestedPermission) {
                    requestedPermission = true;
                    Shizuku.requestPermission(PERMISSION_REQUEST);
                }
                return;
            }
            if (heartbeat != null) { heartbeat.write('H'); heartbeat.flush(); }
            if (!starting && process == null && SystemClock.uptimeMillis() >= retryAfter) launch();
        } catch (Exception | LinkageError e) {
            Log.w(TAG, "Reader unavailable", e); stop();
            retryAfter = SystemClock.uptimeMillis() + 3000;
            warn("ペンの直接入力を開始できません。Shizukuの権限を確認してください");
        }
    }

    private void warn(String message) {
        if (!warned) { warned = true; Toast.makeText(activity, message, Toast.LENGTH_LONG).show(); }
    }

    private void launch() {
        starting = true;
        int session = ++generation;
        String binary = activity.getApplicationInfo().nativeLibraryDir + "/libdeco_reader.so";
        new Thread(() -> {
            Process child = null;
            try {
                // API 13.1.5 provides shell execution. Kept explicitly for R8, like the
                // existing immersive helper; migration to UserService is needed for API 14.
                Method exec = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
                exec.setAccessible(true);
                child = (Process) exec.invoke(null, new String[]{binary}, null, null);
                Process launched = child;
                OutputStream input = child.getOutputStream();
                InputStream stdout = child.getInputStream(), stderr = child.getErrorStream();
                main.post(() -> {
                    if (closed || session != generation || !eligible.isEligible()) {
                        dispose(launched, input, stdout, stderr); return;
                    }
                    process = launched; heartbeat = input; output = stdout; errors = stderr;
                });
                new Thread(() -> drainErrors(stderr), "Deco-reader-errors").start();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(stdout, StandardCharsets.US_ASCII))) {
                    String line;
                    boolean ready = false;
                    while ((line = reader.readLine()) != null) {
                        if (line.length() > 160) throw new IllegalArgumentException("Oversized reader frame");
                        if (!ready) {
                            if (!line.equals("READY")) throw new IllegalArgumentException("Missing READY");
                            ready = true;
                            main.post(() -> {
                                if (session == generation && !closed && eligible.isEligible()) {
                                    active = true; starting = false; warned = false;
                                    Log.i(TAG, "Direct pen + hover capture active");
                                }
                            });
                        } else {
                            String frame = line;
                            main.post(() -> {
                                if (active && session == generation && eligible.isEligible()) {
                                    try {
                                        stream.accept(frame);
                                        if (Log.isLoggable("ArtemisDeco", Log.DEBUG)
                                                && SystemClock.uptimeMillis() - lastDiagnostic >= 250) {
                                            lastDiagnostic = SystemClock.uptimeMillis();
                                            Log.d("ArtemisDeco", "evdev " + frame);
                                        }
                                    }
                                    catch (RuntimeException e) { Log.w(TAG, "Invalid reader frame", e); stop(); }
                                }
                            });
                        }
                    }
                }
            } catch (Exception | LinkageError e) { Log.w(TAG, "Reader stopped", e); }
            finally {
                Process ended = child;
                main.post(() -> {
                    if (session == generation) {
                        stop(); retryAfter = SystemClock.uptimeMillis() + 3000;
                        warn("ペンの直接入力が停止しました。通常の入力へ戻します");
                    } else if (ended != null) {
                        try { ended.destroy(); } catch (RuntimeException ignored) { }
                    }
                });
            }
        }, "Deco-reader").start();
    }

    private static void drainErrors(InputStream errors) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(errors, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) Log.i(TAG, line);
        } catch (Exception ignored) { }
    }

    private static void dispose(Process child, Closeable... pipes) {
        for (Closeable pipe : pipes) if (pipe != null) try { pipe.close(); } catch (Exception ignored) { }
        if (child != null) try { child.destroy(); } catch (RuntimeException ignored) { }
    }

    private void stop() {
        if (process == null && !starting && !active) return;
        ++generation; active = starting = false;
        stream.release();
        dispose(process, heartbeat, output, errors);
        process = null; heartbeat = null; output = errors = null;
        Log.i(TAG, "Direct capture released");
    }

    @Override public void close() {
        closed = true; main.removeCallbacks(tick); stop();
        Shizuku.removeRequestPermissionResultListener(permissionListener);
        Shizuku.removeBinderDeadListener(deathListener);
    }
}
