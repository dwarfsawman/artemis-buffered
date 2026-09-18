package com.limelight.utils;

import android.app.Activity;
import android.os.Build;
import android.view.Display;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.profiles.ProfilesManager;

/** Immersive UI for Samsung external-display browsing windows. */
public final class DesktopFullscreen {
    private boolean applied;
    private int previousVisibility;

    @SuppressWarnings("deprecation")
    public void apply(Activity activity) {
        Display display = activity.getWindowManager().getDefaultDisplay();
        // Gate on the activity's display, not the presence of a connected monitor:
        // the phone UI must remain unchanged while DeX runs on another display.
        boolean enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                && "samsung".equalsIgnoreCase(Build.MANUFACTURER)
                && display.getDisplayId() != Display.DEFAULT_DISPLAY
                && ProfilesManager.getInstance().getOverlayingSharedPreferences(activity)
                .getBoolean(PreferenceConfiguration.LAUNCH_FULLSCREEN_PREF_STRING,
                        PreferenceConfiguration.DEFAULT_LAUNCH_FULLSCREEN);
        View decor = activity.getWindow().getDecorView();
        if (enabled) {
            if (!applied) {
                previousVisibility = decor.getSystemUiVisibility();
            }
            applied = true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowInsetsController controller = activity.getWindow().getInsetsController();
                if (controller != null) {
                    controller.setSystemBarsBehavior(
                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    // systemBars includes captionBar, unlike statusBars | navigationBars.
                    controller.hide(WindowInsets.Type.systemBars());
                }
            } else {
                decor.setSystemUiVisibility(previousVisibility
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }
        } else if (applied) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowInsetsController controller = activity.getWindow().getInsetsController();
                if (controller != null) {
                    controller.show(WindowInsets.Type.systemBars());
                }
            } else {
                decor.setSystemUiVisibility(previousVisibility);
            }
            applied = false;
        }
    }
}
