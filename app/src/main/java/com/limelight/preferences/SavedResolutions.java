package com.limelight.preferences;

import android.content.SharedPreferences;
import java.util.HashSet;
import java.util.Set;

/** Global resolution library, independent of the selected preset. */
final class SavedResolutions {
    static final String KEY = "saved_resolutions";

    static String normalize(String input) {
        if (input == null) return null;
        String value = input.trim().toLowerCase(java.util.Locale.ROOT)
                .replace('×', 'x').replaceAll("\\s+", "");
        if (!value.matches("[0-9]{1,4}x[0-9]{1,4}")) return null;
        String[] dimensions = value.split("x");
        int width = Integer.parseInt(dimensions[0]);
        int height = Integer.parseInt(dimensions[1]);
        return width > 0 && height > 0 ? width + "x" + height : null;
    }

    static Set<String> load(SharedPreferences prefs) {
        Set<String> saved = new HashSet<>(prefs.getStringSet(KEY, new HashSet<>()));
        if (prefs.contains(PreferenceConfiguration.CUSTOM_RESOLUTION_PREF_STRING)) {
            String legacy = normalize(prefs.getString(
                    PreferenceConfiguration.CUSTOM_RESOLUTION_PREF_STRING, null));
            if (legacy != null && PreferenceConfiguration.isNativeResolution(
                    Integer.parseInt(legacy.split("x")[0]),
                    Integer.parseInt(legacy.split("x")[1]))) {
                saved.add(legacy);
            }
            save(prefs, saved);
            prefs.edit().remove(PreferenceConfiguration.CUSTOM_RESOLUTION_PREF_STRING).apply();
        }
        return saved;
    }

    static void save(SharedPreferences prefs, Set<String> saved) {
        prefs.edit().putStringSet(KEY, new HashSet<>(saved)).apply();
    }
}
