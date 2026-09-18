package com.limelight.utils;

import android.app.Activity;
import android.app.UiModeManager;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.view.Display;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.profiles.ProfilesManager;

/** Immersive UI for Samsung DeX and desktop browsing windows. */
public final class DesktopFullscreen {
    private boolean applied;
    private int previousVisibility;

    public static boolean isDesktopMode(Activity activity) {
        if (activity == null) {
            return false;
        }

        // 1. Check Configuration UI mode
        Configuration config = activity.getResources().getConfiguration();
        if (config != null && (config.uiMode & Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_DESK) {
            return true;
        }

        // 2. Check UiModeManager
        UiModeManager uiModeManager = (UiModeManager) activity.getSystemService(Context.UI_MODE_SERVICE);
        if (uiModeManager != null && uiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_DESK) {
            return true;
        }

        // 3. Check Samsung DeX configuration reflection
        if (config != null) {
            try {
                Class<?> configClass = config.getClass();
                int enabledFlag = configClass.getField("SEM_DESKTOP_MODE_ENABLED").getInt(configClass);
                int currentFlag = configClass.getField("semDesktopModeEnabled").getInt(config);
                if (enabledFlag != 0 && enabledFlag == currentFlag) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }

        // 4. Samsung device on external display or in multi-window / desktop freeform mode
        if ("samsung".equalsIgnoreCase(Build.MANUFACTURER)) {
            Display display = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    display = activity.getDisplay();
                } catch (Throwable ignored) {
                }
            }
            if (display == null) {
                display = activity.getWindowManager().getDefaultDisplay();
            }
            if (display != null && display.getDisplayId() != Display.DEFAULT_DISPLAY) {
                return true;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity.isInMultiWindowMode()) {
                return true;
            }
        }

        return false;
    }

    @SuppressWarnings("deprecation")
    public void apply(Activity activity) {
        if (activity == null || activity.getWindow() == null) {
            return;
        }

        boolean prefEnabled = ProfilesManager.getInstance().getOverlayingSharedPreferences(activity)
                .getBoolean(PreferenceConfiguration.LAUNCH_FULLSCREEN_PREF_STRING,
                        PreferenceConfiguration.DEFAULT_LAUNCH_FULLSCREEN);

        boolean enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                && prefEnabled
                && isDesktopMode(activity);

        View decor = activity.getWindow().getDecorView();
        if (decor == null) {
            return;
        }

        if (enabled) {
            if (!applied) {
                previousVisibility = decor.getSystemUiVisibility();
            }
            applied = true;

            activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowInsetsController controller = activity.getWindow().getInsetsController();
                if (controller != null) {
                    controller.setSystemBarsBehavior(
                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    // systemBars includes captionBar, statusBars, navigationBars.
                    controller.hide(WindowInsets.Type.systemBars());
                }
            }
            decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        } else if (applied) {
            activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowInsetsController controller = activity.getWindow().getInsetsController();
                if (controller != null) {
                    controller.show(WindowInsets.Type.systemBars());
                }
            }
            decor.setSystemUiVisibility(previousVisibility);
            applied = false;
        }
    }
}
