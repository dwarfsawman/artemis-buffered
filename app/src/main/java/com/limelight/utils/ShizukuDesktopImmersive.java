package com.limelight.utils;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.View;

import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.profiles.ProfilesManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import rikka.shizuku.Shizuku;

/**
 * Automatically triggers DeX Full Immersive Mode via Shizuku by detecting
 * the caption bar (WindowDecor) and tapping the toggle_immersive_window button.
 */
public final class ShizukuDesktopImmersive {
    private static final String TAG = "ShizukuDesktopImmersive";
    private static final int SHIZUKU_REQUEST_CODE = 4045;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private static final Pattern DECOR_RECT_PATTERN = Pattern.compile(
            "ReusableWindowDecorViewHost.*?frame=\\[Rect\\((\\d+),\\s*(\\d+)\\s*-\\s*(\\d+),\\s*(\\d+)\\)\\]"
    );

    private static boolean binderListenerRegistered = false;
    private static boolean permissionListenerRegistered = false;
    private static WeakReference<Activity> pendingActivityRef = null;
    private static Method newProcessMethod = null;
    private static long lastTriggerTime = 0;

    static {
        initShizukuListeners();
    }

    public static synchronized void initShizukuListeners() {
        if (!binderListenerRegistered) {
            try {
                Shizuku.addBinderReceivedListenerSticky(() -> {
                    Log.i(TAG, "Shizuku binder received!");
                    checkPendingTrigger();
                });
                binderListenerRegistered = true;
            } catch (Throwable t) {
                Log.w(TAG, "Failed to register binder listener", t);
            }
        }
    }

    private static synchronized void checkPendingTrigger() {
        if (pendingActivityRef != null) {
            Activity act = pendingActivityRef.get();
            if (act != null && !act.isFinishing()) {
                MAIN_HANDLER.post(() -> checkAndTrigger(act));
            }
            pendingActivityRef = null;
        }
    }

    private static Process execProcess(String[] cmd) {
        if (newProcessMethod == null) {
            try {
                newProcessMethod = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
                newProcessMethod.setAccessible(true);
            } catch (Throwable t) {
                Log.w(TAG, "Failed to resolve newProcess method", t);
                return null;
            }
        }
        try {
            return (Process) newProcessMethod.invoke(null, (Object) cmd, null, null);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to invoke Shizuku newProcess", t);
            return null;
        }
    }

    public static void checkAndTrigger(Activity activity) {
        if (activity == null || activity.isFinishing()) {
            return;
        }

        // Only proceed if DeX / Desktop mode is active and user enabled full screen pref
        if (!DesktopFullscreen.isDesktopMode(activity)) {
            return;
        }

        boolean prefEnabled = ProfilesManager.getInstance().getOverlayingSharedPreferences(activity)
                .getBoolean(PreferenceConfiguration.LAUNCH_FULLSCREEN_PREF_STRING,
                        PreferenceConfiguration.DEFAULT_LAUNCH_FULLSCREEN);
        if (!prefEnabled) {
            return;
        }

        try {
            if (!Shizuku.pingBinder()) {
                Log.d(TAG, "Shizuku binder is not available yet, saving pending activity");
                pendingActivityRef = new WeakReference<>(activity);
                initShizukuListeners();
                return;
            }

            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "Shizuku permission not granted, requesting...");
                registerPermissionListener(activity);
                Shizuku.requestPermission(SHIZUKU_REQUEST_CODE);
                return;
            }

