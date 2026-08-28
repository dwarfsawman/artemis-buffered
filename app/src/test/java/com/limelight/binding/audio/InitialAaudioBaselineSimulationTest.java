package com.limelight.binding.audio;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

    @Test
    public void startupWarmupMovesInitialCallbackStallBeforeAudiblePlayback() {
        StartupResult delayedStart = simulateStartupCallbackStall(false);
        StartupResult warmStart = simulateStartupCallbackStall(true);

        // The original policy emits useful PCM from the first callback and then encounters the
        // device's startup pause, overflowing the 140 ms steady-state ring during the cutout.
        assertTrue(delayedStart.audibleBeforeCallbackStall);
        assertTrue(delayedStart.droppedAudioMs > 1000);

        // Starting AAudio when armed lets the same pause finish while callbacks still emit
        // startup silence. A 2-second startup ring retains the input, then trims to the newest
        // 40 ms so useful playback begins current and without a preceding audible fragment.
        assertFalse(warmStart.audibleBeforeCallbackStall);
        assertEquals(0, warmStart.droppedAudioMs);
        assertTrue(warmStart.discardedStartupAudioMs > 800);
        assertEquals(40, warmStart.queuedAtFirstAudibleCallbackMs);
    }

    private static StartupResult simulateStartupCallbackStall(boolean warmStart) {
        final int packetStartMs = 500;
        final int targetMs = 40;
        final int steadyCapacityMs = 140;
        final int startupCapacityMs = 2000;
        final int callbackPeriodMs = 4;
        final int streamStartMs = warmStart ? 0 : packetStartMs + targetMs - PACKET_MS;
        final int firstCallbackMs = streamStartMs + 40;
        final int callbackStallStartMs = firstCallbackMs + callbackPeriodMs;
        final int callbackStallEndMs = callbackStallStartMs + 1400;

        int queuedMs = warmStart ? 0 : targetMs;
        int droppedAudioMs = 0;
        int discardedStartupAudioMs = 0;
        int queuedAtFirstAudibleCallbackMs = -1;
        int firstAudibleCallbackMs = -1;
        boolean primed = !warmStart;

        for (int nowMs = 0; nowMs <= 2400; nowMs++) {
            if (nowMs >= packetStartMs &&
                    (nowMs - packetStartMs) % PACKET_MS == 0 &&
                    (warmStart || nowMs > streamStartMs)) {
                int capacityMs = primed ? steadyCapacityMs : startupCapacityMs;
                int acceptedMs = Math.min(PACKET_MS, capacityMs - queuedMs);
                queuedMs += acceptedMs;
                droppedAudioMs += PACKET_MS - acceptedMs;
            }

            boolean callbackDue = nowMs == firstCallbackMs ||
                    (nowMs >= callbackStallEndMs &&
                            (nowMs - callbackStallEndMs) % callbackPeriodMs == 0);
            if (!callbackDue) {
                continue;
            }

            if (!primed) {
                if (queuedMs < targetMs) {
                    continue;
                }
                discardedStartupAudioMs += queuedMs - targetMs;
                queuedMs = targetMs;
                primed = true;
            }

            if (queuedMs > 0) {
                if (firstAudibleCallbackMs < 0) {
                    firstAudibleCallbackMs = nowMs;
                    queuedAtFirstAudibleCallbackMs = queuedMs;
                }
                queuedMs = Math.max(0, queuedMs - callbackPeriodMs);
            }
        }

        return new StartupResult(
                firstAudibleCallbackMs >= 0 && firstAudibleCallbackMs < callbackStallStartMs,
                droppedAudioMs,
                discardedStartupAudioMs,
                queuedAtFirstAudibleCallbackMs);
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

    private static final class StartupResult {
        final boolean audibleBeforeCallbackStall;
        final int droppedAudioMs;
        final int discardedStartupAudioMs;
        final int queuedAtFirstAudibleCallbackMs;

        StartupResult(boolean audibleBeforeCallbackStall, int droppedAudioMs,
                      int discardedStartupAudioMs, int queuedAtFirstAudibleCallbackMs) {
            this.audibleBeforeCallbackStall = audibleBeforeCallbackStall;
            this.droppedAudioMs = droppedAudioMs;
            this.discardedStartupAudioMs = discardedStartupAudioMs;
            this.queuedAtFirstAudibleCallbackMs = queuedAtFirstAudibleCallbackMs;
        }
    }
}
