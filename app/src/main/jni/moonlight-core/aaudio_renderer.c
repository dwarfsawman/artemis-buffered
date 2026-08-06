#include <aaudio/AAudio.h>
#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <math.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define LOG_TAG "ArtemisAAudio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

typedef struct {
    void* library;
    aaudio_result_t (*createStreamBuilder)(AAudioStreamBuilder** builder);
    aaudio_result_t (*builderDelete)(AAudioStreamBuilder* builder);
    void (*builderSetDirection)(AAudioStreamBuilder* builder, aaudio_direction_t direction);
    void (*builderSetSharingMode)(AAudioStreamBuilder* builder, aaudio_sharing_mode_t sharingMode);
    void (*builderSetPerformanceMode)(AAudioStreamBuilder* builder, aaudio_performance_mode_t mode);
    void (*builderSetFormat)(AAudioStreamBuilder* builder, aaudio_format_t format);
    void (*builderSetSampleRate)(AAudioStreamBuilder* builder, int32_t sampleRate);
    void (*builderSetChannelCount)(AAudioStreamBuilder* builder, int32_t channelCount);
    void (*builderSetUsage)(AAudioStreamBuilder* builder, aaudio_usage_t usage);
    void (*builderSetContentType)(AAudioStreamBuilder* builder, aaudio_content_type_t contentType);
    void (*builderSetDataCallback)(AAudioStreamBuilder* builder,
                                   AAudioStream_dataCallback callback,
                                   void* userData);
    void (*builderSetErrorCallback)(AAudioStreamBuilder* builder,
                                    AAudioStream_errorCallback callback,
                                    void* userData);
    aaudio_result_t (*builderOpenStream)(AAudioStreamBuilder* builder, AAudioStream** stream);
    aaudio_result_t (*streamRequestStart)(AAudioStream* stream);
    aaudio_result_t (*streamRequestStop)(AAudioStream* stream);
    aaudio_result_t (*streamClose)(AAudioStream* stream);
    aaudio_result_t (*streamSetBufferSizeInFrames)(AAudioStream* stream, int32_t numFrames);
    int32_t (*streamGetFramesPerBurst)(AAudioStream* stream);
    int32_t (*streamGetXRunCount)(AAudioStream* stream);
    int32_t (*streamGetSampleRate)(AAudioStream* stream);
    int32_t (*streamGetChannelCount)(AAudioStream* stream);
    aaudio_format_t (*streamGetFormat)(AAudioStream* stream);
    aaudio_performance_mode_t (*streamGetPerformanceMode)(AAudioStream* stream);
    const char* (*convertResultToText)(aaudio_result_t result);
    bool ready;
} AAudioApi;

typedef struct {
    AAudioStream* stream;
    int16_t* ring;
    int16_t* stretchBuffer;
    uint32_t capacityFrames;
    uint32_t stretchCapacityFrames;
    uint32_t minTargetFrames;
    uint32_t maxTargetFrames;
    int32_t sampleRate;
    int32_t channelCount;
    bool adaptive;
    int64_t lastArrivalNs;
    int64_t lastTargetDecreaseNs;
    int64_t protectionUntilNs;
    uint32_t previousPacketFrames;
    uint32_t observedUnderrunCallbacks;
    double jitterMs;
    atomic_uint readFrame;
    atomic_uint writeFrame;
    atomic_uint targetFrames;
    atomic_bool armed;
    atomic_bool started;
    atomic_bool closing;
    atomic_uint underrunCallbacks;
    atomic_uint underrunFrames;
    atomic_uint droppedFrames;
    atomic_int playbackRatePpm;
    atomic_int jitterMicros;
    atomic_llong timeStretchFrameDelta;
    atomic_int lastError;
} AAudioRenderer;

enum {
    FIXED_TARGET_MS = 40,
    MIN_TARGET_MS = 20,
    INITIAL_TARGET_MS = 40,
    MAX_TARGET_MS = 80,
    TARGET_DECREASE_INTERVAL_MS = 1000,
    UNDERRUN_PROTECTION_MS = 5000,
    RATE_ONE_PPM = 1000000,
    RATE_MIN_PPM = 970000,
    RATE_MAX_PPM = 1030000,
};

static AAudioApi gApi;
static pthread_once_t gApiOnce = PTHREAD_ONCE_INIT;

#define LOAD_REQUIRED(field, symbolName)                                                   \
    do {                                                                                  \
        gApi.field = (__typeof__(gApi.field))dlsym(gApi.library, symbolName);             \
        if (gApi.field == NULL) {                                                          \
            LOGE("Missing required AAudio symbol: %s", symbolName);                       \
            return;                                                                       \
        }                                                                                 \
    } while (0)

