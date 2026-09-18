package com.limelight;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.Intent;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

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
    public void trampolineStartsPcViewAndFinishes() {
        LaunchTrampoline activity = Robolectric.buildActivity(LaunchTrampoline.class).create().get();
        ShadowActivity.IntentForResult launch = shadowOf(activity).getNextStartedActivityForResult();
        assertEquals(PcView.class.getName(), launch.intent.getComponent().getClassName());
        assertEquals(Intent.ACTION_MAIN, launch.intent.getAction());
        assertTrue(launch.intent.hasCategory(Intent.CATEGORY_LAUNCHER));
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, launch.intent.getFlags());
        assertNull(launch.options);
        assertTrue(activity.isFinishing());
    }

    @Test
    public void launcherResolvesToTrampoline() {
        Context context = ApplicationProvider.getApplicationContext();
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        assertNotNull(launch);
        assertEquals(LaunchTrampoline.class.getName(), launch.getComponent().getClassName());
    }
}
