package com.limelight.preferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.XmlResourceParser;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.preference.PreferenceViewHolder;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.xmlpull.v1.XmlPullParser;

import java.util.HashMap;
import java.util.Map;

@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class ArtemisSettingsAppearanceTest {
    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    @Test
    public void customSettingsAreTheFirstCategoryAndUseStyledPreferenceClasses()
            throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        Map<String, String> customTags = new HashMap<>();
        String firstCategoryKey = null;
        int customCategoryDepth = -1;

        try (XmlResourceParser parser = context.getResources().getXml(R.xml.preferences)) {
            int event;
            while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    String key = parser.getAttributeValue(ANDROID_NS, "key");
                    if (key != null && key.startsWith("category_") && firstCategoryKey == null) {
                        firstCategoryKey = key;
                    }
                    if ("category_artemis_buffered_settings".equals(key)) {
                        customCategoryDepth = parser.getDepth();
                        assertEquals("com.limelight.preferences.ArtemisPreferenceCategory",
                                parser.getName());
                    }
                    else if (customCategoryDepth != -1 &&
                            parser.getDepth() == customCategoryDepth + 1 && key != null) {
                        customTags.put(key, parser.getName());
                    }
                }
                else if (event == XmlPullParser.END_TAG &&
                        parser.getDepth() == customCategoryDepth) {
                    customCategoryDepth = -1;
                }
            }
        }

        assertEquals("category_artemis_buffered_settings", firstCategoryKey);
        assertEquals("com.limelight.preferences.ArtemisSwitchPreference",
                customTags.get(PreferenceConfiguration.ENABLE_CALLBACK_AUDIO_BUFFER_PREF_STRING));
        assertEquals("com.limelight.preferences.ArtemisSwitchPreference",
                customTags.get(PreferenceConfiguration.ENABLE_ADAPTIVE_AUDIO_BUFFER_PREF_STRING));
        assertEquals("com.limelight.preferences.ArtemisSwitchPreference",
                customTags.get(PreferenceConfiguration.ENABLE_AUDIO_DIAGNOSTICS_PREF_STRING));
        assertEquals("com.limelight.preferences.ArtemisPreference",
                customTags.get("share_audio_diagnostic_logs"));
    }

    @Test
    public void adaptiveBufferDependsOnCallbackBuffer() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();

        try (XmlResourceParser parser = context.getResources().getXml(R.xml.preferences)) {
            int event;
            while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (event != XmlPullParser.START_TAG) {
                    continue;
                }

                String key = parser.getAttributeValue(ANDROID_NS, "key");
                if (PreferenceConfiguration.ENABLE_ADAPTIVE_AUDIO_BUFFER_PREF_STRING.equals(key)) {
                    assertEquals(PreferenceConfiguration.ENABLE_CALLBACK_AUDIO_BUFFER_PREF_STRING,
                            parser.getAttributeValue(ANDROID_NS, "dependency"));
                    return;
                }
            }
        }

        throw new AssertionError("Adaptive audio buffer preference was not found");
    }

    @Test
    public void customRowsUseAccentBackgroundAndReadableText() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        verifyStyledRow(context);
    }

    @Test
    @Config(qualifiers = "night")
    public void customRowsRemainDistinctInDarkTheme() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        verifyStyledRow(context);
    }

    private static void verifyStyledRow(Context context) throws Exception {
        LinearLayout row = new LinearLayout(context);

        TextView title = new TextView(context);
        title.setId(android.R.id.title);
        row.addView(title);

        TextView summary = new TextView(context);
        summary.setId(android.R.id.summary);
        row.addView(summary);

        SwitchCompat switchView = new SwitchCompat(context);
        row.addView(switchView);

        java.lang.reflect.Constructor<PreferenceViewHolder> constructor =
                PreferenceViewHolder.class.getDeclaredConstructor(android.view.View.class);
        constructor.setAccessible(true);
        PreferenceViewHolder holder = constructor.newInstance(row);
        ArtemisPreferenceStyle.bind(context, holder, true, true);

        assertEquals(ContextCompat.getColor(context,
                        R.color.artemisBufferedPreferenceText),
                title.getCurrentTextColor());
        assertEquals(ContextCompat.getColor(context,
                        R.color.artemisBufferedPreferenceSummary),
                summary.getCurrentTextColor());
        assertTrue(row.getBackground() instanceof RippleDrawable);
        GradientDrawable card = (GradientDrawable)
                ((RippleDrawable) row.getBackground()).getDrawable(0);
        int background = ContextCompat.getColor(context,
                R.color.artemisBufferedPreferenceBackground);
        assertEquals(background, card.getColor().getDefaultColor());
        assertTrue(ColorUtils.calculateContrast(title.getCurrentTextColor(), background) >= 4.5);
        assertNotNull(switchView.getThumbTintList());
        assertTrue(switchView.getThumbTintList().isStateful());
    }
}
