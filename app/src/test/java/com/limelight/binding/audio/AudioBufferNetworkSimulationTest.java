package com.limelight.binding.audio;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertTrue;

public class AudioBufferNetworkSimulationTest {
    private static final int PACKET_MS = 5;
    private static final int DURATION_MS = 120_000;

    @Test
    public void adaptiveModeCutsBufferDelayWithoutMoreUnderrunsAtThirtyToSeventyMsRtt() {
        List<Double> arrivals = createThirtyToSeventyMsRttTrace();

        Result fixed = simulate(arrivals, false);
        Result adaptive = simulate(arrivals, true);

        System.out.printf("fixed40: underruns=%d averageBuffer=%.2f ms%n",
                fixed.underruns, fixed.averageBufferedMs);
        System.out.printf("adaptive+wsola: underruns=%d averageBuffer=%.2f ms " +
                        "averageTarget=%.2f ms adjustedAudio=%.2f ms%n",
                adaptive.underruns, adaptive.averageBufferedMs,
                adaptive.averageTargetMs, adaptive.adjustedAudioMs);

        assertTrue("adaptive mode added underruns", adaptive.underruns <= fixed.underruns);
        assertTrue("adaptive target did not reduce buffered latency",
                adaptive.averageBufferedMs + 5 < fixed.averageBufferedMs);
        assertTrue(adaptive.minimumRate >= WsolaTimeStretcher.MIN_RATE);
        assertTrue(adaptive.maximumRate <= WsolaTimeStretcher.MAX_RATE);
    }

    private static List<Double> createThirtyToSeventyMsRttTrace() {
        Random random = new Random(0xA71E51L);
        List<Double> arrivals = new ArrayList<>();
        double oneWayDelayMs = 25;

        for (int sentMs = 0; sentMs < DURATION_MS; sentMs += PACKET_MS) {
            // Bounded random walk from 15-35 ms one-way, equivalent to 30-70 ms RTT.
            oneWayDelayMs += (random.nextDouble() - 0.5) * 3.0;
            oneWayDelayMs = Math.max(15, Math.min(35, oneWayDelayMs));
            double schedulerJitterMs = random.nextDouble() < 0.01 ? 6 : 0;
            arrivals.add(sentMs + oneWayDelayMs + schedulerJitterMs);
        }

        arrivals.sort(Comparator.naturalOrder());
        return arrivals;
    }

    private static Result simulate(List<Double> arrivals, boolean adaptive) {
        AdaptiveAudioBufferController controller =
                new AdaptiveAudioBufferController(adaptive, 80);
        double queueMs = 0;
        double lastArrivalMs = arrivals.get(0);
        boolean playing = false;
        int underruns = 0;
        double bufferedSum = 0;
        double targetSum = 0;
        double adjustedAudioMs = 0;
        double minimumRate = 1;
        double maximumRate = 1;
        int measuredPackets = 0;

        for (double arrivalMs : arrivals) {
            double elapsedMs = arrivalMs - lastArrivalMs;
            if (playing) {
                queueMs -= elapsedMs;
                if (queueMs < 0) {
                    underruns++;
                    queueMs = 0;
                    // The AAudio callback keeps running and emits silence for missing frames.
                    // It deliberately does not wait for the target to fill again.
                    controller.onUnderrun(Math.round(arrivalMs));
                }
            }

            controller.onPacketArrival(Math.round(arrivalMs), PACKET_MS);
            double rate = playing ? controller.getPlaybackRate((int) Math.round(queueMs)) : 1.0;
            double outputMs = PACKET_MS / rate;
            queueMs += outputMs;
            adjustedAudioMs += outputMs - PACKET_MS;
            minimumRate = Math.min(minimumRate, rate);
            maximumRate = Math.max(maximumRate, rate);

            if (!playing && queueMs >= controller.getTargetMs()) {
                playing = true;
            }

            // Exclude startup convergence from steady-state latency figures.
            if (arrivalMs > 30_000) {
                bufferedSum += queueMs;
                targetSum += controller.getTargetMs();
                measuredPackets++;
            }
            lastArrivalMs = arrivalMs;
        }

        return new Result(underruns, bufferedSum / measuredPackets,
                targetSum / measuredPackets, adjustedAudioMs, minimumRate, maximumRate);
    }

    private static final class Result {
        final int underruns;
        final double averageBufferedMs;
        final double averageTargetMs;
        final double adjustedAudioMs;
        final double minimumRate;
        final double maximumRate;

        Result(int underruns, double averageBufferedMs, double averageTargetMs,
               double adjustedAudioMs, double minimumRate, double maximumRate) {
            this.underruns = underruns;
            this.averageBufferedMs = averageBufferedMs;
            this.averageTargetMs = averageTargetMs;
            this.adjustedAudioMs = adjustedAudioMs;
            this.minimumRate = minimumRate;
            this.maximumRate = maximumRate;
        }
    }
}
