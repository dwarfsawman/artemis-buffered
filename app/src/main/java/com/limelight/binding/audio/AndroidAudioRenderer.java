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

    static {
        System.loadLibrary("moonlight-core");
    }

    private final Context context;
    private final boolean enableAudioFx;
    private final int audioBufferMs;
    private final long[] nativeStats = new long[6];

    private AudioTrack track;
    private long nativeRenderer;
    private int sampleRate;
    private int channelCount;
    private boolean audioFxSessionOpened;
    private boolean closing;
    private long nextStatsLogTimeMs;

    public AndroidAudioRenderer(Context context, boolean enableAudioFx, int audioBufferMs) {
        this.context = context;
        this.enableAudioFx = enableAudioFx;
        this.audioBufferMs = Math.max(0, Math.min(500, audioBufferMs));
    }

    private static native long nativeCreate(int sampleRate, int channelCount, int bufferMs);
    private static native void nativeArm(long handle);
    private static native int nativeWrite(long handle, short[] audioData);
    private static native void nativeGetStats(long handle, long[] stats);
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

        // AAudio uses a high-priority callback to consume a separate PCM jitter ring.
        // Audio effects require an AudioTrack session, so preserve the legacy path for that case.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && !enableAudioFx) {
            nativeRenderer = nativeCreate(sampleRate, channelCount, audioBufferMs);
            if (nativeRenderer != 0) {
                LimeLog.info("Using callback AAudio renderer with " + audioBufferMs + " ms target");
                return 0;
            }
            LimeLog.warning("AAudio renderer unavailable; falling back to low-latency AudioTrack");
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

    @Override
    public synchronized void playDecodedAudio(short[] audioData) {
        if (closing) {
            return;
        }

        if (nativeRenderer != 0) {
            int acceptedFrames = nativeWrite(nativeRenderer, audioData);
            if (acceptedFrames < 0) {
                LimeLog.warning("AAudio ring write failed with code " + acceptedFrames);
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
                    break;
                }
                offset += written;
            }
        }
        else {
            LimeLog.warning("Too much pending fallback audio data: " +
                    MoonBridge.getPendingAudioDuration() + " ms");
        }
    }

    private void maybeLogNativeStats() {
        long now = SystemClock.elapsedRealtime();
        if (now < nextStatsLogTimeMs || nativeRenderer == 0) {
            return;
        }

        nextStatsLogTimeMs = now + STATS_LOG_INTERVAL_MS;
        nativeGetStats(nativeRenderer, nativeStats);
        long queuedMs = sampleRate == 0 ? 0 : nativeStats[0] * 1000 / sampleRate;
        long underrunMs = sampleRate == 0 ? 0 : nativeStats[2] * 1000 / sampleRate;
        long droppedMs = sampleRate == 0 ? 0 : nativeStats[3] * 1000 / sampleRate;
        LimeLog.info("AAudio jitter stats: queued=" + queuedMs +
                " ms, underrunCallbacks=" + nativeStats[1] +
                ", underrun=" + underrunMs +
                " ms, dropped=" + droppedMs +
                " ms, xrun=" + nativeStats[4] +
                ", error=" + nativeStats[5]);
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
            long handle = nativeRenderer;
            nativeRenderer = 0;
            nativeDestroy(handle);
        }

        if (track != null) {
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
    }
}
