package com.limelight.profiles;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

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
                    currentValues.get(key), presetValues.get(key))) {
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
            return currentValues.get(key);
        }

        Map<String, Object> presetValues = profile.getOptions();
        if (presetValues != null && presetValues.containsKey(key)) {
            return presetValues.get(key);
        }
        if (presetValues != null && getUnsetKeys(presetValues).contains(key)) {
            return null;
        }
        return currentValues.get(key);
    }

    private Map<String, Object> captureCurrentSettings() {
        Map<String, ?> currentValues = settings.getAll();
        Map<String, Object> snapshot = new HashMap<>();
        List<String> unsetKeys = new ArrayList<>();
        for (String key : managedKeys) {
            if (currentValues.containsKey(key)) {
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
