package com.limelight.profiles;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.limelight.preferences.PreferenceConfiguration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Adapts the existing settings profile storage for the in-settings preset carousel.
 *
 * Presets created or saved here contain a complete snapshot of the persistent settings
 * represented by the settings screen. Older profiles remain valid as sparse overlays.
 */
public final class SettingsPresetController {
    private final SharedPreferences settings;
    private final Set<String> managedKeys;
    private final ProfilesManager profilesManager;

    public SettingsPresetController(@NonNull Context context,
                                    @NonNull SharedPreferences settings,
                                    @NonNull Set<String> managedKeys) {
        this.settings = settings;
        this.managedKeys = new HashSet<>(managedKeys);
        this.profilesManager = ProfilesManager.getInstance();
        migrateFixedAudioBufferValues();
    }

    @NonNull
    public List<SettingsProfile> getPresets() {
        return profilesManager.getProfiles();
    }

    @Nullable
    public SettingsProfile getActivePreset() {
        return profilesManager.getActive();
    }

    @NonNull
    public SettingsProfile addPreset(@NonNull String name) {
        long now = System.currentTimeMillis();
        SettingsProfile profile = new SettingsProfile(
                UUID.randomUUID(),
                name,
                now,
                now,
                captureCurrentSettings());
        profilesManager.add(profile);
        profilesManager.setActive(profile.getUuid());
        return profile;
    }

    public void selectPreset(@NonNull SettingsProfile profile) {
        applyPreset(profile);
        profilesManager.setActive(profile.getUuid());
    }

    public void savePreset(@NonNull SettingsProfile profile) {
        profile.setOptions(captureCurrentSettings());
        profile.setModifiedUtc(System.currentTimeMillis());
        profilesManager.update(profile);
    }

    public void resetPreset(@NonNull SettingsProfile profile) {
        applyPreset(profile);
    }

    public void renamePreset(@NonNull SettingsProfile profile, @NonNull String name) {
        String trimmedName = name.trim();
        if (trimmedName.isEmpty() || trimmedName.equals(profile.getName())) {
            return;
        }

        profile.setName(trimmedName);
        profile.setModifiedUtc(System.currentTimeMillis());
        profilesManager.update(profile);
    }

    public void deletePreset(@NonNull SettingsProfile profile) {
        profilesManager.delete(profile.getUuid());
    }

    public boolean isActive(@NonNull SettingsProfile profile) {
        SettingsProfile active = getActivePreset();
        return active != null && active.getUuid().equals(profile.getUuid());
    }