#define LOAD_OPTIONAL(field, symbolName)                                                   \
    do {                                                                                  \
        gApi.field = (__typeof__(gApi.field))dlsym(gApi.library, symbolName);             \
    } while (0)

static void loadAAudioApi(void) {
    memset(&gApi, 0, sizeof(gApi));
    gApi.library = dlopen("libaaudio.so", RTLD_NOW | RTLD_LOCAL);
    if (gApi.library == NULL) {
        LOGW("AAudio is unavailable: %s", dlerror());
        return;
    }

    LOAD_REQUIRED(createStreamBuilder, "AAudio_createStreamBuilder");
    LOAD_REQUIRED(builderDelete, "AAudioStreamBuilder_delete");
    LOAD_REQUIRED(builderSetDirection, "AAudioStreamBuilder_setDirection");
    LOAD_REQUIRED(builderSetSharingMode, "AAudioStreamBuilder_setSharingMode");
    LOAD_REQUIRED(builderSetPerformanceMode, "AAudioStreamBuilder_setPerformanceMode");
    LOAD_REQUIRED(builderSetFormat, "AAudioStreamBuilder_setFormat");
    LOAD_REQUIRED(builderSetSampleRate, "AAudioStreamBuilder_setSampleRate");
    LOAD_REQUIRED(builderSetChannelCount, "AAudioStreamBuilder_setChannelCount");
    LOAD_REQUIRED(builderSetDataCallback, "AAudioStreamBuilder_setDataCallback");
    LOAD_REQUIRED(builderSetErrorCallback, "AAudioStreamBuilder_setErrorCallback");
    LOAD_REQUIRED(builderOpenStream, "AAudioStreamBuilder_openStream");
    LOAD_REQUIRED(streamRequestStart, "AAudioStream_requestStart");
    LOAD_REQUIRED(streamRequestStop, "AAudioStream_requestStop");
    LOAD_REQUIRED(streamClose, "AAudioStream_close");
    LOAD_REQUIRED(streamSetBufferSizeInFrames, "AAudioStream_setBufferSizeInFrames");
    LOAD_REQUIRED(streamGetFramesPerBurst, "AAudioStream_getFramesPerBurst");
    LOAD_REQUIRED(streamGetXRunCount, "AAudioStream_getXRunCount");
    LOAD_REQUIRED(streamGetSampleRate, "AAudioStream_getSampleRate");
    LOAD_REQUIRED(streamGetChannelCount, "AAudioStream_getChannelCount");
    LOAD_REQUIRED(streamGetFormat, "AAudioStream_getFormat");
    LOAD_REQUIRED(streamGetPerformanceMode, "AAudioStream_getPerformanceMode");
    LOAD_REQUIRED(convertResultToText, "AAudio_convertResultToText");

    // Usage and content type were added in API 28. AAudio itself is available from API 26.
    LOAD_OPTIONAL(builderSetUsage, "AAudioStreamBuilder_setUsage");
    LOAD_OPTIONAL(builderSetContentType, "AAudioStreamBuilder_setContentType");
    gApi.ready = true;
}

static const char* resultText(aaudio_result_t result) {
    if (gApi.convertResultToText != NULL) {
        return gApi.convertResultToText(result);
    }
    return "unknown";
}

static int64_t monotonicTimeNs(void) {
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    return (int64_t)now.tv_sec * 1000000000LL + now.tv_nsec;
}

static uint32_t framesForMs(const AAudioRenderer* renderer, uint32_t milliseconds) {
    return (uint32_t)(((int64_t)renderer->sampleRate * milliseconds) / 1000);
}

static uint32_t clampFrames(uint32_t value, uint32_t minimum, uint32_t maximum) {
    if (value < minimum) {
        return minimum;
    }
    if (value > maximum) {
        return maximum;
    }
    return value;
}

static void copyFromRing(AAudioRenderer* renderer, int16_t* destination,
                         uint32_t readFrame, uint32_t frames) {
    uint32_t ringIndex = readFrame % renderer->capacityFrames;
    uint32_t firstFrames = frames;
    if (ringIndex + frames > renderer->capacityFrames) {
        firstFrames = renderer->capacityFrames - ringIndex;
    }

    size_t firstSamples = (size_t)firstFrames * renderer->channelCount;
    memcpy(destination,
           renderer->ring + (size_t)ringIndex * renderer->channelCount,
           firstSamples * sizeof(int16_t));

    if (firstFrames < frames) {
        size_t remainingSamples = (size_t)(frames - firstFrames) * renderer->channelCount;
        memcpy(destination + firstSamples,
               renderer->ring,
               remainingSamples * sizeof(int16_t));
    }
}

