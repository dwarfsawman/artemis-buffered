package com.limelight.preferences;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class AudioDiagnosticsPreferenceTest {
    @Test
    public void audioDiagnosticsAreOptInAndReadFromSettings() {
        Context context = ApplicationProvider.getApplicationContext();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        preferences.edit().clear().commit();

        assertFalse(PreferenceConfiguration.isAudioDiagnosticsEnabled(preferences));

        preferences.edit()
                .putBoolean(PreferenceConfiguration.ENABLE_AUDIO_DIAGNOSTICS_PREF_STRING, true)
                .commit();
        assertTrue(PreferenceConfiguration.isAudioDiagnosticsEnabled(preferences));
    }

    @Test
    public void fixedAudioBufferIsDefaultAndAdaptiveModeIsOptIn() {
        Context context = ApplicationProvider.getApplicationContext();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        preferences.edit().clear().commit();

        assertFalse(PreferenceConfiguration.isAdaptiveAudioBufferEnabled(preferences));

        preferences.edit()
                .putBoolean(PreferenceConfiguration.ENABLE_ADAPTIVE_AUDIO_BUFFER_PREF_STRING, true)
                .commit();
        assertTrue(PreferenceConfiguration.isAdaptiveAudioBufferEnabled(preferences));
    }
}
