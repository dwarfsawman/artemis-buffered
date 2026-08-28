package com.limelight.preferences;

import static org.junit.Assert.assertEquals;
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
    public void originalAudioTrackIsDefaultAndCallbackBufferIsOptIn() {
        Context context = ApplicationProvider.getApplicationContext();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        preferences.edit().clear().commit();

        assertFalse(PreferenceConfiguration.isCallbackAudioBufferEnabled(preferences));
        assertFalse(PreferenceConfiguration.isAdaptiveAudioBufferEnabled(preferences));

        preferences.edit()
                .putBoolean(PreferenceConfiguration.ENABLE_CALLBACK_AUDIO_BUFFER_PREF_STRING, true)
                .commit();
        assertTrue(PreferenceConfiguration.isCallbackAudioBufferEnabled(preferences));
        assertFalse(PreferenceConfiguration.isAdaptiveAudioBufferEnabled(preferences));
    }

    @Test
    public void adaptiveModeRequiresCallbackBuffer() {
        Context context = ApplicationProvider.getApplicationContext();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        preferences.edit().clear()
                .putBoolean(PreferenceConfiguration.ENABLE_ADAPTIVE_AUDIO_BUFFER_PREF_STRING, true)
                .commit();

        assertFalse(PreferenceConfiguration.isAdaptiveAudioBufferEnabled(preferences));

        preferences.edit()
                .putBoolean(PreferenceConfiguration.ENABLE_CALLBACK_AUDIO_BUFFER_PREF_STRING, true)
                .commit();
        assertTrue(PreferenceConfiguration.isAdaptiveAudioBufferEnabled(preferences));
    }

    @Test
    public void fixedAudioBufferDefaultsTo40AndIsClampedToSliderRange() {
        Context context = ApplicationProvider.getApplicationContext();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        preferences.edit().clear().commit();

        assertEquals(PreferenceConfiguration.DEFAULT_FIXED_AUDIO_BUFFER_MS,
                PreferenceConfiguration.getFixedAudioBufferMs(preferences));

        preferences.edit()
                .putInt(PreferenceConfiguration.FIXED_AUDIO_BUFFER_MS_PREF_STRING, 120)
                .commit();
        assertEquals(120, PreferenceConfiguration.getFixedAudioBufferMs(preferences));

        preferences.edit()
                .putInt(PreferenceConfiguration.FIXED_AUDIO_BUFFER_MS_PREF_STRING, 20)
                .commit();
        assertEquals(PreferenceConfiguration.MIN_FIXED_AUDIO_BUFFER_MS,
                PreferenceConfiguration.getFixedAudioBufferMs(preferences));

        preferences.edit()
                .putInt(PreferenceConfiguration.FIXED_AUDIO_BUFFER_MS_PREF_STRING, 200)
                .commit();
        assertEquals(PreferenceConfiguration.MAX_FIXED_AUDIO_BUFFER_MS,
                PreferenceConfiguration.getFixedAudioBufferMs(preferences));
    }
}
