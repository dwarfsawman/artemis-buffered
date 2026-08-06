package com.limelight.preferences;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PreferenceConfigurationTest {
    @Test
    public void audioBufferValueIsParsedAndClamped() {
        assertEquals(0, PreferenceConfiguration.parseAudioBufferMs("0"));
        assertEquals(40, PreferenceConfiguration.parseAudioBufferMs("40"));
        assertEquals(200, PreferenceConfiguration.parseAudioBufferMs("200"));
        assertEquals(0, PreferenceConfiguration.parseAudioBufferMs("-1"));
        assertEquals(500, PreferenceConfiguration.parseAudioBufferMs("501"));
    }

    @Test
    public void invalidAudioBufferValueUsesDefault() {
        assertEquals(40, PreferenceConfiguration.parseAudioBufferMs("invalid"));
        assertEquals(40, PreferenceConfiguration.parseAudioBufferMs(null));
    }

    @Test
    public void custom2158By1440IsAStandardResolutionOption() {
        assertFalse(PreferenceConfiguration.isNativeResolution(2158, 1440));
        assertTrue(PreferenceConfiguration.isNativeResolution(2159, 1440));
    }
}
