package com.limelight;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.preferences.PreferenceConfiguration;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowBuild;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class LaunchTrampolineTest {
    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit();
        ShadowBuild.setManufacturer("samsung");
    }

    @Test
    public void freshInstallRequestsFullscreenAndFinishesTrampoline() {
        LaunchTrampoline activity = Robolectric.buildActivity(LaunchTrampoline.class).create().get();
        ShadowActivity.IntentForResult launch = shadowOf(activity).getNextStartedActivityForResult();
        assertEquals(PcView.class.getName(), launch.intent.getComponent().getClassName());
        assertEquals(Intent.ACTION_MAIN, launch.intent.getAction());
        assertTrue(launch.intent.hasCategory(Intent.CATEGORY_LAUNCHER));
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, launch.intent.getFlags());
        assertNotNull(launch.options);
        assertEquals(new Rect(), launch.options.getParcelable("android:activity.launchBounds"));
        assertTrue(activity.isFinishing());
    }

    @Test
    public void disabledUsesSystemWindowSize() {
        PreferenceManager.getDefaultSharedPreferences(ApplicationProvider.getApplicationContext())
                .edit().putBoolean(PreferenceConfiguration.LAUNCH_FULLSCREEN_PREF_STRING, false).commit();
        assertNormalLaunch();
    }

    @Test
    public void otherManufacturersKeepNormalLaunch() {
        ShadowBuild.setManufacturer("Google");
        assertNormalLaunch();
    }

    @Test
    @Config(sdk = 23)
    public void oldAndroidDoesNotUseLaunchBounds() {
        assertNormalLaunch();
    }

    @Test
    public void launcherResolvesToTrampoline() {
        Context context = ApplicationProvider.getApplicationContext();
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        assertNotNull(launch);
        assertEquals(LaunchTrampoline.class.getName(), launch.getComponent().getClassName());
    }

    private void assertNormalLaunch() {
        LaunchTrampoline activity = Robolectric.buildActivity(LaunchTrampoline.class).create().get();
        ShadowActivity.IntentForResult launch = shadowOf(activity).getNextStartedActivityForResult();
        assertEquals(PcView.class.getName(), launch.intent.getComponent().getClassName());
        assertNull(launch.options);
        assertTrue(activity.isFinishing());
    }
}
