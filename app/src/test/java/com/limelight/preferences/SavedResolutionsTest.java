package com.limelight.preferences;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.core.app.ApplicationProvider;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.Set;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class SavedResolutionsTest {
    @Test
    public void validatesAndNormalizesDimensions() {
        assertEquals("2560x1600", SavedResolutions.normalize(" 2560 × 1600 "));
        assertEquals("1920x1200", SavedResolutions.normalize("1920X1200"));
        for (String invalid : new String[]{"0x1200", "-1x1080", "10000x1600", "1920x", "1x2x3"}) {
            assertNull(SavedResolutions.normalize(invalid));
        }
    }

    @Test
    public void migratesLegacyAndPersistsMultipleValuesAndDeletion() {
        Context context = ApplicationProvider.getApplicationContext();
        SharedPreferences prefs = context.getSharedPreferences("resolution-test", Context.MODE_PRIVATE);
        prefs.edit().clear().putString(PreferenceConfiguration.CUSTOM_RESOLUTION_PREF_STRING,
                "2304x1440").commit();
        Set<String> values = SavedResolutions.load(prefs);
        assertTrue(values.contains("2304x1440"));
        assertFalse(prefs.contains(PreferenceConfiguration.CUSTOM_RESOLUTION_PREF_STRING));
        values.add("2880x1800");
        SavedResolutions.save(prefs, values);
        assertEquals(2, SavedResolutions.load(prefs).size());
        values.remove("2304x1440");
        SavedResolutions.save(prefs, values);
        assertEquals(values, SavedResolutions.load(prefs));
        assertFalse(SavedResolutions.load(prefs).contains("2304x1440"));
    }

    @Test
    public void builtIn1600pFollows1440p() {
        Context context = ApplicationProvider.getApplicationContext();
        String[] values = context.getResources().getStringArray(com.limelight.R.array.resolution_values);
        int index = java.util.Arrays.asList(values).indexOf("2560x1440");
        assertEquals("2560x1600", values[index + 1]);
        assertFalse(PreferenceConfiguration.isNativeResolution(2560, 1600));
    }
}