    public boolean isDirty(@NonNull SettingsProfile profile) {
        if (!isActive(profile)) {
            return false;
        }

        Map<String, ?> currentValues = settings.getAll();
        Map<String, Object> presetValues = profile.getOptions();
        if (presetValues == null) {
            return false;
        }

        Set<String> unsetKeys = getUnsetKeys(presetValues);
        for (String key : managedKeys) {
            if (presetValues.containsKey(key) && !valuesEqual(
                    getEffectiveCurrentValue(currentValues, key), presetValues.get(key))) {
                return true;
            }
            if (unsetKeys.contains(key) && currentValues.containsKey(key)) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    public Object getEffectiveValue(@NonNull SettingsProfile profile, @NonNull String key) {
        Map<String, ?> currentValues = settings.getAll();
        if (isActive(profile)) {
            // Show pending edits on the selected card before they are saved.
            return getEffectiveCurrentValue(currentValues, key);
        }

        Map<String, Object> presetValues = profile.getOptions();
        if (presetValues != null && presetValues.containsKey(key)) {
            return presetValues.get(key);
        }
        if (presetValues != null && getUnsetKeys(presetValues).contains(key)) {
            return null;
        }
        return getEffectiveCurrentValue(currentValues, key);
    }

    private Map<String, Object> captureCurrentSettings() {
        Map<String, ?> currentValues = settings.getAll();
        Map<String, Object> snapshot = new HashMap<>();
        List<String> unsetKeys = new ArrayList<>();
        for (String key : managedKeys) {
            if (PreferenceConfiguration.FIXED_AUDIO_BUFFER_MS_PREF_STRING.equals(key)) {
                // Store the effective default even before the slider has written a value.
                // This keeps every preset self-contained for fixed-buffer playback.
                snapshot.put(key, PreferenceConfiguration.getFixedAudioBufferMs(settings));
            }
            else if (currentValues.containsKey(key)) {
                snapshot.put(key, copyValue(currentValues.get(key)));
            }
            else {
                unsetKeys.add(key);
            }
        }
        snapshot.put(ProfilesManager.SNAPSHOT_VERSION_KEY, 1);
        snapshot.put(ProfilesManager.UNSET_KEYS_KEY, unsetKeys);
        return snapshot;
    }

    private void migrateFixedAudioBufferValues() {
        if (!managedKeys.contains(PreferenceConfiguration.FIXED_AUDIO_BUFFER_MS_PREF_STRING)) {
            return;
        }

        for (SettingsProfile profile : profilesManager.getProfiles()) {
            Map<String, Object> existing = profile.getOptions();
            if (existing != null && existing.containsKey(
                    PreferenceConfiguration.FIXED_AUDIO_BUFFER_MS_PREF_STRING)) {
                continue;
            }

            Map<String, Object> migrated = existing == null ?
                    new HashMap<>() : new HashMap<>(existing);
            migrated.put(PreferenceConfiguration.FIXED_AUDIO_BUFFER_MS_PREF_STRING,
                    PreferenceConfiguration.DEFAULT_FIXED_AUDIO_BUFFER_MS);
            removeUnsetKey(migrated,
                    PreferenceConfiguration.FIXED_AUDIO_BUFFER_MS_PREF_STRING);
            profile.setOptions(migrated);
            profilesManager.update(profile);
        }
    }

    private static void removeUnsetKey(Map<String, Object> values, String keyToRemove) {
        Object value = values.get(ProfilesManager.UNSET_KEYS_KEY);
        if (!(value instanceof Iterable)) {
            return;
        }

        List<String> remaining = new ArrayList<>();
        for (Object key : (Iterable<?>) value) {
            if (key != null && !keyToRemove.equals(key.toString())) {
                remaining.add(key.toString());
            }
        }
        values.put(ProfilesManager.UNSET_KEYS_KEY, remaining);
    }

    private void applyPreset(@NonNull SettingsProfile profile) {
        Map<String, Object> presetValues = profile.getOptions();
        if (presetValues == null || presetValues.isEmpty()) {
            return;
        }

        Map<String, ?> currentValues = settings.getAll();
        Set<String> unsetKeys = getUnsetKeys(presetValues);
        SharedPreferences.Editor editor = settings.edit();
        for (String key : managedKeys) {
            if (unsetKeys.contains(key)) {
                editor.remove(key);
            }
            else if (!presetValues.containsKey(key)) {
                // Older profiles are sparse overlays and intentionally inherit missing keys.
                continue;
            }
            else {
                putValue(editor, key, presetValues.get(key), currentValues.get(key));
            }
        }
        editor.apply();
    }

    private static Set<String> getUnsetKeys(Map<String, Object> presetValues) {
        Set<String> unsetKeys = new HashSet<>();
        if (!presetValues.containsKey(ProfilesManager.SNAPSHOT_VERSION_KEY)) {
            return unsetKeys;
        }

        Object value = presetValues.get(ProfilesManager.UNSET_KEYS_KEY);
        if (value instanceof Iterable) {
            for (Object key : (Iterable<?>) value) {
                if (key != null) {
                    unsetKeys.add(key.toString());
                }
            }
        }
        return unsetKeys;
    }

    private static void putValue(SharedPreferences.Editor editor, String key,
                                 Object value, Object currentValue) {
        if (value == null) {
            editor.remove(key);
        }
        else if (value instanceof Boolean) {
            editor.putBoolean(key, (Boolean) value);
        }
        else if (value instanceof String) {
            editor.putString(key, (String) value);
        }
        else if (value instanceof Set) {
            Set<String> strings = new HashSet<>();
            for (Object item : (Set<?>) value) {
                if (item != null) {
                    strings.add(item.toString());
                }
            }
            editor.putStringSet(key, strings);
        }
        else if (value instanceof List) {
            // Gson restores persisted string sets as lists because profile options use Object.
            Set<String> strings = new HashSet<>();
            for (Object item : (List<?>) value) {
                if (item != null) {
                    strings.add(item.toString());
                }
            }
            editor.putStringSet(key, strings);
        }
        else if (value instanceof Number) {
            Number number = (Number) value;
            if (currentValue instanceof Long) {
                editor.putLong(key, number.longValue());
            }
            else if (currentValue instanceof Float) {
                editor.putFloat(key, number.floatValue());
            }
            else {
                // Android preference XML numeric values are ints. Gson restores them as Double,
                // so default to int when the current value doesn't provide stronger type data.
                editor.putInt(key, number.intValue());
            }
        }
    }

    private static boolean valuesEqual(Object first, Object second) {
        if (first instanceof Number && second instanceof Number) {
            return Double.compare(((Number) first).doubleValue(),
                    ((Number) second).doubleValue()) == 0;
        }
        if (first instanceof Set && second instanceof List) {
            return first.equals(new HashSet<>((List<?>) second));
        }
        if (first instanceof List && second instanceof Set) {
            return new HashSet<>((List<?>) first).equals(second);
        }
        return Objects.equals(first, second);
    }

    private static Object getEffectiveCurrentValue(Map<String, ?> currentValues, String key) {
        Object value = currentValues.get(key);
        if (!PreferenceConfiguration.FIXED_AUDIO_BUFFER_MS_PREF_STRING.equals(key)) {
            return value;
        }
        if (!(value instanceof Number)) {
            return PreferenceConfiguration.DEFAULT_FIXED_AUDIO_BUFFER_MS;
        }

        int milliseconds = ((Number) value).intValue();
        return Math.max(PreferenceConfiguration.MIN_FIXED_AUDIO_BUFFER_MS,
                Math.min(PreferenceConfiguration.MAX_FIXED_AUDIO_BUFFER_MS, milliseconds));
    }

    private static Object copyValue(Object value) {
        if (value instanceof Set) {
            return new HashSet<>((Set<?>) value);
        }
        if (value instanceof List) {
            return new ArrayList<>((List<?>) value);
        }
        return value;
    }
}
