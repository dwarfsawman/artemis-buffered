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
            // Samsung documents empty launch bounds as fullscreen in DeX. On a
            // phone's normal fullscreen task this does not change system-bar visibility.
            // Avoid private DeX detection APIs, which differ between One UI versions.
            ActivityOptions activityOptions = ActivityOptions.makeBasic();
            activityOptions.setLaunchBounds(new Rect(0, 0, 0, 0));
            options = activityOptions.toBundle();
        }
        startActivity(target, options);
        finish();
    }
}
