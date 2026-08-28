package com.limelight.preferences;

import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;

import com.limelight.R;

import androidx.preference.Preference;
import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@Config(sdk = {33}, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class
})
@RunWith(RobolectricTestRunner.class)
public class StreamSettingsTouchTest {
    private ActivityController<StreamSettings> activityController;
    private StreamSettings activity;

    @Before
    public void setUp() {
        PreferenceManager.getDefaultSharedPreferences(
                ApplicationProvider.getApplicationContext()).edit().clear().commit();
        activityController = Robolectric.buildActivity(StreamSettings.class).setup();
        activity = activityController.get();
        activity.getSupportFragmentManager().executePendingTransactions();
    }

    @After
    public void tearDown() {
        activityController.destroy();
    }

    @Test
    public void touchingOutsideCarouselClearsPresetNameFocus() {
        TouchHarness harness = createTouchHarness();

        dispatchDown(harness.carouselBounds.right + 40, harness.carouselBounds.bottom + 40);

        assertFalse(harness.editor.hasFocus());
    }

    @Test
    public void touchingInsideCarouselKeepsPresetNameFocus() {
        TouchHarness harness = createTouchHarness();

        dispatchDown(harness.carouselBounds.centerX(), harness.carouselBounds.centerY());

        assertTrue(harness.editor.hasFocus());
    }

    @Test
    public void fixedBufferSliderFollowsCallbackAndAdaptiveToggles() {
        StreamSettings.SettingsFragment fragment = (StreamSettings.SettingsFragment)
                activity.getSupportFragmentManager().findFragmentById(R.id.stream_settings);
        assertTrue(fragment != null);

        ArtemisSwitchPreference callback = fragment.findPreference(
                PreferenceConfiguration.ENABLE_CALLBACK_AUDIO_BUFFER_PREF_STRING);
        ArtemisSwitchPreference adaptive = fragment.findPreference(
                PreferenceConfiguration.ENABLE_ADAPTIVE_AUDIO_BUFFER_PREF_STRING);
        Preference fixedBuffer = fragment.findPreference(
                PreferenceConfiguration.FIXED_AUDIO_BUFFER_MS_PREF_STRING);
        assertTrue(callback != null);
        assertTrue(adaptive != null);
        assertTrue(fixedBuffer != null);
        assertFalse(fixedBuffer.isVisible());

        assertTrue(callback.getOnPreferenceChangeListener()
                .onPreferenceChange(callback, true));
        callback.setChecked(true);
        assertTrue(fixedBuffer.isVisible());

        assertTrue(adaptive.getOnPreferenceChangeListener()
                .onPreferenceChange(adaptive, true));
        adaptive.setChecked(true);
        assertFalse(fixedBuffer.isVisible());

        assertTrue(adaptive.getOnPreferenceChangeListener()
                .onPreferenceChange(adaptive, false));
        adaptive.setChecked(false);
        assertTrue(fixedBuffer.isVisible());

        assertTrue(callback.getOnPreferenceChangeListener()
                .onPreferenceChange(callback, false));
        assertFalse(fixedBuffer.isVisible());
    }

    private TouchHarness createTouchHarness() {
        FrameLayout root = new FrameLayout(activity);
        root.setId(R.id.stream_settings);

        FrameLayout carousel = new FrameLayout(activity);
        carousel.setId(R.id.settingsPresetCarousel);
        FrameLayout.LayoutParams carouselParams = new FrameLayout.LayoutParams(200, 150);
        carouselParams.leftMargin = 20;
        carouselParams.topMargin = 20;
        root.addView(carousel, carouselParams);

        EditText editor = new EditText(activity);
        editor.setId(R.id.settingsPresetName);
        editor.setFocusableInTouchMode(true);
        carousel.addView(editor, new FrameLayout.LayoutParams(120, 60));

        activity.setContentView(root);
        int width = View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY);
        int height = View.MeasureSpec.makeMeasureSpec(470, View.MeasureSpec.EXACTLY);
        root.measure(width, height);
        root.layout(0, 0, 320, 470);

        assertTrue(editor.requestFocus());
        assertTrue(editor.hasFocus());

        int[] carouselLocation = new int[2];
        carousel.getLocationOnScreen(carouselLocation);
        Rect carouselBounds = new Rect(
                carouselLocation[0],
                carouselLocation[1],
                carouselLocation[0] + carousel.getWidth(),
                carouselLocation[1] + carousel.getHeight());
        return new TouchHarness(editor, carouselBounds);
    }

    private void dispatchDown(float x, float y) {
        MotionEvent event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0);
        try {
            activity.dispatchTouchEvent(event);
        }
        finally {
            event.recycle();
        }
    }

    private static final class TouchHarness {
        final EditText editor;
        final Rect carouselBounds;

        TouchHarness(EditText editor, Rect carouselBounds) {
            this.editor = editor;
            this.carouselBounds = carouselBounds;
        }
    }
}