static void copyToRing(AAudioRenderer* renderer, const int16_t* source,
                       uint32_t writeFrame, uint32_t frames) {
    uint32_t ringIndex = writeFrame % renderer->capacityFrames;
    uint32_t firstFrames = frames;
    if (ringIndex + frames > renderer->capacityFrames) {
        firstFrames = renderer->capacityFrames - ringIndex;
    }

    size_t firstSamples = (size_t)firstFrames * renderer->channelCount;
    memcpy(renderer->ring + (size_t)ringIndex * renderer->channelCount,
           source,
           firstSamples * sizeof(int16_t));

    if (firstFrames < frames) {
        size_t remainingSamples = (size_t)(frames - firstFrames) * renderer->channelCount;
        memcpy(renderer->ring,
               source + firstSamples,
               remainingSamples * sizeof(int16_t));
    }
}

static aaudio_data_callback_result_t dataCallback(AAudioStream* stream, void* userData,
                                                   void* audioData, int32_t numFrames) {
    (void)stream;
    AAudioRenderer* renderer = (AAudioRenderer*)userData;
    int16_t* output = (int16_t*)audioData;

    if (renderer == NULL || numFrames <= 0 || atomic_load_explicit(&renderer->closing, memory_order_acquire)) {
        if (renderer != NULL && numFrames > 0) {
            memset(output, 0, (size_t)numFrames * renderer->channelCount * sizeof(int16_t));
        }
        return AAUDIO_CALLBACK_RESULT_STOP;
    }

    uint32_t readFrame = atomic_load_explicit(&renderer->readFrame, memory_order_relaxed);
    uint32_t writeFrame = atomic_load_explicit(&renderer->writeFrame, memory_order_acquire);
    uint32_t availableFrames = writeFrame - readFrame;
    uint32_t requestedFrames = (uint32_t)numFrames;
    uint32_t copiedFrames = availableFrames < requestedFrames ? availableFrames : requestedFrames;

    if (copiedFrames > 0) {
        copyFromRing(renderer, output, readFrame, copiedFrames);
        atomic_store_explicit(&renderer->readFrame, readFrame + copiedFrames, memory_order_release);
    }

    if (copiedFrames < requestedFrames) {
        uint32_t missingFrames = requestedFrames - copiedFrames;
        memset(output + (size_t)copiedFrames * renderer->channelCount,
               0,
               (size_t)missingFrames * renderer->channelCount * sizeof(int16_t));
        atomic_fetch_add_explicit(&renderer->underrunCallbacks, 1, memory_order_relaxed);
        atomic_fetch_add_explicit(&renderer->underrunFrames, missingFrames, memory_order_relaxed);
    }

    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

static void errorCallback(AAudioStream* stream, void* userData, aaudio_result_t error) {
    (void)stream;
    AAudioRenderer* renderer = (AAudioRenderer*)userData;
    if (renderer != NULL) {
        atomic_store_explicit(&renderer->lastError, error, memory_order_release);
    }
    LOGE("AAudio stream error: %d (%s)", error, resultText(error));
}

static void maybeStart(AAudioRenderer* renderer) {
    if (!atomic_load_explicit(&renderer->armed, memory_order_acquire) ||
        atomic_load_explicit(&renderer->closing, memory_order_acquire)) {
        return;
    }

    uint32_t readFrame = atomic_load_explicit(&renderer->readFrame, memory_order_acquire);
    uint32_t writeFrame = atomic_load_explicit(&renderer->writeFrame, memory_order_acquire);
    uint32_t targetFrames = atomic_load_explicit(&renderer->targetFrames, memory_order_acquire);
    if (writeFrame - readFrame < targetFrames) {
        return;
    }

    bool expected = false;
    if (!atomic_compare_exchange_strong_explicit(&renderer->started, &expected, true,
                                                  memory_order_acq_rel, memory_order_acquire)) {
        return;
    }

    aaudio_result_t result = gApi.streamRequestStart(renderer->stream);
    if (result != AAUDIO_OK) {
        atomic_store_explicit(&renderer->started, false, memory_order_release);
        atomic_store_explicit(&renderer->lastError, result, memory_order_release);
        LOGE("AAudio start failed: %d (%s)", result, resultText(result));
    }
    else {
        LOGI("AAudio playback started with %u queued frames", writeFrame - readFrame);
    }
}

static double normalizedCorrelation(const AAudioRenderer* renderer, const int16_t* input,
                                    uint32_t firstFrame, uint32_t secondFrame,
                                    uint32_t frameCount) {
    double cross = 0.0;
    double firstEnergy = 1.0;
    double secondEnergy = 1.0;
    for (uint32_t frame = 0; frame < frameCount; frame++) {
        size_t firstOffset = (size_t)(firstFrame + frame) * renderer->channelCount;
        size_t secondOffset = (size_t)(secondFrame + frame) * renderer->channelCount;
        for (int32_t channel = 0; channel < renderer->channelCount; channel++) {
            double first = input[firstOffset + channel];
            double second = input[secondOffset + channel];
            cross += first * second;
            firstEnergy += first * first;
            secondEnergy += second * second;
        }
    }
    return cross / sqrt(firstEnergy * secondEnergy);
}

static uint32_t findBestSplice(const AAudioRenderer* renderer, const int16_t* input,
                               uint32_t inputFrames, uint32_t overlapFrames,
                               uint32_t adjustmentFrames, bool expanding) {
    uint32_t minimumSplice = expanding ? overlapFrames + adjustmentFrames : overlapFrames;
    uint32_t maximumSplice = expanding ? inputFrames : inputFrames - adjustmentFrames;
    uint32_t center = (minimumSplice + maximumSplice) / 2;
    uint32_t searchRadius = (uint32_t)renderer->sampleRate / 1000;
    uint32_t availableRadius = (maximumSplice - minimumSplice) / 3;
    if (searchRadius > availableRadius) {
        searchRadius = availableRadius;
    }
    if (searchRadius < 1) {
        searchRadius = 1;
    }
    uint32_t searchStart = center > searchRadius ? center - searchRadius : minimumSplice;
    if (searchStart < minimumSplice) {
        searchStart = minimumSplice;
    }
    uint32_t searchEnd = center + searchRadius;
    if (searchEnd > maximumSplice) {
        searchEnd = maximumSplice;
    }

    uint32_t bestSplice = center;
    double bestScore = -2.0;
    for (uint32_t splice = searchStart; splice <= searchEnd; splice++) {
        uint32_t firstStart = splice - overlapFrames;
        uint32_t secondStart = expanding ?
                splice - adjustmentFrames - overlapFrames :
                splice + adjustmentFrames - overlapFrames;
        double score = normalizedCorrelation(renderer, input, firstStart, secondStart,
                                             overlapFrames);
        if (score > bestScore) {
            bestScore = score;
            bestSplice = splice;
        }
    }
    return bestSplice;
}

static uint32_t overlapAdd(AAudioRenderer* renderer, const int16_t* input,
                           uint32_t inputFrames, uint32_t overlapFrames,
                           uint32_t adjustmentFrames, uint32_t spliceFrame,
                           bool expanding) {
    uint32_t outputFrames = expanding ? inputFrames + adjustmentFrames :
                                        inputFrames - adjustmentFrames;
    int16_t* output = renderer->stretchBuffer;
    uint32_t prefixFrames = spliceFrame - overlapFrames;
    memcpy(output, input,
           (size_t)prefixFrames * renderer->channelCount * sizeof(int16_t));

    uint32_t secondStartFrame = expanding ?
            spliceFrame - adjustmentFrames - overlapFrames :
            spliceFrame + adjustmentFrames - overlapFrames;
    for (uint32_t frame = 0; frame < overlapFrames; frame++) {
        size_t firstOffset = (size_t)(spliceFrame - overlapFrames + frame) *
                             renderer->channelCount;
        size_t secondOffset = (size_t)(secondStartFrame + frame) * renderer->channelCount;
        size_t outputOffset = (size_t)(prefixFrames + frame) * renderer->channelCount;
        int32_t firstWeight = (int32_t)(overlapFrames - 1 - frame);
        int32_t secondWeight = (int32_t)frame;
        int32_t divisor = (int32_t)overlapFrames - 1;
        for (int32_t channel = 0; channel < renderer->channelCount; channel++) {
            int32_t mixed = (input[firstOffset + channel] * firstWeight +
                             input[secondOffset + channel] * secondWeight) / divisor;
            output[outputOffset + channel] = (int16_t)mixed;
        }
    }

    uint32_t suffixInputFrame = expanding ? spliceFrame - adjustmentFrames :
                                            spliceFrame + adjustmentFrames;
    uint32_t suffixFrames = inputFrames - suffixInputFrame;
    memcpy(output + (size_t)spliceFrame * renderer->channelCount,
           input + (size_t)suffixInputFrame * renderer->channelCount,
           (size_t)suffixFrames * renderer->channelCount * sizeof(int16_t));
    return outputFrames;
}

static uint32_t stretchFrames(AAudioRenderer* renderer, const int16_t* input,
                              uint32_t inputFrames, int ratePpm, const int16_t** output) {
    *output = input;
    if (!renderer->adaptive || ratePpm == RATE_ONE_PPM || inputFrames == 0) {
        return inputFrames;
    }

    if (ratePpm < RATE_MIN_PPM) {
        ratePpm = RATE_MIN_PPM;
    }
    else if (ratePpm > RATE_MAX_PPM) {
        ratePpm = RATE_MAX_PPM;
    }
    uint32_t outputFrames = (uint32_t)(((int64_t)inputFrames * RATE_ONE_PPM + ratePpm / 2) /
                                       ratePpm);
    if (outputFrames == inputFrames) {
        return inputFrames;
    }

    uint32_t adjustmentFrames = outputFrames > inputFrames ? outputFrames - inputFrames :
                                                               inputFrames - outputFrames;
    uint32_t overlapFrames = (uint32_t)renderer->sampleRate / 1000;
    uint32_t quarterPacket = inputFrames / 4;
    if (overlapFrames < 8) {
        overlapFrames = 8;
    }
    if (overlapFrames > quarterPacket) {
        overlapFrames = quarterPacket;
    }
    if (overlapFrames < 2 || adjustmentFrames + overlapFrames >= inputFrames ||
        outputFrames > renderer->stretchCapacityFrames) {
        return inputFrames;
    }

    bool expanding = outputFrames > inputFrames;
    uint32_t spliceFrame = findBestSplice(renderer, input, inputFrames, overlapFrames,
                                          adjustmentFrames, expanding);
    outputFrames = overlapAdd(renderer, input, inputFrames, overlapFrames, adjustmentFrames,
                              spliceFrame, expanding);
    *output = renderer->stretchBuffer;
    atomic_fetch_add_explicit(&renderer->timeStretchFrameDelta,
                              (long long)outputFrames - inputFrames,
                              memory_order_relaxed);
    return outputFrames;
}

static int updateAdaptiveController(AAudioRenderer* renderer, uint32_t packetFrames,
                                    uint32_t queuedFrames) {
    if (!renderer->adaptive) {
        return RATE_ONE_PPM;
    }

    int64_t nowNs = monotonicTimeNs();
    uint32_t targetFrames = atomic_load_explicit(&renderer->targetFrames, memory_order_relaxed);
    uint32_t underruns = atomic_load_explicit(&renderer->underrunCallbacks, memory_order_relaxed);
    if (underruns != renderer->observedUnderrunCallbacks) {
        renderer->observedUnderrunCallbacks = underruns;
        targetFrames = renderer->maxTargetFrames;
        renderer->protectionUntilNs = nowNs + (int64_t)UNDERRUN_PROTECTION_MS * 1000000LL;
        renderer->lastTargetDecreaseNs = nowNs;
    }

    if (renderer->lastArrivalNs >= 0 && renderer->previousPacketFrames > 0) {
        double intervalMs = (nowNs - renderer->lastArrivalNs) / 1000000.0;
        double expectedMs = renderer->previousPacketFrames * 1000.0 / renderer->sampleRate;
        double deviationMs = fabs(intervalMs - expectedMs);
        if (deviationMs > MAX_TARGET_MS) {
            deviationMs = MAX_TARGET_MS;
        }
        renderer->jitterMs += (deviationMs - renderer->jitterMs) / 16.0;
        uint32_t desiredMs = MIN_TARGET_MS + (uint32_t)ceil(renderer->jitterMs * 4.0);
        if (desiredMs > MAX_TARGET_MS) {
            desiredMs = MAX_TARGET_MS;
        }
        uint32_t desiredFrames = clampFrames(framesForMs(renderer, desiredMs),
                                             renderer->minTargetFrames,
                                             renderer->maxTargetFrames);
        if (intervalMs > expectedMs + targetFrames * 1000.0 / renderer->sampleRate) {
            desiredFrames = renderer->maxTargetFrames;
            renderer->protectionUntilNs = nowNs +
                    (int64_t)UNDERRUN_PROTECTION_MS * 1000000LL;
        }
        if (nowNs < renderer->protectionUntilNs && desiredFrames < targetFrames) {
            desiredFrames = targetFrames;
        }
        if (desiredFrames >= targetFrames) {
            targetFrames = desiredFrames;
            renderer->lastTargetDecreaseNs = nowNs;
        }
        else if (renderer->lastTargetDecreaseNs < 0) {
            renderer->lastTargetDecreaseNs = nowNs;
        }
        else {
            int64_t steps = (nowNs - renderer->lastTargetDecreaseNs) /
                    ((int64_t)TARGET_DECREASE_INTERVAL_MS * 1000000LL);
            if (steps > 0) {
                uint32_t decrease = framesForMs(renderer, (uint32_t)steps);
                targetFrames = targetFrames > decrease ? targetFrames - decrease : desiredFrames;
                if (targetFrames < desiredFrames) {
                    targetFrames = desiredFrames;
                }
                renderer->lastTargetDecreaseNs += steps *
                        (int64_t)TARGET_DECREASE_INTERVAL_MS * 1000000LL;
            }
        }
        atomic_store_explicit(&renderer->targetFrames, targetFrames, memory_order_release);
    }
    else {
        renderer->lastTargetDecreaseNs = nowNs;
    }
    renderer->lastArrivalNs = nowNs;
    renderer->previousPacketFrames = packetFrames;
    atomic_store_explicit(&renderer->jitterMicros,
                          (int)llround(renderer->jitterMs * 1000.0),
                          memory_order_relaxed);

    int32_t errorFrames = (int32_t)queuedFrames - (int32_t)targetFrames;
    uint32_t magnitudeFrames = (uint32_t)(errorFrames < 0 ? -errorFrames : errorFrames);
    uint32_t deadbandFrames = framesForMs(renderer, 4);
    int ratePpm = RATE_ONE_PPM;
    if (magnitudeFrames > deadbandFrames) {
        double magnitudeMs = magnitudeFrames * 1000.0 / renderer->sampleRate;
        int adjustmentPpm = 10000 + (int)((magnitudeMs - 4.0) * 1000.0);
        if (adjustmentPpm > 30000) {
            adjustmentPpm = 30000;
        }
        ratePpm = errorFrames > 0 ? RATE_ONE_PPM + adjustmentPpm :
                                    RATE_ONE_PPM - adjustmentPpm;
    }
    atomic_store_explicit(&renderer->playbackRatePpm, ratePpm, memory_order_relaxed);
    return ratePpm;
}

static uint32_t writeFrames(AAudioRenderer* renderer, const int16_t* samples, uint32_t frames) {
    if (frames == 0 || atomic_load_explicit(&renderer->closing, memory_order_acquire)) {
        return 0;
    }

    uint32_t readFrame = atomic_load_explicit(&renderer->readFrame, memory_order_acquire);
    uint32_t writeFrame = atomic_load_explicit(&renderer->writeFrame, memory_order_relaxed);
    uint32_t queuedFrames = writeFrame - readFrame;
    int playbackRatePpm = updateAdaptiveController(renderer, frames, queuedFrames);
    const int16_t* adjustedSamples = samples;
    uint32_t adjustedFrames = stretchFrames(renderer, samples, frames, playbackRatePpm,
                                            &adjustedSamples);
    uint32_t freeFrames = queuedFrames < renderer->capacityFrames ?
            renderer->capacityFrames - queuedFrames : 0;
    uint32_t acceptedFrames = adjustedFrames < freeFrames ? adjustedFrames : freeFrames;

    if (acceptedFrames > 0) {
        copyToRing(renderer, adjustedSamples, writeFrame, acceptedFrames);
        atomic_store_explicit(&renderer->writeFrame, writeFrame + acceptedFrames, memory_order_release);
    }

    if (acceptedFrames < adjustedFrames) {
        atomic_fetch_add_explicit(&renderer->droppedFrames,
                                  adjustedFrames - acceptedFrames,
                                  memory_order_relaxed);
    }

    maybeStart(renderer);
    return acceptedFrames;
}

static void destroyRenderer(AAudioRenderer* renderer) {
    if (renderer == NULL) {
        return;
    }

    atomic_store_explicit(&renderer->closing, true, memory_order_release);
    atomic_store_explicit(&renderer->armed, false, memory_order_release);

    if (renderer->stream != NULL) {
        if (atomic_load_explicit(&renderer->started, memory_order_acquire)) {
            aaudio_result_t stopResult = gApi.streamRequestStop(renderer->stream);
            if (stopResult != AAUDIO_OK) {
                LOGW("AAudio stop failed: %d (%s)", stopResult, resultText(stopResult));
            }
        }

        aaudio_result_t closeResult = gApi.streamClose(renderer->stream);
        if (closeResult != AAUDIO_OK) {
            LOGW("AAudio close failed: %d (%s)", closeResult, resultText(closeResult));
        }
        renderer->stream = NULL;
    }

    free(renderer->ring);
    renderer->ring = NULL;
    free(renderer->stretchBuffer);
    renderer->stretchBuffer = NULL;
    free(renderer);
}

JNIEXPORT jlong JNICALL
Java_com_limelight_binding_audio_AndroidAudioRenderer_nativeCreate(
        JNIEnv* env, jclass clazz, jint sampleRate, jint channelCount,
        jint samplesPerFrame, jboolean adaptive) {
    (void)env;
    (void)clazz;

    pthread_once(&gApiOnce, loadAAudioApi);
    if (!gApi.ready || sampleRate <= 0 || channelCount <= 0 || channelCount > 8 ||
        samplesPerFrame <= 0) {
        return 0;
    }

    AAudioRenderer* renderer = (AAudioRenderer*)calloc(1, sizeof(*renderer));
    if (renderer == NULL) {
        return 0;
    }

    renderer->sampleRate = sampleRate;
    renderer->channelCount = channelCount;
    renderer->adaptive = adaptive == JNI_TRUE;
    renderer->minTargetFrames = framesForMs(renderer,
            renderer->adaptive ? MIN_TARGET_MS : FIXED_TARGET_MS);
    renderer->maxTargetFrames = framesForMs(renderer,
            renderer->adaptive ? MAX_TARGET_MS : FIXED_TARGET_MS);
    uint32_t initialTargetFrames = framesForMs(renderer, INITIAL_TARGET_MS);
    renderer->lastArrivalNs = -1;
    renderer->lastTargetDecreaseNs = -1;
    renderer->protectionUntilNs = -1;

    // The ring owns jitter buffering. AAudio itself remains at a two-burst low-latency size.
    // Keep 100 ms of non-blocking producer headroom beyond the selected target.
    uint32_t headroomFrames = (uint32_t)(((int64_t)sampleRate * 100) / 1000);
    renderer->capacityFrames = renderer->maxTargetFrames + headroomFrames;
    if (renderer->capacityFrames < (uint32_t)sampleRate / 10) {
        renderer->capacityFrames = (uint32_t)sampleRate / 10;
    }

    renderer->ring = (int16_t*)calloc((size_t)renderer->capacityFrames * channelCount,
                                      sizeof(int16_t));
    renderer->stretchCapacityFrames = (uint32_t)(((int64_t)samplesPerFrame * 100 + 96) / 97) + 8;
    renderer->stretchBuffer = (int16_t*)calloc(
            (size_t)renderer->stretchCapacityFrames * channelCount, sizeof(int16_t));
    if (renderer->ring == NULL || renderer->stretchBuffer == NULL) {
        // The atomic fields are initialized below, so don't route this early failure
        // through destroyRenderer(), which deliberately performs atomic stores.
        free(renderer->ring);
        free(renderer->stretchBuffer);
        free(renderer);
        return 0;
    }

    atomic_init(&renderer->readFrame, 0);
    atomic_init(&renderer->writeFrame, 0);
    atomic_init(&renderer->targetFrames, initialTargetFrames);
    atomic_init(&renderer->armed, false);
    atomic_init(&renderer->started, false);
    atomic_init(&renderer->closing, false);
    atomic_init(&renderer->underrunCallbacks, 0);
    atomic_init(&renderer->underrunFrames, 0);
    atomic_init(&renderer->droppedFrames, 0);
    atomic_init(&renderer->playbackRatePpm, RATE_ONE_PPM);
    atomic_init(&renderer->jitterMicros, 0);
    atomic_init(&renderer->timeStretchFrameDelta, 0);
    atomic_init(&renderer->lastError, AAUDIO_OK);

    AAudioStreamBuilder* builder = NULL;
    aaudio_result_t result = gApi.createStreamBuilder(&builder);
    if (result != AAUDIO_OK || builder == NULL) {
        LOGE("AAudio builder creation failed: %d (%s)", result, resultText(result));
        destroyRenderer(renderer);
        return 0;
    }

    gApi.builderSetDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    gApi.builderSetSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
    gApi.builderSetPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    gApi.builderSetFormat(builder, AAUDIO_FORMAT_PCM_I16);
    gApi.builderSetSampleRate(builder, sampleRate);
    gApi.builderSetChannelCount(builder, channelCount);
    if (gApi.builderSetUsage != NULL) {
        gApi.builderSetUsage(builder, AAUDIO_USAGE_GAME);
    }
    if (gApi.builderSetContentType != NULL) {
        gApi.builderSetContentType(builder, AAUDIO_CONTENT_TYPE_MUSIC);
    }
    gApi.builderSetDataCallback(builder, dataCallback, renderer);
    gApi.builderSetErrorCallback(builder, errorCallback, renderer);

    result = gApi.builderOpenStream(builder, &renderer->stream);
    gApi.builderDelete(builder);
    if (result != AAUDIO_OK || renderer->stream == NULL) {
        LOGE("AAudio stream open failed: %d (%s)", result, resultText(result));
        destroyRenderer(renderer);
        return 0;
    }

    int32_t actualSampleRate = gApi.streamGetSampleRate(renderer->stream);
    int32_t actualChannelCount = gApi.streamGetChannelCount(renderer->stream);
    aaudio_format_t actualFormat = gApi.streamGetFormat(renderer->stream);
    if (actualSampleRate != sampleRate || actualChannelCount != channelCount ||
        actualFormat != AAUDIO_FORMAT_PCM_I16) {
        LOGW("AAudio format mismatch: requested %d Hz/%d ch/I16, got %d Hz/%d ch/%d",
             sampleRate, channelCount, actualSampleRate, actualChannelCount, actualFormat);
        destroyRenderer(renderer);
        return 0;
    }

    int32_t framesPerBurst = gApi.streamGetFramesPerBurst(renderer->stream);
    if (framesPerBurst > 0) {
        aaudio_result_t bufferResult =
                gApi.streamSetBufferSizeInFrames(renderer->stream, framesPerBurst * 2);
        if (bufferResult < 0) {
            LOGW("Unable to set two-burst AAudio buffer: %d (%s)",
                 bufferResult, resultText(bufferResult));
        }
    }

    LOGI("AAudio opened: %d Hz, %d channels, target=%s, initial=40 ms, capacity=%u frames, burst=%d, mode=%d",
         sampleRate, channelCount, renderer->adaptive ? "20-80 ms adaptive" : "40 ms fixed",
         renderer->capacityFrames, framesPerBurst,
         gApi.streamGetPerformanceMode(renderer->stream));
    return (jlong)(intptr_t)renderer;
}

JNIEXPORT void JNICALL
Java_com_limelight_binding_audio_AndroidAudioRenderer_nativeArm(
        JNIEnv* env, jclass clazz, jlong handle) {
    (void)env;
    (void)clazz;
    AAudioRenderer* renderer = (AAudioRenderer*)(intptr_t)handle;
    if (renderer == NULL) {
        return;
    }

    atomic_store_explicit(&renderer->armed, true, memory_order_release);
    maybeStart(renderer);
}

JNIEXPORT jint JNICALL
Java_com_limelight_binding_audio_AndroidAudioRenderer_nativeWrite(
        JNIEnv* env, jclass clazz, jlong handle, jshortArray audioData) {
    (void)clazz;
    AAudioRenderer* renderer = (AAudioRenderer*)(intptr_t)handle;
    if (renderer == NULL || audioData == NULL) {
        return -1;
    }

    jsize sampleCount = (*env)->GetArrayLength(env, audioData);
    if (sampleCount <= 0 || sampleCount % renderer->channelCount != 0) {
        return -2;
    }

    jshort* samples = (*env)->GetShortArrayElements(env, audioData, NULL);
    if (samples == NULL) {
        return -3;
    }

    uint32_t acceptedFrames = writeFrames(renderer,
                                          (const int16_t*)samples,
                                          (uint32_t)sampleCount / renderer->channelCount);
    (*env)->ReleaseShortArrayElements(env, audioData, samples, JNI_ABORT);
    return (jint)acceptedFrames;
}

JNIEXPORT void JNICALL
Java_com_limelight_binding_audio_AndroidAudioRenderer_nativeGetStats(
        JNIEnv* env, jclass clazz, jlong handle, jlongArray stats) {
    (void)clazz;
    AAudioRenderer* renderer = (AAudioRenderer*)(intptr_t)handle;
    if (renderer == NULL || stats == NULL || (*env)->GetArrayLength(env, stats) < 11) {
        return;
    }

    uint32_t readFrame = atomic_load_explicit(&renderer->readFrame, memory_order_acquire);
    uint32_t writeFrame = atomic_load_explicit(&renderer->writeFrame, memory_order_acquire);
    jlong values[11] = {
            (jlong)(writeFrame - readFrame),
            (jlong)atomic_load_explicit(&renderer->underrunCallbacks, memory_order_relaxed),
            (jlong)atomic_load_explicit(&renderer->underrunFrames, memory_order_relaxed),
            (jlong)atomic_load_explicit(&renderer->droppedFrames, memory_order_relaxed),
            (jlong)gApi.streamGetXRunCount(renderer->stream),
            (jlong)atomic_load_explicit(&renderer->lastError, memory_order_acquire),
            (jlong)atomic_load_explicit(&renderer->targetFrames, memory_order_acquire),
            (jlong)atomic_load_explicit(&renderer->playbackRatePpm, memory_order_relaxed),
            (jlong)atomic_load_explicit(&renderer->jitterMicros, memory_order_relaxed),
            (jlong)atomic_load_explicit(&renderer->timeStretchFrameDelta, memory_order_relaxed),
            (jlong)atomic_load_explicit(&renderer->started, memory_order_acquire),
    };
    (*env)->SetLongArrayRegion(env, stats, 0, 11, values);
}

JNIEXPORT void JNICALL
Java_com_limelight_binding_audio_AndroidAudioRenderer_nativeDestroy(
        JNIEnv* env, jclass clazz, jlong handle) {
    (void)env;
    (void)clazz;
    destroyRenderer((AAudioRenderer*)(intptr_t)handle);
}
