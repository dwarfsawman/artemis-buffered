#include "audio_buffer_policy.h"
#include <assert.h>
#include <math.h>
#include <stdio.h>
#include <string.h>

static unsigned tests;
static uint32_t ms(uint32_t value) { return value * 48; }
static AudioBufferPolicy ready(uint32_t depthMs) {
    AudioBufferPolicy p;
    AudioBufferPolicyInit(&p, 48000, 240);
    AudioBufferStep s = AudioBufferPolicyNext(&p, ms(depthMs), 240);
    assert(!s.silence);
    AudioBufferPolicyRead(&p, false);
    return p;
}

static void priming(void) {
    AudioBufferPolicy p;
    AudioBufferPolicyInit(&p, 48000, 240);
    for (unsigned i = 0; i < 1000; i++) {
        assert(AudioBufferPolicyNext(&p, ms(39), 240).silence);
        AudioBufferPolicyRead(&p, false);
    }
    assert(p.targetFrames == ms(40));
    assert(!AudioBufferPolicyNext(&p, ms(40), 240).silence);
    assert(p.averageFrames == ms(40));
    tests++;
}

static void quantum_floor(void) {
    AudioBufferPolicy p;
    AudioBufferPolicyInit(&p, 48000, 240);
    assert(AudioBufferPolicyNext(&p, ms(90), ms(100)).silence);
    AudioBufferStep s = AudioBufferPolicyNext(&p, ms(105), ms(100));
    assert(!s.silence && s.targetFrames == ms(105) && s.dropFrames == 0);
    tests++;
}

static void healthy_pcm_unchanged(void) {
    int16_t pcm[480 * 8], original[480 * 8];
    for (unsigned channels = 2; channels <= 8; channels += 2) {
        memset(pcm, 0, sizeof(pcm));
        AudioBufferPolicy p = ready(40);
        for (unsigned i = 0; i < 480; i++)
            for (unsigned ch = 0; ch < channels; ch++)
                pcm[i * channels + ch] = (int16_t)(12000 * sin(2 * 3.141592653589793 * 3000 * i / 48000));
        memcpy(original, pcm, sizeof(pcm));
        // 90 seconds includes relaxation of the initial floor. No corrective splice is needed.
        for (unsigned i = 0; i < 18000; i++) {
            AudioBufferStep s = AudioBufferPolicyNext(&p, ms(40), 240);
            assert(!s.silence && s.dropFrames == 0);
            AudioBufferCrossfadeDrop(pcm, 480, channels, 0, s.dropFrames, s.fadeFrames);
            AudioBufferPolicyRead(&p, false);
        }
        assert(memcmp(pcm, original, sizeof(pcm)) == 0);
    }
    tests++;
}

static void transient_burst_preserved(void) {
    AudioBufferPolicy p = ready(40);
    for (unsigned i = 0; i < 2000; i++) {
        uint32_t depth = i % 100 < 10 ? ms(100) : ms(40);
        AudioBufferStep s = AudioBufferPolicyNext(&p, depth, 240);
        assert(!s.silence && s.dropFrames == 0);
        AudioBufferPolicyRead(&p, false);
    }
    tests++;
}

static void sustained_excess(void) {
    AudioBufferPolicy p = ready(40);
    unsigned sheds = 0;
    for (unsigned i = 0; i < 1600; i++) {
        AudioBufferStep s = AudioBufferPolicyNext(&p, ms(65), 240);
        if (i < 400) assert(s.dropFrames == 0);
        if (s.dropFrames) {
            assert(s.dropFrames == 240 && s.fadeFrames == 96 && !s.hardTrim);
            sheds++;
        }
        AudioBufferPolicyRead(&p, false);
    }
    assert(sheds >= 1 && sheds <= 4);
    tests++;
}

static void hard_cap(void) {
    AudioBufferPolicy p = ready(40);
    AudioBufferStep s = AudioBufferPolicyNext(&p, ms(300), 240);
    assert(s.hardTrim && s.dropFrames == ms(60) && s.fadeFrames == 96);
    assert(ms(300) - s.dropFrames >= s.targetFrames);
    tests++;
}

static void near_miss_growth(void) {
    AudioBufferPolicy p = ready(40);
    for (unsigned i = 0; i < 20; i++) {
        AudioBufferPolicyNext(&p, 479, 240);
        AudioBufferPolicyRead(&p, false);
        assert(p.primed && p.targetFrames == ms(50));
    }
    assert(p.underruns == 0);
    tests++;
}

static void single_short_read(void) {
    AudioBufferPolicy p = ready(40);
    AudioBufferPolicyNext(&p, 0, 240);
    AudioBufferPolicyRead(&p, true);
    assert(p.primed);
    assert(!AudioBufferPolicyNext(&p, ms(40), 240).silence);
    AudioBufferPolicyRead(&p, false);
    assert(p.targetFrames == ms(40));
    tests++;
}

static void large_quantum_hysteresis(void) {
    AudioBufferPolicy p = ready(200);
    AudioBufferPolicyNext(&p, 0, ms(80));
    AudioBufferPolicyRead(&p, true);
    assert(p.primed); // 80 ms already exceeds the fuse, but one read is insufficient.
    AudioBufferPolicyNext(&p, 0, ms(80));
    AudioBufferPolicyRead(&p, true);
    assert(!p.primed);
    tests++;
}