            // Delay trigger to allow WindowManager / WMShell to finish layout
            MAIN_HANDLER.postDelayed(() -> EXECUTOR.execute(() -> runImmersiveTrigger(activity)), 500);

        } catch (Throwable t) {
            Log.w(TAG, "Shizuku check error", t);
        }
    }

    private static synchronized void registerPermissionListener(Activity activity) {
        if (permissionListenerRegistered) {
            return;
        }
        try {
            Shizuku.addRequestPermissionResultListener((requestCode, grantResult) -> {
                if (requestCode == SHIZUKU_REQUEST_CODE && grantResult == PackageManager.PERMISSION_GRANTED) {
                    Log.i(TAG, "Shizuku permission granted!");
                    MAIN_HANDLER.postDelayed(() -> EXECUTOR.execute(() -> runImmersiveTrigger(activity)), 500);
                }
            });
            permissionListenerRegistered = true;
        } catch (Throwable t) {
            Log.w(TAG, "Failed to register Shizuku permission listener", t);
        }
    }

    private static void runImmersiveTrigger(Activity activity) {
        if (activity == null || activity.isFinishing()) {
            return;
        }

        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastTriggerTime < 2500) {
            Log.d(TAG, "Skipping trigger: cooldown active (" + (now - lastTriggerTime) + "ms since last trigger)");
            return;
        }

        try {
            int displayId = Display.DEFAULT_DISPLAY;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Display d = activity.getDisplay();
                if (d != null) {
                    displayId = d.getDisplayId();
                }
            }
            if (displayId == Display.DEFAULT_DISPLAY) {
                displayId = activity.getWindowManager().getDefaultDisplay().getDisplayId();
            }

            Log.i(TAG, "Attempting immersive trigger for display " + displayId);

            // 1. Query dumpsys window windows to find ReusableWindowDecorViewHost frame
            Process dumpsysProcess = execProcess(new String[]{"dumpsys", "window", "windows"});
            int targetX = -1;
            int targetY = -1;
            boolean decorFound = false;

            if (dumpsysProcess != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(dumpsysProcess.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        Matcher m = DECOR_RECT_PATTERN.matcher(line);
                        if (m.find()) {
                            decorFound = true;
                            int left = Integer.parseInt(m.group(1));
                            int top = Integer.parseInt(m.group(2));
                            int right = Integer.parseInt(m.group(3));
                            int bottom = Integer.parseInt(m.group(4));

                            // Samsung DeX WindowDecor layout:
                            // Toggle Immersive button is positioned ~80px from right edge of caption bar.
                            targetX = right - 80;
                            targetY = top + (bottom - top) / 2;
                            Log.i(TAG, "Found caption decor: [" + left + ", " + top + " - " + right + ", " + bottom
                                    + "] -> target click: (" + targetX + ", " + targetY + ")");
                            break;
                        }
                    }
                }
                dumpsysProcess.waitFor();
            }

            if (!decorFound) {
                // If caption decor is not found in dumpsys, verify if app is already Full Immersive
                final int finalDisplayId = displayId;
                MAIN_HANDLER.post(() -> {
                    try {
                        View decor = activity.getWindow().getDecorView();
                        DisplayMetrics dm = new DisplayMetrics();
                        activity.getWindowManager().getDefaultDisplay().getRealMetrics(dm);
                        if (decor.getWidth() >= dm.widthPixels && decor.getHeight() >= dm.heightPixels) {
                            Log.i(TAG, "App is already in Full Immersive (" + decor.getWidth() + "x" + decor.getHeight()
                                    + "), skipping tap to avoid toggling off.");
                            return;
                        }

                        // Decor wasn't parsed from dumpsys but window is not full screen -> try fallback coordinates
                        int fallbackX = decor.getWidth() - 80;
                        int fallbackY = 20;
                        Log.i(TAG, "Using fallback coordinates: (" + fallbackX + ", " + fallbackY + ")");
                        EXECUTOR.execute(() -> executeTap(finalDisplayId, fallbackX, fallbackY));
                    } catch (Throwable t) {
                        Log.w(TAG, "Fallback check error", t);
                    }
                });
                return;
            }

            // 2. Execute tap via Shizuku
            executeTap(displayId, targetX, targetY);

        } catch (Throwable t) {
            Log.w(TAG, "Failed to run immersive trigger", t);
        }
    }

    private static void executeTap(int displayId, int x, int y) {
        try {
            Log.i(TAG, "Executing input -d " + displayId + " tap " + x + " " + y);
            Process tapProcess = execProcess(
                    new String[]{"input", "-d", String.valueOf(displayId), "tap", String.valueOf(x), String.valueOf(y)}
            );
            if (tapProcess != null) {
                tapProcess.waitFor();
                lastTriggerTime = android.os.SystemClock.uptimeMillis();
                Log.i(TAG, "Tap executed successfully (exit " + tapProcess.exitValue() + ")");
            } else {
                Log.w(TAG, "tapProcess was null");
            }
        } catch (Throwable t) {
            Log.w(TAG, "Error executing input tap", t);
        }
    }
}
