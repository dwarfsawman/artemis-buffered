package com.limelight.profiles;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;

import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceViewHolder;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.R;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.preferences.SettingsPresetPreference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;

import java.io.File;
import java.lang.reflect.Constructor;
import java.util.Collections;
import java.util.HashMap;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class SettingsPresetPreferenceUiTest {
    private Context context;
    private SharedPreferences settings;
    private ProfilesManager profilesManager;
    private File profilesDir;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        settings = PreferenceManager.getDefaultSharedPreferences(context);
        settings.edit().clear().commit();

        profilesDir = new File(context.getFilesDir(), "profiles");
        deleteRecursively(profilesDir);
        ProfilesManager.instance = null;
        profilesManager = ProfilesManager.getInstance();
        profilesManager.load(context);
    }

    @After
    public void tearDown() {
        ProfilesManager.instance = null;
        deleteRecursively(profilesDir);
        settings.edit().clear().commit();
    }

    @Test
    public void longPressingPresetCardConfirmsAndDeletesIt() throws Exception {
        SettingsProfile profile = new SettingsProfile(
                UUID.randomUUID(),
                "Temporary",
                System.currentTimeMillis(),
                System.currentTimeMillis(),
                new HashMap<>());
        profilesManager.add(profile);
        profilesManager.setActive(profile.getUuid());

        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        SettingsPresetPreference preference = new SettingsPresetPreference(activity);
        preference.configure(settings, Collections.emptySet(), () -> { });

        View preferenceView = LayoutInflater.from(activity).inflate(
                R.layout.preference_settings_preset_carousel, null, false);
        activity.setContentView(preferenceView);
        Constructor<PreferenceViewHolder> constructor =
                PreferenceViewHolder.class.getDeclaredConstructor(View.class);
        constructor.setAccessible(true);
        preference.onBindViewHolder(constructor.newInstance(preferenceView));

        RecyclerView carousel = preferenceView.findViewById(R.id.settingsPresetCarousel);
        carousel.measure(
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY));
        carousel.layout(0, 0, 1000, 500);
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        RecyclerView.ViewHolder holder = carousel.findViewHolderForAdapterPosition(0);
        assertNotNull(holder);
        View card = holder.itemView.findViewById(R.id.settingsPresetCard);
        assertTrue(card.performLongClick());

        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        assertEquals(1, profilesManager.getProfiles().size());

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertTrue(profilesManager.getProfiles().isEmpty());
        assertNull(profilesManager.getActive());
        assertEquals(1, carousel.getAdapter().getItemCount());

        preference.onDetached();
    }

    @Test
    public void dirtySelectedPresetShowsActionsAndSaveOverwritesBufferValue() throws Exception {
        settings.edit()
                .putInt(PreferenceConfiguration.FIXED_AUDIO_BUFFER_MS_PREF_STRING, 80)
                .commit();
        HashMap<String, Object> options = new HashMap<>();
        options.put(PreferenceConfiguration.FIXED_AUDIO_BUFFER_MS_PREF_STRING, 80);
        SettingsProfile profile = new SettingsProfile(
                UUID.randomUUID(),
                "Audio",
                System.currentTimeMillis(),
                System.currentTimeMillis(),
                options);
        profilesManager.add(profile);
        profilesManager.setActive(profile.getUuid());

        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        SettingsPresetPreference preference = new SettingsPresetPreference(activity);
        preference.configure(settings, Collections.singleton(
                PreferenceConfiguration.FIXED_AUDIO_BUFFER_MS_PREF_STRING), () -> { });

        View preferenceView = LayoutInflater.from(activity).inflate(
                R.layout.preference_settings_preset_carousel, null, false);
        activity.setContentView(preferenceView);
        Constructor<PreferenceViewHolder> constructor =
                PreferenceViewHolder.class.getDeclaredConstructor(View.class);
        constructor.setAccessible(true);
        preference.onBindViewHolder(constructor.newInstance(preferenceView));

        RecyclerView carousel = preferenceView.findViewById(R.id.settingsPresetCarousel);
        carousel.measure(
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY));
        carousel.layout(0, 0, 1000, 500);
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        RecyclerView.ViewHolder holder = carousel.findViewHolderForAdapterPosition(0);
        assertNotNull(holder);
        View actions = holder.itemView.findViewById(R.id.settingsPresetActions);
        View save = holder.itemView.findViewById(R.id.settingsPresetSave);
        View reset = holder.itemView.findViewById(R.id.settingsPresetReset);
        assertEquals(View.INVISIBLE, actions.getVisibility());

        settings.edit()
                .putInt(PreferenceConfiguration.FIXED_AUDIO_BUFFER_MS_PREF_STRING, 100)
                .commit();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(View.VISIBLE, actions.getVisibility());

        assertTrue(save.performClick());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(100, ((Number) profile.getOptions().get(
                PreferenceConfiguration.FIXED_AUDIO_BUFFER_MS_PREF_STRING)).intValue());
        assertEquals(View.INVISIBLE, actions.getVisibility());

        settings.edit()
                .putInt(PreferenceConfiguration.FIXED_AUDIO_BUFFER_MS_PREF_STRING, 120)
                .commit();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(View.VISIBLE, actions.getVisibility());

        assertTrue(reset.performClick());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(100, settings.getInt(
                PreferenceConfiguration.FIXED_AUDIO_BUFFER_MS_PREF_STRING, -1));
        assertFalse(new SettingsPresetController(activity, settings, Collections.singleton(
                PreferenceConfiguration.FIXED_AUDIO_BUFFER_MS_PREF_STRING)).isDirty(profile));
        assertEquals(View.INVISIBLE, actions.getVisibility());

        preference.onDetached();
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
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