static void hollow_reprime_and_refill(void) {
    AudioBufferPolicy p = ready(40);
    p.targetFrames = ms(90);
    AudioBufferPolicyNext(&p, 0, 240);
    AudioBufferPolicyRead(&p, true);
    assert(!p.primed);
    for (unsigned i = 0; i < 200; i++) {
        assert(AudioBufferPolicyNext(&p, ms(89), 240).silence);
        AudioBufferPolicyRead(&p, false);
    }
    assert(!AudioBufferPolicyNext(&p, ms(90), 240).silence);
    assert(p.averageFrames == ms(90) && !p.hollow);
    tests++;
}

static void growth_is_bounded(void) {
    AudioBufferPolicy p = ready(40);
    for (unsigned i = 0; i < 100; i++) {
        p.primed = true;
        p.averageFrames = ms(150);
        AudioBufferPolicyNext(&p, 0, 240);
        AudioBufferPolicyRead(&p, true);
    }
    assert(p.targetFrames == ms(90));
    tests++;
}

static void failed_shrink_restores_proven_floor(void) {
    AudioBufferPolicy p = ready(60);
    for (unsigned i = 0; i < 6000; i++) {
        AudioBufferPolicyNext(&p, ms(60), 240);
        AudioBufferPolicyRead(&p, false);
    }
    assert(p.targetFrames == ms(30) && p.probeFrames > 0);
    AudioBufferPolicyNext(&p, 479, 240);
    AudioBufferPolicyRead(&p, false);
    assert(p.targetFrames == ms(40) && p.probeFrames == 0);
    tests++;
}

static void negotiated_packet_and_rate(void) {
    AudioBufferPolicy p;
    AudioBufferPolicyInit(&p, 96000, 192); // 2 ms, not a hardcoded 5 ms frame.
    assert(p.targetFrames == 3840);
    AudioBufferPolicyNext(&p, 3840, 480);
    AudioBufferPolicyRead(&p, false);
    AudioBufferStep s = {0};
    for (unsigned i = 0; i < 1600 && !s.dropFrames; i++) {
        s = AudioBufferPolicyNext(&p, 6240, 480);
        AudioBufferPolicyRead(&p, false);
    }
    assert(s.dropFrames == 192 && s.fadeFrames == 96);
    AudioBufferPolicyInit(&p, 44100, 220);
    assert(p.targetFrames == 1764);
    assert(AudioBufferPolicyTarget(&p, 4410) == 4630);
    tests++;
}

static void crossfade_wrap_and_channels(void) {
    int16_t ring[1024 * 8], original[1024 * 8];
    for (unsigned channels = 2; channels <= 8; channels += 2) {
        memset(ring, 0, sizeof(ring));
        for (unsigned frame = 0; frame < 1024; frame++)
            for (unsigned ch = 0; ch < channels; ch++)
                ring[frame * channels + ch] = (int16_t)((int)frame * 20 - 10000 + (int)ch * 77);
        memcpy(original, ring, sizeof(ring));
        AudioBufferCrossfadeDrop(ring, 1024, channels, 1000, 240, 96);
        for (unsigned frame = 0; frame < 1024; frame++) {
            for (unsigned ch = 0; ch < channels; ch++) {
                int16_t expected = original[frame * channels + ch];
                if (frame >= 216 && frame < 312) {
                    unsigned i = frame - 216;
                    int64_t old = original[((1000 + i) % 1024) * channels + ch];
                    int64_t next = original[frame * channels + ch];
                    expected = (int16_t)((old * (96 - i) + next * (i + 1)) / 97);
                }
                assert(ring[frame * channels + ch] == expected);
            }
        }
    }
    tests++;
}

static void drift(int ppm) {
    AudioBufferPolicy p = ready(40);
    uint32_t depth = ms(40), peak = depth;
    int64_t carry = 0;
    unsigned underruns = 0, reprimes = 0, sheds = 0, trims = 0;
    for (unsigned i = 0; i < 60000; i++) {
        carry += (int64_t)240 * ppm;
        int extra = (int)(carry / 1000000);
        carry -= (int64_t)extra * 1000000;
        depth += (uint32_t)(240 + extra);
        bool wasPrimed = p.primed;
        AudioBufferStep s = AudioBufferPolicyNext(&p, depth, 240);
        depth -= s.dropFrames;
        if (s.hardTrim) trims++;
        else if (s.dropFrames) sheds++;
        if (depth > peak) peak = depth;
        if (!s.silence) {
            bool shortRead = depth < 240;
            if (shortRead) underruns++;
            depth = depth > 240 ? depth - 240 : 0;
            AudioBufferPolicyRead(&p, shortRead);
        }
        if (wasPrimed && !p.primed) reprimes++;
    }
    printf("drift %d ppm: peak=%.2f ms underruns=%u reprimes=%u sheds=%u hardTrims=%u\n",
           ppm, (double)peak / 48, underruns, reprimes, sheds, trims);
    assert(trims == 0 && peak < ms(100));
    if (ppm > 0) assert(underruns == 0 && sheds > 0);
    else assert(underruns < 20 && reprimes < 10);
    tests++;
}

int main(void) {
    priming(); quantum_floor(); healthy_pcm_unchanged(); transient_burst_preserved();
    sustained_excess(); hard_cap(); near_miss_growth(); single_short_read();
    large_quantum_hysteresis(); hollow_reprime_and_refill(); growth_is_bounded();
    failed_shrink_restores_proven_floor(); negotiated_packet_and_rate();
    crossfade_wrap_and_channels(); drift(200); drift(500); drift(-200); drift(-500);
    printf("Audio buffer policy: %u tests passed\n", tests);
    return 0;
}
