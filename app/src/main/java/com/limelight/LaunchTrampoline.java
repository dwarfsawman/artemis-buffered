package com.limelight;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;

import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.profiles.ProfilesManager;

/** Applies the user's window-size preference before displaying the PC list. */
public class LaunchTrampoline extends Activity {
    // Hidden API Bundle keys to specify WINDOWING_MODE_FULLSCREEN (1) without reflection
    private static final String KEY_WINDOWING_MODE_DOT = "android.activity.windowingMode";
    private static final String KEY_WINDOWING_MODE_COLON = "android:activity.windowingMode";
    private static final int WINDOWING_MODE_FULLSCREEN = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent target = new Intent(this, PcView.class)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        boolean fullscreen = ProfilesManager.getInstance()
                .getOverlayingSharedPreferences(this).getBoolean(
                        PreferenceConfiguration.LAUNCH_FULLSCREEN_PREF_STRING,
                        PreferenceConfiguration.DEFAULT_LAUNCH_FULLSCREEN);

        Bundle options = null;
        if (fullscreen && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                "samsung".equalsIgnoreCase(Build.MANUFACTURER)) {
            // Samsung DeX and Android WindowManager require windowingMode=1 (fullscreen)
            // without specifying launch bounds (which would otherwise force freeform mode).
            ActivityOptions activityOptions = ActivityOptions.makeBasic();
            options = activityOptions.toBundle();
            if (options != null) {
                options.putInt(KEY_WINDOWING_MODE_DOT, WINDOWING_MODE_FULLSCREEN);
                options.putInt(KEY_WINDOWING_MODE_COLON, WINDOWING_MODE_FULLSCREEN);
            }
        }
        startActivity(target, options);
        finish();
    }
}
