package com.limelight.binding.audio;

/**
 * Pure-Java reference model for the native receive-side adaptive controller.
 *
 * <p>The target rises quickly when decoded packet delivery becomes irregular and falls by at
 * most one millisecond per second. This avoids target oscillation while still reacting before
 * the AudioTrack queue is exhausted.</p>
 */
final class AdaptiveAudioBufferController {
    static final int FIXED_TARGET_MS = 40;
    static final int MIN_TARGET_MS = 20;
    static final int DEFAULT_INITIAL_TARGET_MS = 40;
    static final int ABSOLUTE_MAX_TARGET_MS = 80;

    private static final int TARGET_DECREASE_INTERVAL_MS = 1_000;
    private static final int UNDERRUN_PROTECTION_MS = 5_000;
    private static final double JITTER_MULTIPLIER = 4.0;

    private final boolean adaptive;
    private final int maxTargetMs;

    private int targetMs;
    private double jitterMs;
    private long lastArrivalMs = -1;
    private long lastTargetDecreaseMs = -1;
    private long protectionUntilMs = -1;
    private int previousPacketDurationMs;

    AdaptiveAudioBufferController(boolean adaptive, int configuredMaxTargetMs) {
        this.adaptive = adaptive;
        this.maxTargetMs = adaptive ?
                clamp(configuredMaxTargetMs, MIN_TARGET_MS, ABSOLUTE_MAX_TARGET_MS) :
                FIXED_TARGET_MS;
        this.targetMs = adaptive ? Math.min(DEFAULT_INITIAL_TARGET_MS, maxTargetMs) :
                FIXED_TARGET_MS;
    }

    void onPacketArrival(long nowMs, int packetDurationMs) {
        packetDurationMs = Math.max(1, packetDurationMs);
        if (!adaptive) {
            previousPacketDurationMs = packetDurationMs;
            lastArrivalMs = nowMs;
            return;
        }

        if (lastArrivalMs >= 0) {
            long intervalMs = Math.max(0, nowMs - lastArrivalMs);
            int expectedIntervalMs = Math.max(1, previousPacketDurationMs);
            double deviationMs = Math.min(ABSOLUTE_MAX_TARGET_MS,
                    Math.abs(intervalMs - expectedIntervalMs));

            // RFC 3550-style inter-arrival jitter smoothing. The input here is the decoded
            // packet cadence rather than RTP timestamps, but the same 1/16 filter avoids
            // chasing individual scheduler spikes.
            jitterMs += (deviationMs - jitterMs) / 16.0;

            int desiredTargetMs = clamp(
                    MIN_TARGET_MS + (int) Math.ceil(jitterMs * JITTER_MULTIPLIER),
                    MIN_TARGET_MS,
                    maxTargetMs);

            // A single gap large enough to threaten the current queue is handled immediately.
            if (intervalMs > expectedIntervalMs + targetMs) {
                desiredTargetMs = maxTargetMs;
                protectionUntilMs = Math.max(protectionUntilMs, nowMs + UNDERRUN_PROTECTION_MS);
            }

            updateTarget(nowMs, desiredTargetMs);
        }
        else {
            lastTargetDecreaseMs = nowMs;
        }

        previousPacketDurationMs = packetDurationMs;
        lastArrivalMs = nowMs;
    }

    void onUnderrun(long nowMs) {
        if (!adaptive) {
            return;
        }

        targetMs = maxTargetMs;
        protectionUntilMs = nowMs + UNDERRUN_PROTECTION_MS;
        lastTargetDecreaseMs = nowMs;
    }

    double getPlaybackRate(int bufferedMs) {
        if (!adaptive) {
            return 1.0;
        }

        int errorMs = bufferedMs - targetMs;
        int magnitudeMs = Math.abs(errorMs);
        if (magnitudeMs <= 4) {
            return 1.0;
        }

        // Start at one percent outside the dead band and reach three percent at 24 ms.
        double adjustment = Math.min(0.03, 0.01 + (magnitudeMs - 4) * 0.001);
        return errorMs > 0 ? 1.0 + adjustment : 1.0 - adjustment;
    }

    int getTargetMs() {
        return targetMs;
    }

    int getPendingLimitMs() {
        return adaptive ? maxTargetMs : FIXED_TARGET_MS;
    }

    double getJitterMs() {
        return jitterMs;
    }

    boolean isAdaptive() {
        return adaptive;
    }

    private void updateTarget(long nowMs, int desiredTargetMs) {
        if (nowMs < protectionUntilMs) {
            desiredTargetMs = Math.max(desiredTargetMs, targetMs);
        }

        if (desiredTargetMs >= targetMs) {
            targetMs = desiredTargetMs;
            lastTargetDecreaseMs = nowMs;
            return;
        }

        if (lastTargetDecreaseMs < 0) {
            lastTargetDecreaseMs = nowMs;
            return;
        }

        long decreaseSteps = (nowMs - lastTargetDecreaseMs) / TARGET_DECREASE_INTERVAL_MS;
        if (decreaseSteps > 0) {
            targetMs = Math.max(desiredTargetMs,
                    targetMs - (int) Math.min(Integer.MAX_VALUE, decreaseSteps));
            lastTargetDecreaseMs += decreaseSteps * TARGET_DECREASE_INTERVAL_MS;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
