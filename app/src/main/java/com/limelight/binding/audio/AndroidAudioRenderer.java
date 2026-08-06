package com.limelight.binding.audio;

import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.audiofx.AudioEffect;
import android.os.Build;
import android.os.SystemClock;

import com.limelight.LimeLog;
import com.limelight.nvstream.av.audio.AudioRenderer;
import com.limelight.nvstream.jni.MoonBridge;

public class AndroidAudioRenderer implements AudioRenderer {
    private static final long STATS_LOG_INTERVAL_MS = 2000;
    private static final int NATIVE_STATS_COUNT = 20;
    private static final int NATIVE_DIAGNOSTIC_EVENT_WORDS = 9;
    private static final int NATIVE_DIAGNOSTIC_EVENT_CAPACITY = 128;

    private static final int NATIVE_EVENT_STREAM_OPENED = 1;
    private static final int NATIVE_EVENT_ARMED = 2;
    private static final int NATIVE_EVENT_START_REQUESTED = 3;
    private static final int NATIVE_EVENT_FIRST_CALLBACK = 4;
    private static final int NATIVE_EVENT_UNDERRUN = 5;
    private static final int NATIVE_EVENT_RING_OVERFLOW = 6;
    private static final int NATIVE_EVENT_START_FAILED = 7;
    private static final int NATIVE_EVENT_STREAM_ERROR = 8;

    static {
        System.loadLibrary("moonlight-core");
    }

    private final Context context;
    private final boolean enableAudioFx;
    private final boolean adaptiveAudioBuffer;
    private final AudioDiagnosticsLogger diagnostics;
    private final long[] nativeStats = new long[NATIVE_STATS_COUNT];
    private final long[] nativeDiagnosticEvents =
            new long[NATIVE_DIAGNOSTIC_EVENT_WORDS * NATIVE_DIAGNOSTIC_EVENT_CAPACITY];

    private AudioTrack track;
    private long nativeRenderer;
    private int sampleRate;
    private int channelCount;
    private boolean audioFxSessionOpened;
    private boolean closing;
    private long nextStatsLogTimeMs;
    private long lastDeliveryTimeMs;
    private long deliveryGapEvents;
    private long deliveryIntervalsSinceStats;
    private long maximumDeliveryGapMsSinceStats;
    private long deliveryIntervalsOver10MsSinceStats;
    private long deliveryIntervalsOver20MsSinceStats;
    private long deliveryIntervalsOver40MsSinceStats;
    private long fallbackDroppedPackets;

    public AndroidAudioRenderer(Context context, boolean enableAudioFx,
                                boolean enableAudioDiagnostics, boolean adaptiveAudioBuffer) {
        this.context = context;
        this.enableAudioFx = enableAudioFx;
        this.adaptiveAudioBuffer = adaptiveAudioBuffer;
        this.diagnostics = enableAudioDiagnostics ? AudioDiagnosticsLogger.start(context) : null;
    }

    /** Shares this renderer's session logger with the video/network diagnostics producer. */
    public AudioDiagnosticsLogger getDiagnosticsLogger() {
        return diagnostics;
    }

    private static native long nativeCreate(int sampleRate, int channelCount, int samplesPerFrame,
                                            boolean adaptive, boolean diagnosticsEnabled);
    private static native void nativeArm(long handle);
    private static native int nativeWrite(long handle, short[] audioData);
    private static native void nativeGetStats(long handle, long[] stats);
    private static native int nativeDrainDiagnosticEvents(long handle, long[] events);
    private static native void nativeDestroy(long handle);

