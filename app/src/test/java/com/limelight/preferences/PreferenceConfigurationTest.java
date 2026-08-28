package com.limelight.preferences;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PreferenceConfigurationTest {
    @Test
    public void custom1920By1200IsAStandardResolutionOption() {
        assertFalse(PreferenceConfiguration.isNativeResolution(1920, 1200));
        assertTrue(PreferenceConfiguration.isNativeResolution(1920, 1201));
    }

    @Test
    public void custom2158By1440IsAStandardResolutionOption() {
        assertFalse(PreferenceConfiguration.isNativeResolution(2158, 1440));
        assertTrue(PreferenceConfiguration.isNativeResolution(2159, 1440));
    }
}
