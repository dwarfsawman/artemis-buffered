package com.limelight.binding.audio;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AdaptiveAudioBufferControllerTest {
    @Test
    public void stableDeliveryConvergesToTwentyMilliseconds() {
        AdaptiveAudioBufferController controller =
                new AdaptiveAudioBufferController(true, 80);

        for (long nowMs = 0; nowMs <= 30_000; nowMs += 5) {
            controller.onPacketArrival(nowMs, 5);
        }

        assertEquals(20, controller.getTargetMs());
        assertEquals(0.0, controller.getJitterMs(), 0.01);
    }

    @Test
    public void irregularDeliveryRaisesTargetWithinConfiguredCeiling() {
        AdaptiveAudioBufferController controller =
                new AdaptiveAudioBufferController(true, 80);
        long nowMs = 0;

        for (int packet = 0; packet < 400; packet++) {
            // A decoded cadence representative of packets bunching and separating while
            // one-way network latency moves within the 15-35 ms (30-70 ms RTT) range.
            nowMs += packet % 2 == 0 ? 1 : 9;
            controller.onPacketArrival(nowMs, 5);
        }

        assertTrue(controller.getTargetMs() > 20);
        assertTrue(controller.getTargetMs() <= 80);
    }

    @Test
    public void underrunRaisesTargetAndRateIsLimitedToThreePercent() {
        AdaptiveAudioBufferController controller =
                new AdaptiveAudioBufferController(true, 80);

        controller.onUnderrun(1_000);

        assertEquals(80, controller.getTargetMs());
        assertEquals(0.97, controller.getPlaybackRate(0), 0.0001);
        assertEquals(1.03, controller.getPlaybackRate(120), 0.0001);
        assertEquals(1.0, controller.getPlaybackRate(80), 0.0001);
    }

    @Test
    public void fixedVariantRetainsFortyMillisecondsWithoutStretch() {
        AdaptiveAudioBufferController controller =
                new AdaptiveAudioBufferController(false, 80);

        controller.onUnderrun(1_000);
        controller.onPacketArrival(1_100, 5);

        assertEquals(40, controller.getTargetMs());
        assertEquals(40, controller.getPendingLimitMs());
        assertEquals(1.0, controller.getPlaybackRate(0), 0.0);
    }
}