    private AudioTrack createAudioTrack(int channelConfig, int sampleRate,
                                        int bufferSize, boolean lowLatency) {
        AudioAttributes.Builder attributesBuilder = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME);
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .build();

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O && lowLatency) {
            attributesBuilder.setFlags(AudioAttributes.FLAG_LOW_LATENCY);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioTrack.Builder trackBuilder = new AudioTrack.Builder()
                    .setAudioFormat(format)
                    .setAudioAttributes(attributesBuilder.build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(bufferSize);

            if (lowLatency) {
                trackBuilder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
            }

            return trackBuilder.build();
        }

        return new AudioTrack(attributesBuilder.build(),
                format,
                bufferSize,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE);
    }

    @Override
    public synchronized int setup(MoonBridge.AudioConfiguration audioConfiguration,
                                  int sampleRate, int samplesPerFrame) {
        this.sampleRate = sampleRate;
        this.channelCount = audioConfiguration.channelCount;
        this.closing = false;
        this.nextStatsLogTimeMs = 0;
        this.lastDeliveryTimeMs = 0;
        this.deliveryGapEvents = 0;
        this.fallbackDroppedPackets = 0;
        resetDeliveryWindowStats();
        recordDiagnostic("audio_setup",
                "sampleRate", sampleRate,
                "channelCount", channelCount,
                "samplesPerFrame", samplesPerFrame,
                "audioFxEnabled", enableAudioFx,
                "selectedBufferMode", getSelectedBufferMode(),
                "aaudioEligible", Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && !enableAudioFx,
                "underrunRebuffering", false);

        // AAudio uses a high-priority callback to consume a separate PCM jitter ring.
        // Audio effects require an AudioTrack session, so preserve the legacy path for that case.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && !enableAudioFx) {
            nativeRenderer = nativeCreate(sampleRate, channelCount, samplesPerFrame,
                    adaptiveAudioBuffer,
                    diagnostics != null);
            if (nativeRenderer != 0) {
                int minimumTargetMs = adaptiveAudioBuffer ? 20 : 40;
                int maximumTargetMs = adaptiveAudioBuffer ? 80 : 40;
                LimeLog.info(adaptiveAudioBuffer ?
                        "Using adaptive callback AAudio renderer: 20-80 ms target, " +
                                "initial=40 ms, WSOLA rate=0.97-1.03, no underrun rebuffering" :
                        "Using fixed callback AAudio renderer: 40 ms SPSC ring, " +
                                "no WSOLA, no underrun rebuffering");
                recordDiagnostic("renderer_started",
                        "renderer", "AAudio",
                        "bufferMode", getSelectedBufferMode(),
                        "adaptive", adaptiveAudioBuffer,
                        "wsolaEnabled", adaptiveAudioBuffer,
                        "underrunRebuffering", false,
                        "initialTargetMs", 40,
                        "minimumTargetMs", minimumTargetMs,
                        "maximumTargetMs", maximumTargetMs);
                return 0;
            }
            LimeLog.warning("AAudio renderer unavailable; falling back to low-latency AudioTrack");
            recordDiagnostic("aaudio_unavailable",
                    "selectedBufferMode", getSelectedBufferMode());
        }

        return setupAudioTrack(audioConfiguration, sampleRate, samplesPerFrame);
    }

    private int setupAudioTrack(MoonBridge.AudioConfiguration audioConfiguration,
                                int sampleRate, int samplesPerFrame) {
        int channelConfig;
        switch (audioConfiguration.channelCount) {
            case 2:
                channelConfig = AudioFormat.CHANNEL_OUT_STEREO;
                break;
            case 4:
                channelConfig = AudioFormat.CHANNEL_OUT_QUAD;
                break;
            case 6:
                channelConfig = AudioFormat.CHANNEL_OUT_5POINT1;
                break;
            case 8:
                channelConfig = 0x000018fc; // AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
                break;
            default:
                LimeLog.severe("Decoder returned unhandled channel count");
                return -1;
        }

        LimeLog.info("Audio channel config: " + String.format("0x%X", channelConfig));
        int bytesPerFrame = audioConfiguration.channelCount * samplesPerFrame * 2;

        for (int i = 0; i < 4; i++) {
            boolean lowLatency = i < 2;
            int bufferSize;
            if (i == 0 || i == 2) {
                bufferSize = bytesPerFrame * 2;
            }
            else {
                bufferSize = Math.max(AudioTrack.getMinBufferSize(sampleRate,
                                channelConfig,
                                AudioFormat.ENCODING_PCM_16BIT),
                        bytesPerFrame * 2);
                bufferSize = ((bufferSize + bytesPerFrame - 1) / bytesPerFrame) * bytesPerFrame;
            }

            if (AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC) != sampleRate &&
                    lowLatency) {
                continue;
            }

            if (enableAudioFx && lowLatency) {
                continue;
            }

            try {
                track = createAudioTrack(channelConfig, sampleRate, bufferSize, lowLatency);
                LimeLog.info("AudioTrack fallback configuration: " + bufferSize +
                        " bytes, lowLatency=" + lowLatency);
                recordDiagnostic("renderer_started",
                        "renderer", "AudioTrack",
                        "bufferMode", "audio_track_fallback",
                        "selectedBufferMode", getSelectedBufferMode(),
                        "bufferBytes", bufferSize,
                        "lowLatency", lowLatency,
                        "channelConfig", String.format("0x%X", channelConfig));
                return 0;
            }
            catch (Exception e) {
                e.printStackTrace();
                try {
                    if (track != null) {
                        track.release();
                        track = null;
                    }
                }
                catch (Exception ignored) {
                }
            }
        }

        return -2;
    }

    private String getSelectedBufferMode() {
        return adaptiveAudioBuffer ? "adaptive_20_80ms_wsola" : "fixed_40ms";
    }

    @Override
    public synchronized void playDecodedAudio(short[] audioData) {
        if (closing) {
            return;
        }

        recordDeliveryTiming(audioData.length);

        if (nativeRenderer != 0) {
            int acceptedFrames = nativeWrite(nativeRenderer, audioData);
            if (acceptedFrames < 0) {
                LimeLog.warning("AAudio ring write failed with code " + acceptedFrames);
                recordDiagnostic("write_error",
                        "renderer", "AAudio",
                        "code", acceptedFrames,
                        "requestedFrames", channelCount == 0 ? 0 : audioData.length / channelCount);
            }
            maybeLogNativeStats();
            return;
        }

        if (track == null) {
            return;
        }

        // The fallback write is blocking, so keep the original 40 ms decoder-queue limit.
        if (MoonBridge.getPendingAudioDuration() < 40) {
            int offset = 0;
            while (offset < audioData.length && !closing) {
                int written = track.write(audioData, offset, audioData.length - offset);
                if (written <= 0) {
                    LimeLog.warning("AudioTrack write failed with code " + written);
                    recordDiagnostic("write_error",
                            "renderer", "AudioTrack",
                            "code", written,
                            "remainingSamples", audioData.length - offset);
                    break;
                }
                offset += written;
            }
        }
        else {
            fallbackDroppedPackets++;
            LimeLog.warning("Too much pending fallback audio data: " +
                    MoonBridge.getPendingAudioDuration() + " ms");
        }
        maybeLogAudioTrackStats();
    }

    private void maybeLogNativeStats() {
        long now = SystemClock.elapsedRealtime();
        if (now < nextStatsLogTimeMs || nativeRenderer == 0) {
            return;
        }

        nextStatsLogTimeMs = now + STATS_LOG_INTERVAL_MS;
        nativeGetStats(nativeRenderer, nativeStats);
        drainNativeDiagnosticEvents();
        long queuedMs = sampleRate == 0 ? 0 : nativeStats[0] * 1000 / sampleRate;
        long underrunMs = sampleRate == 0 ? 0 : nativeStats[2] * 1000 / sampleRate;
        long droppedMs = sampleRate == 0 ? 0 : nativeStats[3] * 1000 / sampleRate;
        long targetMs = sampleRate == 0 ? 40 : nativeStats[6] * 1000 / sampleRate;
        long ringCapacityMs = sampleRate == 0 ? 0 : nativeStats[14] * 1000 / sampleRate;
        double playbackRate = nativeStats[7] / 1_000_000.0;
        double jitterMs = nativeStats[8] / 1_000.0;
        double maximumCallbackGapMs = nativeStats[13] / 1_000.0;
        recordDiagnostic("aaudio_stats",
                "queuedMs", queuedMs,
                "targetMs", targetMs,
                "playbackRate", playbackRate,
                "jitterMs", jitterMs,
                "underrunCallbacks", nativeStats[1],
                "underrunMs", underrunMs,
                "droppedMs", droppedMs,
                "stretchDeltaFrames", nativeStats[9],
                "xrunCount", nativeStats[4],
                "lastError", nativeStats[5],
                "started", nativeStats[10] != 0,
                "callbackCount", nativeStats[11],
                "diagnosticEventsDropped", nativeStats[12],
                "maxCallbackGapMs", maximumCallbackGapMs,
                "ringCapacityMs", ringCapacityMs,
                "framesPerBurst", nativeStats[15],
                "aaudioBufferFrames", nativeStats[16],
                "aaudioBufferCapacityFrames", nativeStats[17],
                "streamState", nativeStats[18],
                "armed", nativeStats[19] != 0,
                "deliveryGapEvents", deliveryGapEvents,
                "deliveryIntervals", deliveryIntervalsSinceStats,
                "maxDeliveryGapMs", maximumDeliveryGapMsSinceStats,
                "deliveryIntervalsOver10Ms", deliveryIntervalsOver10MsSinceStats,
                "deliveryIntervalsOver20Ms", deliveryIntervalsOver20MsSinceStats,
                "deliveryIntervalsOver40Ms", deliveryIntervalsOver40MsSinceStats);
        resetDeliveryWindowStats();
        LimeLog.info("AAudio jitter stats: queued=" + queuedMs +
                " ms, target=" + targetMs +
                " ms, rate=" + String.format("%.3f", playbackRate) +
                ", jitter=" + String.format("%.2f", jitterMs) +
                " ms, underrunCallbacks=" + nativeStats[1] +
                ", underrun=" + underrunMs +
                " ms, dropped=" + droppedMs +
                " ms, stretchDeltaFrames=" + nativeStats[9] +
                ", maxCallbackGap=" + String.format("%.2f", maximumCallbackGapMs) +
                " ms, streamState=" + nativeStats[18] +
                ", xrun=" + nativeStats[4] +
                ", error=" + nativeStats[5]);
    }

    private void drainNativeDiagnosticEvents() {
        if (diagnostics == null || nativeRenderer == 0) {
            return;
        }

        long underrunEventCount = 0;
        long underrunFirstElapsedNanos = 0;
        long underrunLastElapsedNanos = 0;
        long underrunTotalMissingFrames = 0;
        long underrunMaximumMissingFrames = 0;
        long underrunMinimumAvailableFrames = Long.MAX_VALUE;
        long underrunMaximumRequestedFrames = 0;
        long underrunMaximumCallbackGapUs = 0;
        long underrunLastTargetFrames = 0;
        long underrunTotalCallbacks = 0;
        long underrunStreamState = 0;

        long overflowEventCount = 0;
        long overflowFirstElapsedNanos = 0;
        long overflowLastElapsedNanos = 0;
        long overflowTotalRequestedFrames = 0;
        long overflowTotalAdjustedFrames = 0;
        long overflowTotalAcceptedFrames = 0;
        long overflowTotalDroppedFrames = 0;
        long overflowMaximumDroppedFrames = 0;
        long overflowMaximumQueuedFrames = 0;
        long overflowRingCapacityFrames = 0;
        boolean overflowStarted = false;

        int eventCount;
        do {
            eventCount = nativeDrainDiagnosticEvents(nativeRenderer, nativeDiagnosticEvents);
            for (int eventIndex = 0; eventIndex < eventCount; eventIndex++) {
                int offset = eventIndex * NATIVE_DIAGNOSTIC_EVENT_WORDS;
                int type = (int)nativeDiagnosticEvents[offset];
                long eventElapsedNanos = nativeDiagnosticEvents[offset + 1];
                int valueOffset = offset + 2;
                if (type == NATIVE_EVENT_UNDERRUN) {
                    long availableFrames = nativeDiagnosticEvents[valueOffset];
                    long requestedFrames = nativeDiagnosticEvents[valueOffset + 1];
                    long missingFrames = nativeDiagnosticEvents[valueOffset + 2];
                    underrunEventCount++;
                    if (underrunFirstElapsedNanos == 0) {
                        underrunFirstElapsedNanos = eventElapsedNanos;
                    }
                    underrunLastElapsedNanos = eventElapsedNanos;
                    underrunTotalMissingFrames += missingFrames;
                    underrunMaximumMissingFrames =
                            Math.max(underrunMaximumMissingFrames, missingFrames);
                    underrunMinimumAvailableFrames =
                            Math.min(underrunMinimumAvailableFrames, availableFrames);
                    underrunMaximumRequestedFrames =
                            Math.max(underrunMaximumRequestedFrames, requestedFrames);
                    underrunLastTargetFrames = nativeDiagnosticEvents[valueOffset + 3];
                    underrunMaximumCallbackGapUs = Math.max(
                            underrunMaximumCallbackGapUs,
                            nativeDiagnosticEvents[valueOffset + 4]);
                    underrunTotalCallbacks = nativeDiagnosticEvents[valueOffset + 5];
                    underrunStreamState = nativeDiagnosticEvents[valueOffset + 6];
                }
                else if (type == NATIVE_EVENT_RING_OVERFLOW) {
                    long droppedFrames = nativeDiagnosticEvents[valueOffset + 3];
                    overflowEventCount++;
                    if (overflowFirstElapsedNanos == 0) {
                        overflowFirstElapsedNanos = eventElapsedNanos;
                    }
                    overflowLastElapsedNanos = eventElapsedNanos;
                    overflowTotalRequestedFrames += nativeDiagnosticEvents[valueOffset];
                    overflowTotalAdjustedFrames += nativeDiagnosticEvents[valueOffset + 1];
                    overflowTotalAcceptedFrames += nativeDiagnosticEvents[valueOffset + 2];
                    overflowTotalDroppedFrames += droppedFrames;
                    overflowMaximumDroppedFrames =
                            Math.max(overflowMaximumDroppedFrames, droppedFrames);
                    overflowMaximumQueuedFrames = Math.max(
                            overflowMaximumQueuedFrames,
                            nativeDiagnosticEvents[valueOffset + 4]);
                    overflowRingCapacityFrames = nativeDiagnosticEvents[valueOffset + 5];
                    overflowStarted |= nativeDiagnosticEvents[valueOffset + 6] != 0;
                }
                else {
                    recordNativeDiagnosticEvent(type, eventElapsedNanos, valueOffset);
                }
            }
        } while (eventCount == NATIVE_DIAGNOSTIC_EVENT_CAPACITY);

        if (underrunEventCount > 0) {
            diagnostics.recordAtElapsedRealtimeNanos(
                    "aaudio_underrun_window", underrunLastElapsedNanos,
                    "callbackCount", underrunEventCount,
                    "totalMissingFrames", underrunTotalMissingFrames,
                    "maxMissingFrames", underrunMaximumMissingFrames,
                    "minAvailableFrames", underrunMinimumAvailableFrames,
                    "maxRequestedFrames", underrunMaximumRequestedFrames,
                    "maxCallbackGapUs", underrunMaximumCallbackGapUs,
                    "firstEventElapsedRealtimeNanos", underrunFirstElapsedNanos,
                    "lastEventElapsedRealtimeNanos", underrunLastElapsedNanos,
                    "lastTargetFrames", underrunLastTargetFrames,
                    "totalUnderrunCallbacks", underrunTotalCallbacks,
                    "streamState", underrunStreamState);
        }

        if (overflowEventCount > 0) {
            diagnostics.recordAtElapsedRealtimeNanos(
                    "aaudio_ring_overflow_window", overflowLastElapsedNanos,
                    "writeCount", overflowEventCount,
                    "totalRequestedFrames", overflowTotalRequestedFrames,
                    "totalAdjustedFrames", overflowTotalAdjustedFrames,
                    "totalAcceptedFrames", overflowTotalAcceptedFrames,
                    "totalDroppedFrames", overflowTotalDroppedFrames,
                    "maxDroppedFramesPerWrite", overflowMaximumDroppedFrames,
                    "maxQueuedFramesBeforeWrite", overflowMaximumQueuedFrames,
                    "ringCapacityFrames", overflowRingCapacityFrames,
                    "firstEventElapsedRealtimeNanos", overflowFirstElapsedNanos,
                    "lastEventElapsedRealtimeNanos", overflowLastElapsedNanos,
                    "started", overflowStarted);
        }
    }

    private void recordNativeDiagnosticEvent(int type, long elapsedRealtimeNanos,
                                             int valueOffset) {
        long value0 = nativeDiagnosticEvents[valueOffset];
        long value1 = nativeDiagnosticEvents[valueOffset + 1];
        long value2 = nativeDiagnosticEvents[valueOffset + 2];
        long value3 = nativeDiagnosticEvents[valueOffset + 3];
        long value4 = nativeDiagnosticEvents[valueOffset + 4];
        long value5 = nativeDiagnosticEvents[valueOffset + 5];
        long value6 = nativeDiagnosticEvents[valueOffset + 6];

        switch (type) {
            case NATIVE_EVENT_STREAM_OPENED:
                diagnostics.recordAtElapsedRealtimeNanos("aaudio_opened", elapsedRealtimeNanos,
                        "actualSampleRate", value0,
                        "actualChannelCount", value1,
                        "framesPerBurst", value2,
                        "aaudioBufferFrames", value3,
                        "aaudioBufferCapacityFrames", value4,
                        "performanceMode", value5,
                        "sharingMode", value6);
                break;
            case NATIVE_EVENT_ARMED:
                diagnostics.recordAtElapsedRealtimeNanos("aaudio_armed", elapsedRealtimeNanos,
                        "queuedFrames", value0,
                        "targetFrames", value1,
                        "streamState", value2);
                break;
            case NATIVE_EVENT_START_REQUESTED:
                diagnostics.recordAtElapsedRealtimeNanos(
                        "aaudio_start_requested", elapsedRealtimeNanos,
                        "queuedFrames", value0,
                        "targetFrames", value1,
                        "framesPerBurst", value2,
                        "aaudioBufferFrames", value3,
                        "streamState", value4);
                break;
            case NATIVE_EVENT_FIRST_CALLBACK:
                diagnostics.recordAtElapsedRealtimeNanos(
                        "aaudio_first_callback", elapsedRealtimeNanos,
                        "requestedFrames", value0,
                        "availableFrames", value1,
                        "startRequestDelayUs", value2,
                        "armDelayUs", value3,
                        "callbackNumber", value4,
                        "streamState", value5);
                break;
            case NATIVE_EVENT_START_FAILED:
                diagnostics.recordAtElapsedRealtimeNanos(
                        "aaudio_start_failed", elapsedRealtimeNanos,
                        "code", value0,
                        "queuedFrames", value1,
                        "targetFrames", value2,
                        "streamState", value3);
                break;
            case NATIVE_EVENT_STREAM_ERROR:
                diagnostics.recordAtElapsedRealtimeNanos("aaudio_stream_error",
                        elapsedRealtimeNanos,
                        "code", value0,
                        "streamState", value1);
                break;
            default:
                diagnostics.recordAtElapsedRealtimeNanos("aaudio_unknown_event",
                        elapsedRealtimeNanos,
                        "type", type);
                break;
        }
    }

    private void maybeLogAudioTrackStats() {
        if (diagnostics == null || track == null) {
            return;
        }

        long now = SystemClock.elapsedRealtime();
        if (now < nextStatsLogTimeMs) {
            return;
        }
        nextStatsLogTimeMs = now + STATS_LOG_INTERVAL_MS;

        int underrunCount = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N ?
                track.getUnderrunCount() : -1;
        recordDiagnostic("audiotrack_stats",
                "pendingDecoderAudioMs", MoonBridge.getPendingAudioDuration(),
                "underrunCount", underrunCount,
                "playState", track.getPlayState(),
                "fallbackDroppedPackets", fallbackDroppedPackets,
                "deliveryGapEvents", deliveryGapEvents,
                "deliveryIntervals", deliveryIntervalsSinceStats,
                "maxDeliveryGapMs", maximumDeliveryGapMsSinceStats,
                "deliveryIntervalsOver10Ms", deliveryIntervalsOver10MsSinceStats,
                "deliveryIntervalsOver20Ms", deliveryIntervalsOver20MsSinceStats,
                "deliveryIntervalsOver40Ms", deliveryIntervalsOver40MsSinceStats);
        resetDeliveryWindowStats();
    }

    private void recordDeliveryTiming(int sampleCount) {
        if (diagnostics == null || sampleRate == 0 || channelCount == 0) {
            return;
        }

        long now = SystemClock.elapsedRealtime();
        long expectedMs = Math.max(1, (long) sampleCount * 1000 / sampleRate / channelCount);
        long gapMs = lastDeliveryTimeMs == 0 ? 0 : now - lastDeliveryTimeMs;
        lastDeliveryTimeMs = now;

        if (gapMs > 0) {
            deliveryIntervalsSinceStats++;
            maximumDeliveryGapMsSinceStats = Math.max(maximumDeliveryGapMsSinceStats, gapMs);
            if (gapMs > 10) {
                deliveryIntervalsOver10MsSinceStats++;
            }
            if (gapMs > 20) {
                deliveryIntervalsOver20MsSinceStats++;
            }
            if (gapMs > 40) {
                deliveryIntervalsOver40MsSinceStats++;
            }
        }

        long thresholdMs = Math.max(50, expectedMs * 3);
        if (gapMs <= thresholdMs) {
            return;
        }

        deliveryGapEvents++;
        recordDiagnostic("delivery_gap",
                "gapMs", gapMs,
                "expectedPacketDurationMs", expectedMs,
                "thresholdMs", thresholdMs,
                "totalDeliveryGapEvents", deliveryGapEvents);
    }

    private void resetDeliveryWindowStats() {
        deliveryIntervalsSinceStats = 0;
        maximumDeliveryGapMsSinceStats = 0;
        deliveryIntervalsOver10MsSinceStats = 0;
        deliveryIntervalsOver20MsSinceStats = 0;
        deliveryIntervalsOver40MsSinceStats = 0;
    }

    private void recordDiagnostic(String event, Object... fields) {
        if (diagnostics != null) {
            diagnostics.record(event, fields);
        }
    }

    @Override
    public synchronized void start() {
        if (closing) {
            return;
        }

        if (nativeRenderer != 0) {
            nativeArm(nativeRenderer);
        }
        else if (track != null && track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
            track.play();
        }

        if (enableAudioFx && track != null && !audioFxSessionOpened) {
            Intent i = new Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
            i.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, track.getAudioSessionId());
            i.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.getPackageName());
            i.putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_GAME);
            context.sendBroadcast(i);
            audioFxSessionOpened = true;
        }
    }

    @Override
    public synchronized void stop() {
        shutdownNowLocked();
    }

    @Override
    public synchronized void cleanup() {
        shutdownNowLocked();
    }

    /** Immediately releases the local audio route before asynchronous network teardown. */
    public synchronized void shutdownNow() {
        shutdownNowLocked();
    }

    private void shutdownNowLocked() {
        if (closing) {
            return;
        }
        closing = true;

        if (audioFxSessionOpened && track != null) {
            Intent i = new Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
            i.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, track.getAudioSessionId());
            i.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.getPackageName());
            context.sendBroadcast(i);
            audioFxSessionOpened = false;
        }

        if (nativeRenderer != 0) {
            // Capture one final snapshot even if the normal interval has not elapsed.
            nextStatsLogTimeMs = 0;
            maybeLogNativeStats();
            long handle = nativeRenderer;
            nativeRenderer = 0;
            nativeDestroy(handle);
        }

        if (track != null) {
            nextStatsLogTimeMs = 0;
            maybeLogAudioTrackStats();
            try {
                if (track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause();
                }
                track.flush();
            }
            catch (IllegalStateException e) {
                LimeLog.warning("AudioTrack stop failed: " + e.getMessage());
            }
            finally {
                track.release();
                track = null;
            }
        }

        if (diagnostics != null) {
            diagnostics.close("renderer_shutdown");
        }
    }
}
