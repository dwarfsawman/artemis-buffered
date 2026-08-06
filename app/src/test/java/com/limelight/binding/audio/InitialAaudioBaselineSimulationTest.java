package com.limelight.binding.audio;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Compares the v20.2.6 AudioTrack behavior with the first fixed-40-ms AAudio/SPSC design.
 * This is an application-side timing model; Android device and mixer scheduling are excluded.
 */
public class InitialAaudioBaselineSimulationTest {
    private static final int PACKET_MS = 5;
    private static final int DURATION_MS = 120_000;

    @Test
    public void fixedAaudioRemovesStarvationOnThirtyToSeventyMsRttTrace() {
        List<Double> arrivals = createThirtyToSeventyMsRttTrace();

        // v20.2.6 calls AudioTrack.play() before data arrives, so useful playback begins with
        // the first 5 ms decoded packet. The 40 ms check in that version limits the decoder
        // backlog; it is not an initial output-buffer target.
        Result taggedAudioTrack = simulate(arrivals, PACKET_MS, false);
        Result fixedAaudio = simulate(arrivals, 40, false);

        System.out.printf("v20.2.6 AudioTrack: starvationIntervals=%d missingAudio=%.2f ms " +
                        "averageBacklog=%.2f ms firstPacketToStart=%.2f ms%n",
                taggedAudioTrack.starvationIntervals, taggedAudioTrack.postStartSilenceMs,
                taggedAudioTrack.averageBufferedMs, taggedAudioTrack.firstPacketToStartMs);
        System.out.printf("fixed40 AAudio+SPSC: starvationIntervals=%d missingAudio=%.2f ms " +
                        "averageBacklog=%.2f ms firstPacketToStart=%.2f ms%n",
                fixedAaudio.starvationIntervals, fixedAaudio.postStartSilenceMs,
                fixedAaudio.averageBufferedMs, fixedAaudio.firstPacketToStartMs);

        assertTrue(taggedAudioTrack.starvationIntervals > 0);
        assertTrue(taggedAudioTrack.postStartSilenceMs > 0);
        assertEquals(0, fixedAaudio.starvationIntervals);
        assertEquals(0, fixedAaudio.postStartSilenceMs, 0.001);
        assertTrue(fixedAaudio.averageBufferedMs > taggedAudioTrack.averageBufferedMs);
        assertTrue(fixedAaudio.firstPacketToStartMs >= 30);
        assertTrue(fixedAaudio.firstPacketToStartMs <= 40);
    }

    @Test
    public void noRebufferingAvoidsASecondFortyMsWaitAfterUnderrun() {
        List<Double> arrivals = createControlledSixtyMsDeliveryStall();

        Result taggedAudioTrack = simulate(arrivals, PACKET_MS, false);
        Result fixedNoRebuffer = simulate(arrivals, 40, false);
        Result fixedWithRebuffer = simulate(arrivals, 40, true);

        System.out.printf("60 ms delivery stall: tagged=%.2f ms fixed-no-rebuffer=%.2f ms " +
                        "fixed-rebuffer=%.2f ms rebufferWait=%.2f ms%n",
                taggedAudioTrack.postStartSilenceMs, fixedNoRebuffer.postStartSilenceMs,
                fixedWithRebuffer.postStartSilenceMs, fixedWithRebuffer.rebufferSilenceMs);

        // The 40 ms reserve covers 35 ms more of the 65 ms inter-arrival interval than
        // the tag's single 5 ms packet. Continuing playback then avoids another 35 ms wait.
        assertEquals(60, taggedAudioTrack.postStartSilenceMs, 0.001);
        assertEquals(25, fixedNoRebuffer.postStartSilenceMs, 0.001);
        assertEquals(60, fixedWithRebuffer.postStartSilenceMs, 0.001);
        assertEquals(35, fixedWithRebuffer.rebufferSilenceMs, 0.001);
        assertEquals(1, fixedNoRebuffer.startCount);
        assertEquals(2, fixedWithRebuffer.startCount);
    }

    private static List<Double> createThirtyToSeventyMsRttTrace() {
        Random random = new Random(0xA71E51L);
        List<Double> arrivals = new ArrayList<>();
        double oneWayDelayMs = 25;

        for (int sentMs = 0; sentMs < DURATION_MS; sentMs += PACKET_MS) {
            oneWayDelayMs += (random.nextDouble() - 0.5) * 3.0;
            oneWayDelayMs = Math.max(15, Math.min(35, oneWayDelayMs));
            double schedulerJitterMs = random.nextDouble() < 0.01 ? 6 : 0;
            arrivals.add(sentMs + oneWayDelayMs + schedulerJitterMs);
        }

        arrivals.sort(Comparator.naturalOrder());
        return arrivals;
    }

    private static List<Double> createControlledSixtyMsDeliveryStall() {
        List<Double> arrivals = new ArrayList<>();
        for (int sentMs = 0; sentMs < 20_000; sentMs += PACKET_MS) {
            arrivals.add(sentMs + 25.0 + (sentMs >= 10_000 ? 60.0 : 0.0));
        }
        return arrivals;
    }

    private static Result simulate(List<Double> arrivals, int initialTargetMs,
                                   boolean rebufferAfterUnderrun) {
        double queueMs = 0;
        double lastArrivalMs = arrivals.get(0);
        double bufferedSum = 0;
        double postStartSilenceMs = 0;
        double rebufferSilenceMs = 0;
        double firstPacketToStartMs = Double.NaN;
        int measuredPackets = 0;
        int starvationIntervals = 0;
        int startCount = 0;
        boolean playing = false;
        boolean everStarted = false;

        for (double arrivalMs : arrivals) {
            double elapsedMs = arrivalMs - lastArrivalMs;
            if (playing) {
                queueMs -= elapsedMs;
                if (queueMs < 0) {
                    postStartSilenceMs += -queueMs;
                    queueMs = 0;
                    starvationIntervals++;
                    if (rebufferAfterUnderrun) {
                        playing = false;
                    }
                }
            }
            else if (everStarted) {
                postStartSilenceMs += elapsedMs;
                rebufferSilenceMs += elapsedMs;
            }

            queueMs += PACKET_MS;
            if (!playing && queueMs >= initialTargetMs) {
                playing = true;
                startCount++;
                if (!everStarted) {
                    everStarted = true;
                    firstPacketToStartMs = arrivalMs - arrivals.get(0);
                }
            }

            if (arrivalMs > 30_000) {
                bufferedSum += queueMs;
                measuredPackets++;
            }
            lastArrivalMs = arrivalMs;
        }

        return new Result(starvationIntervals, postStartSilenceMs, rebufferSilenceMs,
                bufferedSum / measuredPackets, firstPacketToStartMs, startCount);
    }

    private static final class Result {
        final int starvationIntervals;
        final double postStartSilenceMs;
        final double rebufferSilenceMs;
        final double averageBufferedMs;
        final double firstPacketToStartMs;
        final int startCount;

        Result(int starvationIntervals, double postStartSilenceMs, double rebufferSilenceMs,
               double averageBufferedMs, double firstPacketToStartMs, int startCount) {
            this.starvationIntervals = starvationIntervals;
            this.postStartSilenceMs = postStartSilenceMs;
            this.rebufferSilenceMs = rebufferSilenceMs;
            this.averageBufferedMs = averageBufferedMs;
            this.firstPacketToStartMs = firstPacketToStartMs;
            this.startCount = startCount;
        }
    }
}
