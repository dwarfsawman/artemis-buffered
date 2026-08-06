package com.limelight.binding.audio;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class WsolaTimeStretcherTest {
    private static final int SAMPLE_RATE = 48_000;
    private static final int CHANNELS = 2;

    @Test
    public void correctionStaysWithinRequestedThreePercentAndFrameAlignment() {
        WsolaTimeStretcher stretcher = new WsolaTimeStretcher(SAMPLE_RATE, CHANNELS);
        short[] input = stereoSine(480);

        short[] expanded = stretcher.process(input, 0.97);
        short[] compressed = stretcher.process(input, 1.03);

        assertEquals(Math.round(480 / 0.97) * CHANNELS, expanded.length);
        assertEquals(Math.round(480 / 1.03) * CHANNELS, compressed.length);
        assertEquals(0, expanded.length % CHANNELS);
        assertEquals(0, compressed.length % CHANNELS);
    }

    @Test
    public void unityRateReturnsOriginalBufferWithoutAllocation() {
        WsolaTimeStretcher stretcher = new WsolaTimeStretcher(SAMPLE_RATE, CHANNELS);
        short[] input = stereoSine(240);

        assertSame(input, stretcher.process(input, 1.0));
    }

    @Test
    public void overlapAddDoesNotIntroduceHardSampleDiscontinuity() {
        WsolaTimeStretcher stretcher = new WsolaTimeStretcher(SAMPLE_RATE, CHANNELS);
        short[] input = stereoSine(960);

        short[] adjusted = stretcher.process(input, 0.97);
        int maximumStep = 0;
        for (int frame = 1; frame < adjusted.length / CHANNELS; frame++) {
            maximumStep = Math.max(maximumStep,
                    Math.abs(adjusted[frame * CHANNELS] - adjusted[(frame - 1) * CHANNELS]));
            assertEquals(adjusted[frame * CHANNELS], adjusted[frame * CHANNELS + 1]);
        }

        assertTrue("unexpected hard splice: " + maximumStep, maximumStep < 2_500);
    }

    private static short[] stereoSine(int frames) {
        short[] pcm = new short[frames * CHANNELS];
        for (int frame = 0; frame < frames; frame++) {
            short sample = (short) Math.round(Math.sin(2 * Math.PI * 440 * frame / SAMPLE_RATE) * 12_000);
            pcm[frame * CHANNELS] = sample;
            pcm[frame * CHANNELS + 1] = sample;
        }
        return pcm;
    }
}
