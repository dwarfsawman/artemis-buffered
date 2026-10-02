#pragma once

#include <stdbool.h>
#include <stdint.h>

enum {
    AUDIO_BUFFER_BASE_MS = 25,
    AUDIO_BUFFER_INITIAL_MS = 40,
    AUDIO_BUFFER_MAX_TARGET_MS = 90,
    AUDIO_BUFFER_HARD_CAP_MS = 240,
};

// Owned by the audio callback. All lengths are PCM frames, independent of channels.
typedef struct {
    uint32_t sampleRate;
    uint32_t packetFrames;
    uint32_t targetFrames;
    uint32_t lastWant;
    double averageFrames;
    uint64_t overFrames;
    uint64_t emptyFrames;
    uint64_t windowFrames;
    uint64_t quietFrames;
    uint64_t probeFrames;
    uint32_t probeTarget;
    uint32_t emptyCallbacks;
    uint32_t underruns;
    bool primed;
    bool nearMiss;
    bool nearMissGrown;
    bool hollow;
} AudioBufferPolicy;

typedef struct {
    uint32_t dropFrames;
    uint32_t fadeFrames;
    uint32_t targetFrames;
    bool hardTrim;
    bool silence;
} AudioBufferStep;

void AudioBufferPolicyInit(AudioBufferPolicy* policy, uint32_t sampleRate,
                           uint32_t packetFrames);
uint32_t AudioBufferPolicyTarget(const AudioBufferPolicy* policy, uint32_t wantFrames);
AudioBufferStep AudioBufferPolicyNext(AudioBufferPolicy* policy, uint32_t depthFrames,
                                      uint32_t wantFrames);
void AudioBufferPolicyRead(AudioBufferPolicy* policy, bool ranShort);

// Consumer only; call before publishing the new read cursor. Never touches unqueued slots.
void AudioBufferCrossfadeDrop(int16_t* ring, uint32_t capacityFrames, uint32_t channels,
                              uint32_t readFrame, uint32_t dropFrames, uint32_t fadeFrames);
