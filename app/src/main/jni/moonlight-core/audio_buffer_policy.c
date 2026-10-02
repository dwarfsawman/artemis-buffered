// Adapted from punktfunk crates/punktfunk-core/src/audio/jitter.rs, commit
// d016f73683b6b96ca64da99679ca5f4005846726. Copyright (c) 2026 unom - Enrico Bühler.
// MIT licensed; see third_party/punktfunk/LICENSE-MIT. The unsynchronised policy is
// ported to PCM frame counts, with a 40 ms initial target and shared channel weights.
#include "audio_buffer_policy.h"

#include <stddef.h>
#include <string.h>

static uint32_t minimum(uint32_t a, uint32_t b) { return a < b ? a : b; }
static uint32_t maximum(uint32_t a, uint32_t b) { return a > b ? a : b; }
static uint32_t frames(const AudioBufferPolicy* p, uint32_t ms) {
    return (uint32_t)((uint64_t)p->sampleRate * ms / 1000);
}

void AudioBufferPolicyInit(AudioBufferPolicy* p, uint32_t sampleRate, uint32_t packetFrames) {
    memset(p, 0, sizeof(*p));
    p->sampleRate = maximum(sampleRate, 1);
    p->packetFrames = maximum(packetFrames, 1);
    p->targetFrames = frames(p, AUDIO_BUFFER_INITIAL_MS);
}

uint32_t AudioBufferPolicyTarget(const AudioBufferPolicy* p, uint32_t want) {
    return maximum(p->targetFrames, want + p->packetFrames);
}

AudioBufferStep AudioBufferPolicyNext(AudioBufferPolicy* p, uint32_t depth, uint32_t want) {
    p->lastWant = want;
    uint32_t target = AudioBufferPolicyTarget(p, want);
    double alpha = (double)want / maximum(frames(p, 1000), 1);
    if (alpha > 1.0) alpha = 1.0;
    p->averageFrames += (depth - p->averageFrames) * alpha;

    uint32_t cap = maximum(minimum(target + frames(p, 40),
                                  frames(p, AUDIO_BUFFER_HARD_CAP_MS)), target + want);
    uint32_t hard = maximum(frames(p, AUDIO_BUFFER_HARD_CAP_MS), cap);
    uint32_t keep = depth;
    if (depth > hard) keep = hard;
    else if (depth > cap && p->averageFrames > cap) keep = cap;

    AudioBufferStep step = {.targetFrames = target};
    uint32_t fade = minimum(frames(p, 2), p->packetFrames / 2);
    if (keep < depth) {
        step.dropFrames = depth - keep;
        step.fadeFrames = minimum(fade, minimum(step.dropFrames, keep));
        step.hardTrim = true;
        p->averageFrames = keep;
        p->overFrames = 0;
    }
    else if (p->averageFrames > target + maximum(frames(p, 20), 2 * p->packetFrames)) {
        p->overFrames += want;
        if (p->overFrames >= frames(p, 2000)) {
            step.dropFrames = minimum(p->packetFrames, depth);
            step.fadeFrames = minimum(fade, depth - step.dropFrames);
            p->averageFrames -= step.dropFrames;
            if (p->averageFrames < 0.0) p->averageFrames = 0.0;
            p->overFrames = 0;
        }
    }
    else p->overFrames = 0;

    uint32_t after = depth - step.dropFrames;
    if (!p->primed && after >= target) {
        p->primed = true;
        p->emptyCallbacks = 0;
        p->emptyFrames = 0;
        p->averageFrames = after;
    }
    step.silence = !p->primed;
    p->nearMiss = p->primed && after >= want && after - want < p->packetFrames;
    p->hollow = p->primed && p->averageFrames + frames(p, 10) < target;
    return step;
}

void AudioBufferPolicyRead(AudioBufferPolicy* p, bool ranShort) {
    if (!p->primed) return; // Priming silence is not network starvation.
    uint32_t want = maximum(p->lastWant, 1);
    bool nearMiss = p->nearMiss;
    p->nearMiss = false;
    p->windowFrames += want;
    if (p->windowFrames >= frames(p, 5000)) {
        p->windowFrames = 0;
        p->underruns = 0;
        p->nearMissGrown = false;
    }
    bool restored = false;
    if (p->probeFrames > 0) {
        p->probeFrames = p->probeFrames > want ? p->probeFrames - want : 0;
        if (ranShort || nearMiss) {
            p->probeFrames = 0;
            p->targetFrames = maximum(p->targetFrames, p->probeTarget);
            restored = true;
        }
    }
    if (ranShort) {
        p->quietFrames = 0;
        p->emptyCallbacks++;
        p->emptyFrames += want;
        if ((p->emptyFrames >= frames(p, 60) && p->emptyCallbacks >= 2) || p->hollow) {
            p->primed = false;
            p->emptyCallbacks = 0;
            p->emptyFrames = 0;
        }
        if (!restored) p->underruns++;
        if (p->underruns >= 3) {
            p->underruns = 0;
            p->windowFrames = 0;
            p->targetFrames = minimum(p->targetFrames + frames(p, 10),
                                      frames(p, AUDIO_BUFFER_MAX_TARGET_MS));
        }
    }
    else if (nearMiss) {
        p->quietFrames = 0;
        p->emptyCallbacks = 0;
        p->emptyFrames = 0;
        if (!p->nearMissGrown && !restored) {
            p->nearMissGrown = true;
            p->targetFrames = minimum(p->targetFrames + frames(p, 10),
                                      frames(p, AUDIO_BUFFER_MAX_TARGET_MS));
        }
    }
    else {
        p->emptyCallbacks = 0;
        p->emptyFrames = 0;
        p->quietFrames += want;
        if (p->quietFrames >= frames(p, 30000)) {
            p->quietFrames = 0;
            uint32_t previous = p->targetFrames;
            uint32_t decrease = frames(p, 10);
            p->targetFrames = maximum(p->targetFrames > decrease ? p->targetFrames - decrease : 0,
                                      frames(p, AUDIO_BUFFER_BASE_MS));
            if (p->targetFrames < previous) {
                p->probeFrames = frames(p, 5000);
                p->probeTarget = previous;
            }
        }
    }
}

void AudioBufferCrossfadeDrop(int16_t* ring, uint32_t capacity, uint32_t channels,
                              uint32_t read, uint32_t drop, uint32_t fade) {
    // The consumer still holds the old read cursor, so the producer cannot overwrite
    // either side of the seam. fade <= drop prevents overwriting a later fade-out read.
    fade = minimum(fade, drop);
    for (uint32_t frame = 0; frame < fade; frame++) {
        size_t oldOffset = (size_t)((read + frame) % capacity) * channels;
        size_t newOffset = (size_t)((read + drop + frame) % capacity) * channels;
        int32_t newWeight = (int32_t)frame + 1;
        int32_t oldWeight = (int32_t)fade - (int32_t)frame;
        for (uint32_t ch = 0; ch < channels; ch++) {
            int64_t mixed = (int64_t)ring[oldOffset + ch] * oldWeight +
                            (int64_t)ring[newOffset + ch] * newWeight;
            ring[newOffset + ch] = (int16_t)(mixed / ((int64_t)fade + 1));
        }
    }
}
