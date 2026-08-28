package com.limelight.profiles;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.preferences.PreferenceConfiguration;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class SettingsPresetControllerTest {
    private static final String RESOLUTION_KEY = "list_resolution";
    private static final String FPS_KEY = "list_fps";
    private static final String BITRATE_KEY = "seekbar_bitrate_kbps";
    private static final String UNSET_KEY = "unset_default_test";
    private static final String STRING_SET_KEY = "string_set_test";

    private Context context;
    private SharedPreferences settings;
    private SettingsPresetController controller;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        settings = PreferenceManager.getDefaultSharedPreferences(context);
        settings.edit().clear()
                .putString(RESOLUTION_KEY, "1920x1080")
                .putString(FPS_KEY, "120")
                .putInt(BITRATE_KEY, 40000)
                .putStringSet(STRING_SET_KEY,
                        new HashSet<>(Arrays.asList("alpha", "beta")))
                .putBoolean(PreferenceConfiguration.ENABLE_CALLBACK_AUDIO_BUFFER_PREF_STRING, true)
                .putInt(PreferenceConfiguration.FIXED_AUDIO_BUFFER_MS_PREF_STRING, 80)
                .commit();

        deleteRecursively(new File(context.getFilesDir(), "profiles"));
        ProfilesManager.instance = null;
        ProfilesManager.getInstance().load(context);

        Set<String> managedKeys = new HashSet<>(Arrays.asList(
                RESOLUTION_KEY,
                FPS_KEY,
                BITRATE_KEY,
                UNSET_KEY,
                STRING_SET_KEY,
                PreferenceConfiguration.ENABLE_CALLBACK_AUDIO_BUFFER_PREF_STRING,
                PreferenceConfiguration.FIXED_AUDIO_BUFFER_MS_PREF_STRING));
        controller = new SettingsPresetController(context, settings, managedKeys);
    }

    @After
    public void tearDown() {
        ProfilesManager.instance = null;
        deleteRecursively(new File(context.getFilesDir(), "profiles"));
        settings.edit().clear().commit();
    }

    @Test
    public void addDetectSaveAndResetRoundTripAllManagedSettings() {
        SettingsProfile preset = controller.addPreset("Elite 120 Hz");

        assertTrue(controller.isActive(preset));
        assertFalse(controller.isDirty(preset));
        assertEquals("1920x1080", controller.getEffectiveValue(
                preset, RESOLUTION_KEY));
        assertEquals("120", controller.getEffectiveValue(
                preset, FPS_KEY));

        settings.edit()
                .putString(RESOLUTION_KEY, "2560x1440")
                .putString(FPS_KEY, "60")
                .putInt(BITRATE_KEY, 25000)
                .putBoolean(PreferenceConfiguration.ENABLE_CALLBACK_AUDIO_BUFFER_PREF_STRING, false)
                .putInt(PreferenceConfiguration.FIXED_AUDIO_BUFFER_MS_PREF_STRING, 120)
                .commit();
        assertTrue(controller.isDirty(preset));

        controller.resetPreset(preset);
        assertEquals("1920x1080", settings.getString(
                RESOLUTION_KEY, null));
        assertEquals("120", settings.getString(FPS_KEY, null));
        assertEquals(40000, settings.getInt(BITRATE_KEY, 0));
        assertTrue(settings.getBoolean(
                PreferenceConfiguration.ENABLE_CALLBACK_AUDIO_BUFFER_PREF_STRING, false));
        assertEquals(80, settings.getInt(
                PreferenceConfiguration.FIXED_AUDIO_BUFFER_MS_PREF_STRING, 0));
        assertFalse(controller.isDirty(preset));

        settings.edit()
                .putInt(BITRATE_KEY, 50000)
                .putInt(PreferenceConfiguration.FIXED_AUDIO_BUFFER_MS_PREF_STRING, 100)
                .commit();
        controller.savePreset(preset);
        assertFalse(controller.isDirty(preset));
        assertEquals(50000, ((Number) controller.getEffectiveValue(
                preset, BITRATE_KEY)).intValue());
        assertEquals(100, ((Number) controller.getEffectiveValue(
                preset, PreferenceConfiguration.FIXED_AUDIO_BUFFER_MS_PREF_STRING)).intValue());
    }

    @Test
    public void selectingExistingPresetAppliesItsValuesAndNameCanBeEdited() {
        SettingsProfile first = controller.addPreset("First");
        settings.edit()
                .putString(RESOLUTION_KEY, "3840x2160")
                .putString(FPS_KEY, "60")
                .putInt(BITRATE_KEY, 80000)
                .commit();
        SettingsProfile second = controller.addPreset("Second");

        controller.selectPreset(first);
        assertEquals("1920x1080", settings.getString(
                RESOLUTION_KEY, null));
        assertEquals("120", settings.getString(FPS_KEY, null));
        assertEquals(40000, settings.getInt(BITRATE_KEY, 0));

        controller.renamePreset(second, "4K TV");
        assertEquals("4K TV", second.getName());
    }

    @Test
    public void deletingPresetRemovesItAndClearsActiveSelection() {
        SettingsProfile preset = controller.addPreset("Temporary");

        controller.deletePreset(preset);

        assertTrue(controller.getPresets().isEmpty());
        assertNull(controller.getActivePreset());

        ProfilesManager.instance = null;
        ProfilesManager reloadedManager = ProfilesManager.getInstance();
        assertTrue(reloadedManager.load(context));
        assertTrue(reloadedManager.getProfiles().isEmpty());
        assertNull(reloadedManager.getActive());
    }

    @Test
    public void unsetDefaultsAndStringSetsSurviveProfilePersistence() {
        controller.addPreset("Persistent");

        ProfilesManager.instance = null;
        ProfilesManager reloadedManager = ProfilesManager.getInstance();
        assertTrue(reloadedManager.load(context));
        controller = new SettingsPresetController(context, settings, new HashSet<>(Arrays.asList(
                RESOLUTION_KEY, FPS_KEY, BITRATE_KEY, UNSET_KEY, STRING_SET_KEY,
                PreferenceConfiguration.ENABLE_CALLBACK_AUDIO_BUFFER_PREF_STRING,
                PreferenceConfiguration.FIXED_AUDIO_BUFFER_MS_PREF_STRING)));
        SettingsProfile reloaded = controller.getPresets().get(0);

        assertEquals(new HashSet<>(Arrays.asList("alpha", "beta")),
                reloadedManager.getOverlayingSharedPreferences(context).getStringSet(
                        STRING_SET_KEY, null));
        settings.edit().putBoolean(UNSET_KEY, true).commit();
        assertTrue(controller.isDirty(reloaded));
        assertFalse(reloadedManager.getOverlayingSharedPreferences(context).contains(UNSET_KEY));
        assertFalse(reloadedManager.getOverlayingSharedPreferences(context).getBoolean(
                UNSET_KEY, false));

        controller.resetPreset(reloaded);
        assertFalse(settings.contains(UNSET_KEY));
        assertFalse(controller.isDirty(reloaded));
    }

    private static void deleteRecursively(File file) {
        if (!file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
