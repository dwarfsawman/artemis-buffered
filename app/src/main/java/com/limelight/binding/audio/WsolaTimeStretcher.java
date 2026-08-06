package com.limelight.binding.audio;

/**
 * Pure-Java reference model for the native WSOLA-style PCM stretcher.
 *
 * <p>Only a one to three percent correction is accepted. A correlation-selected splice and
 * overlap/add crossfade remove or repeat a few frames while preserving channel alignment and
 * avoiding a hard discontinuity.</p>
 */
final class WsolaTimeStretcher {
    static final double MIN_RATE = 0.97;
    static final double MAX_RATE = 1.03;

    private final int sampleRate;
    private final int channelCount;

    WsolaTimeStretcher(int sampleRate, int channelCount) {
        if (sampleRate <= 0 || channelCount <= 0) {
            throw new IllegalArgumentException("Invalid audio format");
        }
        this.sampleRate = sampleRate;
        this.channelCount = channelCount;
    }

    short[] process(short[] input, double playbackRate) {
        if (input.length == 0 || input.length % channelCount != 0) {
            return input;
        }

        playbackRate = Math.max(MIN_RATE, Math.min(MAX_RATE, playbackRate));
        int inputFrames = input.length / channelCount;
        int outputFrames = (int) Math.round(inputFrames / playbackRate);
        int deltaFrames = outputFrames - inputFrames;
        if (deltaFrames == 0) {
            return input;
        }

        int overlapFrames = Math.min(inputFrames / 4, Math.max(8, sampleRate / 1_000));
        int adjustmentFrames = Math.abs(deltaFrames);
        if (overlapFrames < 2 || adjustmentFrames + overlapFrames >= inputFrames) {
            return input;
        }

        boolean expanding = deltaFrames > 0;
        int spliceFrame = findBestSplice(input, inputFrames, overlapFrames,
                adjustmentFrames, expanding);
        return overlapAdd(input, inputFrames, overlapFrames, adjustmentFrames,
                spliceFrame, expanding);
    }

    private int findBestSplice(short[] input, int inputFrames, int overlapFrames,
                               int adjustmentFrames, boolean expanding) {
        int minimumSplice = expanding ? overlapFrames + adjustmentFrames : overlapFrames;
        int maximumSplice = expanding ? inputFrames : inputFrames - adjustmentFrames;
        int center = (minimumSplice + maximumSplice) / 2;
        int searchRadius = Math.min(sampleRate / 1_000,
                Math.max(1, (maximumSplice - minimumSplice) / 3));
        int searchStart = Math.max(minimumSplice, center - searchRadius);
        int searchEnd = Math.min(maximumSplice, center + searchRadius);

        int bestSplice = center;
        double bestScore = -Double.MAX_VALUE;
        for (int splice = searchStart; splice <= searchEnd; splice++) {
            int firstStart = splice - overlapFrames;
            int secondStart = expanding ?
                    splice - adjustmentFrames - overlapFrames :
                    splice + adjustmentFrames - overlapFrames;
            double score = normalizedCorrelation(input, firstStart, secondStart, overlapFrames);
            if (score > bestScore) {
                bestScore = score;
                bestSplice = splice;
            }
        }
        return bestSplice;
    }

    private double normalizedCorrelation(short[] input, int firstFrame, int secondFrame,
                                         int frameCount) {
        double cross = 0;
        double firstEnergy = 1;
        double secondEnergy = 1;
        for (int frame = 0; frame < frameCount; frame++) {
            int firstOffset = (firstFrame + frame) * channelCount;
            int secondOffset = (secondFrame + frame) * channelCount;
            for (int channel = 0; channel < channelCount; channel++) {
                double first = input[firstOffset + channel];
                double second = input[secondOffset + channel];
                cross += first * second;
                firstEnergy += first * first;
                secondEnergy += second * second;
            }
        }
        return cross / Math.sqrt(firstEnergy * secondEnergy);
    }

    private short[] overlapAdd(short[] input, int inputFrames, int overlapFrames,
                               int adjustmentFrames, int spliceFrame, boolean expanding) {
        int outputFrames = expanding ? inputFrames + adjustmentFrames :
                inputFrames - adjustmentFrames;
        short[] output = new short[outputFrames * channelCount];

        int prefixFrames = spliceFrame - overlapFrames;
        System.arraycopy(input, 0, output, 0, prefixFrames * channelCount);

        int secondStartFrame = expanding ?
                spliceFrame - adjustmentFrames - overlapFrames :
                spliceFrame + adjustmentFrames - overlapFrames;
        for (int frame = 0; frame < overlapFrames; frame++) {
            int firstOffset = (spliceFrame - overlapFrames + frame) * channelCount;
            int secondOffset = (secondStartFrame + frame) * channelCount;
            int outputOffset = (prefixFrames + frame) * channelCount;
            int firstWeight = overlapFrames - 1 - frame;
            int secondWeight = frame;
            int divisor = overlapFrames - 1;
            for (int channel = 0; channel < channelCount; channel++) {
                int mixed = (input[firstOffset + channel] * firstWeight +
                        input[secondOffset + channel] * secondWeight) / divisor;
                output[outputOffset + channel] = (short) mixed;
            }
        }

        int suffixInputFrame = expanding ? spliceFrame - adjustmentFrames :
                spliceFrame + adjustmentFrames;
        int suffixOutputFrame = spliceFrame;
        int suffixFrames = inputFrames - suffixInputFrame;
        System.arraycopy(input, suffixInputFrame * channelCount,
                output, suffixOutputFrame * channelCount,
                suffixFrames * channelCount);
        return output;
    }
}
