package com.limelight.binding.input;

import android.view.KeyEvent;

import com.limelight.preferences.PreferenceConfiguration;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33})
public class KeyboardTranslatorTest {
    private static final int WINDOWS_VK_F13 = 0x7C;

    private KeyboardTranslator translator;

    @Before
    public void setUp() {
        translator = new KeyboardTranslator(new PreferenceConfiguration());
    }

    @Test
    public void translatesAndroid16FunctionKeysToWindowsVirtualKeys() {
        for (int keyCode = KeyEvent.KEYCODE_F13; keyCode <= KeyEvent.KEYCODE_F24; keyCode++) {
            int expectedVirtualKey = WINDOWS_VK_F13 +
                    (keyCode - KeyEvent.KEYCODE_F13);
            short expectedPacketKey = (short) (0x8000 | expectedVirtualKey);

            // Use an unmapped scan code to ensure translation comes from the Android key code.
            assertEquals("keyCode=" + keyCode,
                    expectedPacketKey, translator.translate(keyCode, 0, -1));
        }
    }
}
