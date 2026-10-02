#include <aaudio/AAudio.h>
#include <android/log.h>
#include <dlfcn.h>
#include <errno.h>
#include <jni.h>
#include <pthread.h>
#include <semaphore.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "aaudio_renderer.h"
#include "audio_buffer_policy.h"

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
    aaudio_stream_state_t (*streamGetState)(AAudioStream* stream);
    int32_t (*streamGetBufferSizeInFrames)(AAudioStream* stream);
    int32_t (*streamGetBufferCapacityInFrames)(AAudioStream* stream);
    int32_t (*streamGetFramesPerBurst)(AAudioStream* stream);
    int32_t (*streamGetXRunCount)(AAudioStream* stream);
    int32_t (*streamGetSampleRate)(AAudioStream* stream);
    int32_t (*streamGetChannelCount)(AAudioStream* stream);
    aaudio_format_t (*streamGetFormat)(AAudioStream* stream);
    aaudio_performance_mode_t (*streamGetPerformanceMode)(AAudioStream* stream);
    aaudio_sharing_mode_t (*streamGetSharingMode)(AAudioStream* stream);
    const char* (*convertResultToText)(aaudio_result_t result);
    bool ready;
} AAudioApi;

#define DIAGNOSTIC_EVENT_CAPACITY 128
#define DIAGNOSTIC_EVENT_VALUE_COUNT 7
#define DIAGNOSTIC_EVENT_WORDS (2 + DIAGNOSTIC_EVENT_VALUE_COUNT)

typedef struct {
    atomic_uint publishedSequence;
    int32_t type;
    int64_t elapsedRealtimeNanos;
    int64_t values[DIAGNOSTIC_EVENT_VALUE_COUNT];
} AudioDiagnosticEvent;

typedef struct {
    AAudioStream* stream;
    int16_t* ring;
    uint32_t capacityFrames;
    atomic_uint steadyCapacityFrames;
    AudioBufferPolicy bufferPolicy;
    uint32_t xrunCheckCallbacks;
    int32_t lastXrun;
    uint32_t fixedTargetMs;
    int32_t sampleRate;
    int32_t channelCount;
    bool adaptive;
    bool diagnosticsEnabled;
    int32_t framesPerBurst;
    atomic_int bufferSizeFrames;
    int32_t bufferCapacityFrames;
    int32_t performanceMode;
    int32_t sharingMode;
    int64_t lastDeliveryNs;
    uint32_t previousDeliveryFrames;
    atomic_uint readFrame;
    atomic_uint writeFrame;
    atomic_uint targetFrames;
    atomic_bool armed;
    atomic_bool started;
    atomic_bool primed;
    atomic_bool startupComplete;
    atomic_bool closing;
    atomic_bool recoveryRequested;
    atomic_bool recovering;
    atomic_uint underrunCallbacks;
    atomic_uint underrunFrames;
    atomic_uint droppedFrames;
    atomic_uint crossfadeDrops;
    atomic_uint averageDepthFrames;
    atomic_ullong crossfadeDroppedFrames;
    atomic_uint rebufferCount;
    atomic_uint hardTrimCount;
    atomic_uint hardwareBufferGrowthCount;
    atomic_int lastError;
    atomic_int streamState;
    atomic_ullong armTimeNs;
    atomic_ullong startRequestTimeNs;
    atomic_ullong firstCallbackTimeNs;
    int64_t lastCallbackTimeNs;
    atomic_uint callbackCount;
    atomic_uint maxCallbackGapMicros;
    atomic_uint deliveryGapEvents;
    atomic_uint diagnosticEventsDropped;
    atomic_uint diagnosticEventWriteSequence;
    atomic_uint diagnosticEventReadSequence;
    AudioDiagnosticEvent diagnosticEvents[DIAGNOSTIC_EVENT_CAPACITY];
    pthread_mutex_t streamMutex;
    sem_t recoverySemaphore;
    pthread_t recoveryThread;
    bool streamMutexInitialized;
    bool recoverySemaphoreInitialized;
    bool recoveryThreadStarted;
} AAudioRenderer;

enum {
    MIN_FIXED_TARGET_MS = 40,
    MAX_FIXED_TARGET_MS = 120,
    RING_HEADROOM_MS = 100,
    STARTUP_CAPACITY_MS = 2000,
    DIAGNOSTIC_EVENT_STREAM_OPENED = 1,
    DIAGNOSTIC_EVENT_ARMED = 2,
    DIAGNOSTIC_EVENT_START_REQUESTED = 3,
    DIAGNOSTIC_EVENT_FIRST_CALLBACK = 4,
    DIAGNOSTIC_EVENT_UNDERRUN = 5,
    DIAGNOSTIC_EVENT_RING_OVERFLOW = 6,
    DIAGNOSTIC_EVENT_START_FAILED = 7,
    DIAGNOSTIC_EVENT_STREAM_ERROR = 8,
    DIAGNOSTIC_EVENT_DELIVERY_GAP = 9,
    DIAGNOSTIC_EVENT_PRIMED = 10,
    DIAGNOSTIC_EVENT_REBUFFERING = 11,
    DIAGNOSTIC_EVENT_CROSSFADE_DROP = 12,
    NATIVE_STATS_COUNT = 24,
};

static AAudioApi gApi;
static pthread_once_t gApiOnce = PTHREAD_ONCE_INIT;
static pthread_mutex_t gDirectRendererMutex = PTHREAD_MUTEX_INITIALIZER;
static AAudioRenderer* gDirectRenderer;

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
    LOAD_REQUIRED(streamGetState, "AAudioStream_getState");
    LOAD_REQUIRED(streamGetBufferSizeInFrames, "AAudioStream_getBufferSizeInFrames");
    LOAD_REQUIRED(streamGetBufferCapacityInFrames, "AAudioStream_getBufferCapacityInFrames");
    LOAD_REQUIRED(streamGetFramesPerBurst, "AAudioStream_getFramesPerBurst");
    LOAD_REQUIRED(streamGetXRunCount, "AAudioStream_getXRunCount");
    LOAD_REQUIRED(streamGetSampleRate, "AAudioStream_getSampleRate");
    LOAD_REQUIRED(streamGetChannelCount, "AAudioStream_getChannelCount");
    LOAD_REQUIRED(streamGetFormat, "AAudioStream_getFormat");
    LOAD_REQUIRED(streamGetPerformanceMode, "AAudioStream_getPerformanceMode");
    LOAD_REQUIRED(streamGetSharingMode, "AAudioStream_getSharingMode");
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

// Matches android.os.SystemClock.elapsedRealtimeNanos(), including time spent suspended.
static int64_t elapsedRealtimeNs(void) {
    struct timespec now;
    clock_gettime(CLOCK_BOOTTIME, &now);
    return (int64_t)now.tv_sec * 1000000000LL + now.tv_nsec;
}

static void updateAtomicMaximum(atomic_uint* maximum, uint32_t value) {
    uint32_t observed = atomic_load_explicit(maximum, memory_order_relaxed);
    while (value > observed &&
           !atomic_compare_exchange_weak_explicit(maximum,
                                                  &observed,
                                                  value,
                                                  memory_order_relaxed,
                                                  memory_order_relaxed)) {
    }
}

static void enqueueDiagnosticEvent(AAudioRenderer* renderer,
                                   int32_t type,
                                   int64_t eventElapsedRealtimeNanos,
                                   int64_t value0,
                                   int64_t value1,
                                   int64_t value2,
                                   int64_t value3,
                                   int64_t value4,
                                   int64_t value5,
                                   int64_t value6) {
    if (!renderer->diagnosticsEnabled) {
        return;
    }

    uint32_t writeSequence = atomic_load_explicit(
            &renderer->diagnosticEventWriteSequence, memory_order_relaxed);
    for (;;) {
        uint32_t readSequence = atomic_load_explicit(
                &renderer->diagnosticEventReadSequence, memory_order_acquire);
        if ((uint32_t)(writeSequence - readSequence) >= DIAGNOSTIC_EVENT_CAPACITY) {
            atomic_fetch_add_explicit(&renderer->diagnosticEventsDropped, 1,
                                      memory_order_relaxed);
            return;
        }

        if (atomic_compare_exchange_weak_explicit(
                    &renderer->diagnosticEventWriteSequence,
                    &writeSequence,
                    writeSequence + 1,
                    memory_order_acq_rel,
                    memory_order_relaxed)) {
            break;
        }
    }

    AudioDiagnosticEvent* event =
            &renderer->diagnosticEvents[writeSequence % DIAGNOSTIC_EVENT_CAPACITY];
    event->type = type;
    event->elapsedRealtimeNanos = eventElapsedRealtimeNanos;
    event->values[0] = value0;
    event->values[1] = value1;
    event->values[2] = value2;
    event->values[3] = value3;
    event->values[4] = value4;
    event->values[5] = value5;
    event->values[6] = value6;
    atomic_store_explicit(&event->publishedSequence, writeSequence + 1,
                          memory_order_release);
}

static uint32_t framesForMs(const AAudioRenderer* renderer, uint32_t milliseconds) {
    return (uint32_t)(((int64_t)renderer->sampleRate * milliseconds) / 1000);
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

static void growHardwareBuffer(AAudioRenderer* renderer, AAudioStream* stream) {
    if (!renderer->adaptive || ++renderer->xrunCheckCallbacks % 128 != 0) return;
    int32_t xruns = gApi.streamGetXRunCount(stream);
    if (xruns <= renderer->lastXrun) return;
    renderer->lastXrun = xruns;
    int32_t burst = gApi.streamGetFramesPerBurst(stream);
    int32_t current = gApi.streamGetBufferSizeInFrames(stream);
    int32_t capacity = gApi.streamGetBufferCapacityInFrames(stream);
    int32_t grown = current + (burst > 0 ? burst : 1);
    if (grown > capacity) grown = capacity;
    if (grown <= current) return;
    int32_t result = gApi.streamSetBufferSizeInFrames(stream, grown);
    if (result > current) {
        atomic_store_explicit(&renderer->bufferSizeFrames, result, memory_order_relaxed);
        atomic_fetch_add_explicit(&renderer->hardwareBufferGrowthCount, 1, memory_order_relaxed);
    }
}

static aaudio_data_callback_result_t dataCallback(AAudioStream* stream, void* userData,
                                                   void* audioData, int32_t numFrames) {
    AAudioRenderer* renderer = (AAudioRenderer*)userData;
    int16_t* output = (int16_t*)audioData;

    if (renderer == NULL || numFrames <= 0 || atomic_load_explicit(&renderer->closing, memory_order_acquire)) {
        if (renderer != NULL && numFrames > 0) {
            memset(output, 0, (size_t)numFrames * renderer->channelCount * sizeof(int16_t));
        }
        return AAUDIO_CALLBACK_RESULT_STOP;
    }

    growHardwareBuffer(renderer, stream);
    uint32_t readFrame = atomic_load_explicit(&renderer->readFrame, memory_order_relaxed);
    uint32_t writeFrame = atomic_load_explicit(&renderer->writeFrame, memory_order_acquire);
    uint32_t availableFrames = writeFrame - readFrame;
    uint32_t requestedFrames = (uint32_t)numFrames;
    int64_t callbackTimeNs = 0;
    uint32_t callbackGapMicros = 0;
    uint32_t callbackNumber = 0;

    if (renderer->diagnosticsEnabled) {
        callbackTimeNs = elapsedRealtimeNs();
        if (renderer->lastCallbackTimeNs > 0) {
            int64_t gapMicros = (callbackTimeNs - renderer->lastCallbackTimeNs) / 1000;
            if (gapMicros > UINT32_MAX) {
                gapMicros = UINT32_MAX;
            }
            if (gapMicros > 0) {
                callbackGapMicros = (uint32_t)gapMicros;
                updateAtomicMaximum(&renderer->maxCallbackGapMicros, callbackGapMicros);
            }
        }
        renderer->lastCallbackTimeNs = callbackTimeNs;
        callbackNumber = atomic_fetch_add_explicit(&renderer->callbackCount, 1,
                                                   memory_order_relaxed) + 1;

        unsigned long long expectedFirstCallback = 0;
        if (atomic_compare_exchange_strong_explicit(
                    &renderer->firstCallbackTimeNs,
                    &expectedFirstCallback,
                    (unsigned long long)callbackTimeNs,
                    memory_order_acq_rel,
                    memory_order_relaxed)) {
            unsigned long long startRequestTimeNs = atomic_load_explicit(
                    &renderer->startRequestTimeNs, memory_order_acquire);
            unsigned long long armTimeNs = atomic_load_explicit(
                    &renderer->armTimeNs, memory_order_acquire);
            int64_t requestDelayMicros = startRequestTimeNs == 0 ? -1 :
                    (callbackTimeNs - (int64_t)startRequestTimeNs) / 1000;
            int64_t armDelayMicros = armTimeNs == 0 ? -1 :
                    (callbackTimeNs - (int64_t)armTimeNs) / 1000;
            atomic_store_explicit(&renderer->streamState, AAUDIO_STREAM_STATE_STARTED,
                                  memory_order_release);
            enqueueDiagnosticEvent(renderer,
                                   DIAGNOSTIC_EVENT_FIRST_CALLBACK,
                                   callbackTimeNs,
                                   requestedFrames,
                                   availableFrames,
                                   requestDelayMicros,
                                   armDelayMicros,
                                   callbackNumber,
                                   AAUDIO_STREAM_STATE_STARTED,
                                   0);
        }
    }

    if (renderer->adaptive) {
        AudioBufferPolicy* policy = &renderer->bufferPolicy;
        // Large device quanta need a quantum plus a packet even when above the 90 ms target.
        uint32_t quantumCapacity = requestedFrames * 2 + policy->packetFrames;
        uint32_t budget = atomic_load_explicit(&renderer->steadyCapacityFrames, memory_order_relaxed);
        if (quantumCapacity > budget) {
            atomic_store_explicit(&renderer->steadyCapacityFrames,
                    quantumCapacity < renderer->capacityFrames ? quantumCapacity : renderer->capacityFrames,
                    memory_order_relaxed);
        }
        uint32_t discardedStartupFrames = 0;
        if (!atomic_load_explicit(&renderer->startupComplete, memory_order_acquire)) {
            uint32_t startupTarget = AudioBufferPolicyTarget(policy, requestedFrames);
            if (availableFrames >= startupTarget) {
                // Trim device-route warm-up backlog only once, never on a re-prime.
                discardedStartupFrames = availableFrames - startupTarget;
                readFrame += discardedStartupFrames;
                availableFrames = startupTarget;
            }
        }
        bool wasPrimed = policy->primed;
        AudioBufferStep step = AudioBufferPolicyNext(policy, availableFrames, requestedFrames);
        if (step.dropFrames > 0) {
            AudioBufferCrossfadeDrop(renderer->ring, renderer->capacityFrames,
                    renderer->channelCount, readFrame, step.dropFrames, step.fadeFrames);
            readFrame += step.dropFrames;
            availableFrames -= step.dropFrames;
            atomic_fetch_add_explicit(&renderer->crossfadeDrops, 1, memory_order_relaxed);
            atomic_fetch_add_explicit(&renderer->crossfadeDroppedFrames, step.dropFrames,
                                      memory_order_relaxed);
            if (step.hardTrim) atomic_fetch_add_explicit(&renderer->hardTrimCount, 1, memory_order_relaxed);
            enqueueDiagnosticEvent(renderer, DIAGNOSTIC_EVENT_CROSSFADE_DROP, callbackTimeNs,
                    step.dropFrames, step.fadeFrames, availableFrames, step.targetFrames,
                    step.hardTrim, requestedFrames, 0);
        }
        atomic_store_explicit(&renderer->readFrame, readFrame, memory_order_release);
        atomic_store_explicit(&renderer->targetFrames, step.targetFrames, memory_order_release);
        atomic_store_explicit(&renderer->averageDepthFrames, (uint32_t)policy->averageFrames,
                              memory_order_relaxed);
        atomic_store_explicit(&renderer->primed, policy->primed, memory_order_release);
        if (step.silence) {
            memset(output, 0, (size_t)requestedFrames * renderer->channelCount * sizeof(int16_t));
            return AAUDIO_CALLBACK_RESULT_CONTINUE;
        }
        atomic_store_explicit(&renderer->startupComplete, true, memory_order_release);
        if (!wasPrimed) {
            enqueueDiagnosticEvent(renderer, DIAGNOSTIC_EVENT_PRIMED, callbackTimeNs,
                    availableFrames + discardedStartupFrames, step.targetFrames,
                    discardedStartupFrames, requestedFrames, callbackNumber,
                    callbackGapMicros, renderer->capacityFrames);
        }
    }

    // Fixed mode retains its one-time startup priming.
    // Start AAudio as soon as the renderer is armed so device-route warm-up happens before
    // useful PCM playback. Until the initial target is available, callbacks emit silence
    // without consuming the ring or reporting expected startup underruns. If the device paused
    // its callback during warm-up, discard stale startup PCM and begin from the newest target.
    if (!renderer->adaptive && !atomic_load_explicit(&renderer->primed, memory_order_acquire)) {
        uint32_t targetFrames = atomic_load_explicit(&renderer->targetFrames,
                                                     memory_order_acquire);
        if (availableFrames < targetFrames) {
            memset(output, 0,
                   (size_t)requestedFrames * renderer->channelCount * sizeof(int16_t));
            return AAUDIO_CALLBACK_RESULT_CONTINUE;
        }

        uint32_t discardedFrames = availableFrames - targetFrames;
        if (discardedFrames > 0) {
            readFrame += discardedFrames;
            availableFrames = targetFrames;
            atomic_store_explicit(&renderer->readFrame, readFrame, memory_order_release);
        }
        atomic_store_explicit(&renderer->primed, true, memory_order_release);
        atomic_store_explicit(&renderer->startupComplete, true, memory_order_release);
        enqueueDiagnosticEvent(renderer,
                               DIAGNOSTIC_EVENT_PRIMED,
                               callbackTimeNs,
                               availableFrames + discardedFrames,
                               targetFrames,
                               discardedFrames,
                               requestedFrames,
                               callbackNumber,
                               callbackGapMicros,
                               renderer->capacityFrames);
    }

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
        uint32_t totalUnderrunCallbacks = atomic_fetch_add_explicit(
                &renderer->underrunCallbacks, 1, memory_order_relaxed) + 1;
        atomic_fetch_add_explicit(&renderer->underrunFrames, missingFrames, memory_order_relaxed);
        enqueueDiagnosticEvent(renderer,
                               DIAGNOSTIC_EVENT_UNDERRUN,
                               callbackTimeNs,
                               availableFrames,
                               requestedFrames,
                               missingFrames,
                               atomic_load_explicit(&renderer->targetFrames,
                                                    memory_order_relaxed),
                               callbackGapMicros,
                               totalUnderrunCallbacks,
                               atomic_load_explicit(&renderer->streamState,
                                                    memory_order_relaxed));
    }

    if (renderer->adaptive) {
        AudioBufferPolicyRead(&renderer->bufferPolicy, copiedFrames < requestedFrames);
        bool primed = renderer->bufferPolicy.primed;
        atomic_store_explicit(&renderer->primed, primed, memory_order_release);
        uint32_t target = AudioBufferPolicyTarget(&renderer->bufferPolicy, requestedFrames);
        atomic_store_explicit(&renderer->targetFrames, target, memory_order_release);
        if (!primed) {
            uint32_t count = atomic_fetch_add_explicit(&renderer->rebufferCount, 1,
                                                       memory_order_relaxed) + 1;
            enqueueDiagnosticEvent(renderer, DIAGNOSTIC_EVENT_REBUFFERING, callbackTimeNs,
                    availableFrames, requestedFrames, target, count, 0, 0, 0);
        }
    }
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

static void errorCallback(AAudioStream* stream, void* userData, aaudio_result_t error) {
    (void)stream;
    AAudioRenderer* renderer = (AAudioRenderer*)userData;
    if (renderer != NULL) {
        atomic_store_explicit(&renderer->lastError, error, memory_order_release);
        atomic_store_explicit(&renderer->streamState, AAUDIO_STREAM_STATE_DISCONNECTED,
                              memory_order_release);
        enqueueDiagnosticEvent(renderer,
                               DIAGNOSTIC_EVENT_STREAM_ERROR,
                               renderer->diagnosticsEnabled ? elapsedRealtimeNs() : 0,
                               error,
                               AAUDIO_STREAM_STATE_DISCONNECTED,
                               0, 0, 0, 0, 0);

        // AAudio forbids closing or reopening a disconnected stream from its callback thread.
        // Wake the renderer-owned worker instead. Coalescing repeated callbacks also avoids
        // racing multiple recovery attempts against the same stream.
        if (!atomic_load_explicit(&renderer->closing, memory_order_acquire)) {
            bool expected = false;
            if (atomic_compare_exchange_strong_explicit(
                        &renderer->recoveryRequested,
                        &expected,
                        true,
                        memory_order_acq_rel,
                        memory_order_acquire)) {
                sem_post(&renderer->recoverySemaphore);
            }
        }
    }
    LOGE("AAudio stream error: %d (%s)", error, resultText(error));
}

static void maybeStart(AAudioRenderer* renderer) {
    if (!atomic_load_explicit(&renderer->armed, memory_order_acquire) ||
        atomic_load_explicit(&renderer->closing, memory_order_acquire) ||
        atomic_load_explicit(&renderer->recoveryRequested, memory_order_acquire) ||
        atomic_load_explicit(&renderer->recovering, memory_order_acquire) ||
        atomic_load_explicit(&renderer->streamState, memory_order_acquire) ==
                AAUDIO_STREAM_STATE_DISCONNECTED) {
        return;
    }

    pthread_mutex_lock(&renderer->streamMutex);
    if (renderer->stream == NULL ||
        !atomic_load_explicit(&renderer->armed, memory_order_acquire) ||
        atomic_load_explicit(&renderer->closing, memory_order_acquire) ||
        atomic_load_explicit(&renderer->recoveryRequested, memory_order_acquire) ||
        atomic_load_explicit(&renderer->recovering, memory_order_acquire) ||
        atomic_load_explicit(&renderer->streamState, memory_order_acquire) ==
                AAUDIO_STREAM_STATE_DISCONNECTED) {
        pthread_mutex_unlock(&renderer->streamMutex);
        return;
    }

    uint32_t readFrame = atomic_load_explicit(&renderer->readFrame, memory_order_acquire);
    uint32_t writeFrame = atomic_load_explicit(&renderer->writeFrame, memory_order_acquire);
    uint32_t targetFrames = atomic_load_explicit(&renderer->targetFrames, memory_order_acquire);

    bool expected = false;
    if (!atomic_compare_exchange_strong_explicit(&renderer->started, &expected, true,
                                                  memory_order_acq_rel, memory_order_acquire)) {
        pthread_mutex_unlock(&renderer->streamMutex);
        return;
    }

    int64_t requestTimeNs = renderer->diagnosticsEnabled ? elapsedRealtimeNs() : 0;
    if (renderer->diagnosticsEnabled) {
        atomic_store_explicit(&renderer->startRequestTimeNs,
                              (unsigned long long)requestTimeNs,
                              memory_order_release);
    }
    atomic_store_explicit(&renderer->streamState, AAUDIO_STREAM_STATE_STARTING,
                          memory_order_release);
    enqueueDiagnosticEvent(renderer,
                           DIAGNOSTIC_EVENT_START_REQUESTED,
                           requestTimeNs,
                           writeFrame - readFrame,
                           targetFrames,
                           renderer->framesPerBurst,
                           renderer->bufferSizeFrames,
                           AAUDIO_STREAM_STATE_STARTING,
                           0,
                           0);

    aaudio_result_t result = gApi.streamRequestStart(renderer->stream);
    if (result != AAUDIO_OK) {
        atomic_store_explicit(&renderer->started, false, memory_order_release);
        atomic_store_explicit(&renderer->lastError, result, memory_order_release);
        atomic_store_explicit(&renderer->streamState, gApi.streamGetState(renderer->stream),
                              memory_order_release);
        enqueueDiagnosticEvent(renderer,
                               DIAGNOSTIC_EVENT_START_FAILED,
                               renderer->diagnosticsEnabled ? elapsedRealtimeNs() : 0,
                               result,
                               writeFrame - readFrame,
                               targetFrames,
                               atomic_load_explicit(&renderer->streamState,
                                                    memory_order_relaxed),
                               0, 0, 0);
        LOGE("AAudio start failed: %d (%s)", result, resultText(result));
    }
    else {
        LOGI("AAudio startup warm-up began with %u queued frames", writeFrame - readFrame);
    }
    pthread_mutex_unlock(&renderer->streamMutex);
}

static void closeStreamLocked(AAudioRenderer* renderer) {
    AAudioStream* stream = renderer->stream;
    if (stream == NULL) {
        return;
    }

    renderer->stream = NULL;
    bool wasStarted = atomic_exchange_explicit(&renderer->started, false,
                                                memory_order_acq_rel);
    if (wasStarted) {
        aaudio_result_t stopResult = gApi.streamRequestStop(stream);
        if (stopResult != AAUDIO_OK) {
            LOGW("AAudio stop failed: %d (%s)", stopResult, resultText(stopResult));
        }
    }

    aaudio_result_t closeResult = gApi.streamClose(stream);
    if (closeResult != AAUDIO_OK) {
        LOGW("AAudio close failed: %d (%s)", closeResult, resultText(closeResult));
    }
    atomic_store_explicit(&renderer->streamState, AAUDIO_STREAM_STATE_UNINITIALIZED,
                          memory_order_release);
}

static aaudio_result_t openStreamLocked(AAudioRenderer* renderer) {
    AAudioStreamBuilder* builder = NULL;
    aaudio_result_t result = gApi.createStreamBuilder(&builder);
    if (result != AAUDIO_OK || builder == NULL) {
        LOGE("AAudio builder creation failed: %d (%s)", result, resultText(result));
        return result != AAUDIO_OK ? result : AAUDIO_ERROR_INTERNAL;
    }

    gApi.builderSetDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    gApi.builderSetSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
    gApi.builderSetPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    gApi.builderSetFormat(builder, AAUDIO_FORMAT_PCM_I16);
    gApi.builderSetSampleRate(builder, renderer->sampleRate);
    gApi.builderSetChannelCount(builder, renderer->channelCount);
    if (gApi.builderSetUsage != NULL) {
        gApi.builderSetUsage(builder, AAUDIO_USAGE_GAME);
    }
    if (gApi.builderSetContentType != NULL) {
        gApi.builderSetContentType(builder, AAUDIO_CONTENT_TYPE_MUSIC);
    }
    gApi.builderSetDataCallback(builder, dataCallback, renderer);
    gApi.builderSetErrorCallback(builder, errorCallback, renderer);

    AAudioStream* stream = NULL;
    result = gApi.builderOpenStream(builder, &stream);
    gApi.builderDelete(builder);
    if (result != AAUDIO_OK || stream == NULL) {
        LOGE("AAudio stream open failed: %d (%s)", result, resultText(result));
        if (stream != NULL) {
            gApi.streamClose(stream);
        }
        return result != AAUDIO_OK ? result : AAUDIO_ERROR_INTERNAL;
    }

    int32_t actualSampleRate = gApi.streamGetSampleRate(stream);
    int32_t actualChannelCount = gApi.streamGetChannelCount(stream);
    aaudio_format_t actualFormat = gApi.streamGetFormat(stream);
    if (actualSampleRate != renderer->sampleRate ||
        actualChannelCount != renderer->channelCount ||
        actualFormat != AAUDIO_FORMAT_PCM_I16) {
        LOGW("AAudio format mismatch: requested %d Hz/%d ch/I16, got %d Hz/%d ch/%d",
             renderer->sampleRate, renderer->channelCount,
             actualSampleRate, actualChannelCount, actualFormat);
        gApi.streamClose(stream);
        return AAUDIO_ERROR_INVALID_FORMAT;
    }

    renderer->framesPerBurst = gApi.streamGetFramesPerBurst(stream);
    if (renderer->framesPerBurst > 0) {
        aaudio_result_t bufferResult =
                gApi.streamSetBufferSizeInFrames(stream, renderer->framesPerBurst * (renderer->adaptive ? 3 : 2));
        if (bufferResult < 0) {
            LOGW("Unable to set initial AAudio buffer: %d (%s)",
                 bufferResult, resultText(bufferResult));
        }
    }
    renderer->bufferSizeFrames = gApi.streamGetBufferSizeInFrames(stream);
    renderer->bufferCapacityFrames = gApi.streamGetBufferCapacityInFrames(stream);
    renderer->performanceMode = gApi.streamGetPerformanceMode(stream);
    renderer->sharingMode = gApi.streamGetSharingMode(stream);
    renderer->stream = stream;
    atomic_store_explicit(&renderer->streamState, gApi.streamGetState(stream),
                          memory_order_release);

    enqueueDiagnosticEvent(renderer,
                           DIAGNOSTIC_EVENT_STREAM_OPENED,
                           renderer->diagnosticsEnabled ? elapsedRealtimeNs() : 0,
                           renderer->sampleRate,
                           renderer->channelCount,
                           renderer->framesPerBurst,
                           renderer->bufferSizeFrames,
                           renderer->bufferCapacityFrames,
                           renderer->performanceMode,
                           renderer->sharingMode);

    if (renderer->adaptive) {
        LOGI("AAudio opened: %d Hz, %d channels, adaptive crossfade buffer, base=25 ms, initial=40 ms, max target=90 ms, steady capacity=%u frames, startup capacity=%u frames, burst=%d, mode=%d",
             renderer->sampleRate, renderer->channelCount,
             renderer->steadyCapacityFrames, renderer->capacityFrames,
             renderer->framesPerBurst, renderer->performanceMode);
    }
    else {
        LOGI("AAudio opened: %d Hz, %d channels, target=%u ms fixed, initial=%u ms, steady capacity=%u frames, startup capacity=%u frames, burst=%d, mode=%d",
             renderer->sampleRate, renderer->channelCount,
             renderer->fixedTargetMs, renderer->fixedTargetMs,
             renderer->steadyCapacityFrames, renderer->capacityFrames,
             renderer->framesPerBurst, renderer->performanceMode);
    }
    return AAUDIO_OK;
}

static void resetAfterDisconnect(AAudioRenderer* renderer) {
    // Audio queued for the old route is stale. Start the replacement stream from packets
    // delivered after recovery and require the normal startup target before becoming audible.
    uint32_t writeFrame = atomic_load_explicit(&renderer->writeFrame, memory_order_acquire);
    atomic_store_explicit(&renderer->readFrame, writeFrame, memory_order_release);
    atomic_store_explicit(&renderer->started, false, memory_order_release);
    atomic_store_explicit(&renderer->primed, false, memory_order_release);
    atomic_store_explicit(&renderer->firstCallbackTimeNs, 0, memory_order_release);
    atomic_store_explicit(&renderer->startRequestTimeNs, 0, memory_order_release);
    AudioBufferPolicyInit(&renderer->bufferPolicy, renderer->bufferPolicy.sampleRate,
                          renderer->bufferPolicy.packetFrames);
    atomic_store_explicit(&renderer->targetFrames, renderer->adaptive ?
            framesForMs(renderer, AUDIO_BUFFER_INITIAL_MS) : framesForMs(renderer, renderer->fixedTargetMs),
            memory_order_release);
    atomic_store_explicit(&renderer->startupComplete, false, memory_order_release);
    atomic_store_explicit(&renderer->averageDepthFrames, 0, memory_order_relaxed);
    renderer->xrunCheckCallbacks = 0;
    renderer->lastXrun = 0;
    renderer->lastCallbackTimeNs = 0;
}

static void sleepBeforeRecoveryRetry(uint32_t attempt) {
    uint32_t delayMs = 100U << (attempt < 4 ? attempt : 3);
    struct timespec delay = {
            .tv_sec = delayMs / 1000,
            .tv_nsec = (long)(delayMs % 1000) * 1000000L,
    };
    while (nanosleep(&delay, &delay) != 0 && errno == EINTR) {
    }
}

static void* recoveryThreadMain(void* opaque) {
    AAudioRenderer* renderer = (AAudioRenderer*)opaque;
    for (;;) {
        int waitResult;
        do {
            waitResult = sem_wait(&renderer->recoverySemaphore);
        } while (waitResult != 0 && errno == EINTR);

        if (atomic_load_explicit(&renderer->closing, memory_order_acquire)) {
            break;
        }
        if (!atomic_exchange_explicit(&renderer->recoveryRequested, false,
                                      memory_order_acq_rel)) {
            continue;
        }

        atomic_store_explicit(&renderer->recovering, true, memory_order_release);
        uint32_t attempt = 0;
        while (!atomic_load_explicit(&renderer->closing, memory_order_acquire)) {
            pthread_mutex_lock(&renderer->streamMutex);
            closeStreamLocked(renderer);
            resetAfterDisconnect(renderer);
            aaudio_result_t result = openStreamLocked(renderer);
            pthread_mutex_unlock(&renderer->streamMutex);

            if (result == AAUDIO_OK) {
                atomic_store_explicit(&renderer->lastError, AAUDIO_OK,
                                      memory_order_release);
                atomic_store_explicit(&renderer->recovering, false, memory_order_release);
                LOGI("AAudio stream recovered after %u attempt(s)", attempt + 1);
                maybeStart(renderer);
                break;
            }

            attempt++;
            atomic_store_explicit(&renderer->lastError, result, memory_order_release);
            atomic_store_explicit(&renderer->streamState, AAUDIO_STREAM_STATE_DISCONNECTED,
                                  memory_order_release);
            LOGW("AAudio recovery attempt %u failed: %d (%s)",
                 attempt, result, resultText(result));
            sleepBeforeRecoveryRetry(attempt - 1);
        }
        atomic_store_explicit(&renderer->recovering, false, memory_order_release);
    }
    return NULL;
}

static uint32_t writeFrames(AAudioRenderer* renderer, const int16_t* samples, uint32_t frames) {
    if (frames == 0 || atomic_load_explicit(&renderer->closing, memory_order_acquire)) {
        return 0;
    }

    if (renderer->diagnosticsEnabled) {
        int64_t nowNs = elapsedRealtimeNs();
        if (renderer->lastDeliveryNs > 0 && renderer->previousDeliveryFrames > 0) {
            int64_t gapUs = (nowNs - renderer->lastDeliveryNs) / 1000;
            int64_t expectedUs =
                    (int64_t)renderer->previousDeliveryFrames * 1000000 / renderer->sampleRate;
            int64_t thresholdUs = expectedUs * 3;
            if (thresholdUs < 50000) {
                thresholdUs = 50000;
            }
            if (gapUs > thresholdUs) {
                uint32_t totalEvents = atomic_fetch_add_explicit(
                        &renderer->deliveryGapEvents, 1, memory_order_relaxed) + 1;
                enqueueDiagnosticEvent(renderer,
                                       DIAGNOSTIC_EVENT_DELIVERY_GAP,
                                       nowNs,
                                       gapUs,
                                       expectedUs,
                                       thresholdUs,
                                       totalEvents,
                                       renderer->previousDeliveryFrames,
                                       frames,
                                       0);
            }
        }
        renderer->lastDeliveryNs = nowNs;
        renderer->previousDeliveryFrames = frames;
    }

    uint32_t readFrame = atomic_load_explicit(&renderer->readFrame, memory_order_acquire);
    uint32_t writeFrame = atomic_load_explicit(&renderer->writeFrame, memory_order_relaxed);
    uint32_t queuedFrames = writeFrame - readFrame;
    // PCM is queued bit-for-bit; only the consumer may discard/crossfade old audio.
    bool startupComplete = atomic_load_explicit(&renderer->startupComplete, memory_order_acquire);
    uint32_t adjustedFrames = frames;
    uint32_t queueLimitFrames = startupComplete ?
            atomic_load_explicit(&renderer->steadyCapacityFrames, memory_order_relaxed) :
            renderer->capacityFrames;
    uint32_t freeFrames = queuedFrames < queueLimitFrames ? queueLimitFrames - queuedFrames : 0;
    uint32_t acceptedFrames = frames < freeFrames ? frames : freeFrames;

    if (acceptedFrames > 0) {
        copyToRing(renderer, samples, writeFrame, acceptedFrames);
        atomic_store_explicit(&renderer->writeFrame, writeFrame + acceptedFrames, memory_order_release);
    }

    if (acceptedFrames < adjustedFrames) {
        uint32_t droppedFrames = adjustedFrames - acceptedFrames;
        atomic_fetch_add_explicit(&renderer->droppedFrames,
                                  droppedFrames,
                                  memory_order_relaxed);
        enqueueDiagnosticEvent(renderer,
                               DIAGNOSTIC_EVENT_RING_OVERFLOW,
                               renderer->diagnosticsEnabled ? elapsedRealtimeNs() : 0,
                               frames,
                               adjustedFrames,
                               acceptedFrames,
                               droppedFrames,
                               queuedFrames,
                               queueLimitFrames,
                               atomic_load_explicit(&renderer->started,
                                                    memory_order_relaxed));
    }

    maybeStart(renderer);
    return acceptedFrames;
}

bool ArtemisAaudioRendererIsActive(void) {
    pthread_mutex_lock(&gDirectRendererMutex);
    bool active = gDirectRenderer != NULL &&
            !atomic_load_explicit(&gDirectRenderer->closing, memory_order_acquire);
    pthread_mutex_unlock(&gDirectRendererMutex);
    return active;
}

int32_t ArtemisAaudioRendererWriteDecoded(const int16_t* samples,
                                          uint32_t frames,
                                          int32_t channelCount) {
    if (samples == NULL || frames == 0) {
        return -1;
    }

    pthread_mutex_lock(&gDirectRendererMutex);
    AAudioRenderer* renderer = gDirectRenderer;
    if (renderer == NULL || renderer->channelCount != channelCount ||
            atomic_load_explicit(&renderer->closing, memory_order_acquire)) {
        pthread_mutex_unlock(&gDirectRendererMutex);
        return -2;
    }
    uint32_t acceptedFrames = writeFrames(renderer, samples, frames);
    pthread_mutex_unlock(&gDirectRendererMutex);
    return (int32_t)acceptedFrames;
}

static void destroyRenderer(AAudioRenderer* renderer) {
    if (renderer == NULL) {
        return;
    }

    atomic_store_explicit(&renderer->closing, true, memory_order_release);
    atomic_store_explicit(&renderer->armed, false, memory_order_release);

    if (renderer->recoveryThreadStarted) {
        sem_post(&renderer->recoverySemaphore);
        pthread_join(renderer->recoveryThread, NULL);
        renderer->recoveryThreadStarted = false;
    }

    if (renderer->streamMutexInitialized) {
        pthread_mutex_lock(&renderer->streamMutex);
        closeStreamLocked(renderer);
        pthread_mutex_unlock(&renderer->streamMutex);
    }
    if (renderer->recoverySemaphoreInitialized) {
        sem_destroy(&renderer->recoverySemaphore);
        renderer->recoverySemaphoreInitialized = false;
    }
    if (renderer->streamMutexInitialized) {
        pthread_mutex_destroy(&renderer->streamMutex);
        renderer->streamMutexInitialized = false;
    }

    free(renderer->ring);
    renderer->ring = NULL;
    free(renderer);
}

JNIEXPORT jlong JNICALL
Java_com_limelight_binding_audio_AndroidAudioRenderer_nativeCreate(
        JNIEnv* env, jclass clazz, jint sampleRate, jint channelCount,
        jint samplesPerFrame, jint fixedTargetMs, jboolean adaptive,
        jboolean diagnosticsEnabled) {
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
    renderer->diagnosticsEnabled = diagnosticsEnabled == JNI_TRUE;
    if (fixedTargetMs < MIN_FIXED_TARGET_MS) {
        fixedTargetMs = MIN_FIXED_TARGET_MS;
    }
    else if (fixedTargetMs > MAX_FIXED_TARGET_MS) {
        fixedTargetMs = MAX_FIXED_TARGET_MS;
    }
    renderer->fixedTargetMs = (uint32_t)fixedTargetMs;
    AudioBufferPolicyInit(&renderer->bufferPolicy, (uint32_t)sampleRate,
                          (uint32_t)samplesPerFrame);
    uint32_t initialTargetFrames = framesForMs(renderer,
            renderer->adaptive ? AUDIO_BUFFER_INITIAL_MS : (uint32_t)fixedTargetMs);
    uint32_t steadyCapacityFrames = renderer->adaptive ?
            framesForMs(renderer, AUDIO_BUFFER_HARD_CAP_MS) + (uint32_t)samplesPerFrame :
            framesForMs(renderer, (uint32_t)fixedTargetMs + RING_HEADROOM_MS);
    renderer->capacityFrames = framesForMs(renderer, STARTUP_CAPACITY_MS);
    if (renderer->capacityFrames < steadyCapacityFrames) renderer->capacityFrames = steadyCapacityFrames;
    renderer->ring = (int16_t*)calloc((size_t)renderer->capacityFrames * channelCount,
                                      sizeof(int16_t));
    if (renderer->ring == NULL) {
        free(renderer);
        return 0;
    }
    atomic_init(&renderer->steadyCapacityFrames, steadyCapacityFrames);
    atomic_init(&renderer->bufferSizeFrames, 0);
    atomic_init(&renderer->readFrame, 0);
    atomic_init(&renderer->writeFrame, 0);
    atomic_init(&renderer->targetFrames, initialTargetFrames);
    atomic_init(&renderer->armed, false);
    atomic_init(&renderer->started, false);
    atomic_init(&renderer->primed, false);
    atomic_init(&renderer->startupComplete, false);
    atomic_init(&renderer->closing, false);
    atomic_init(&renderer->recoveryRequested, false);
    atomic_init(&renderer->recovering, false);
    atomic_init(&renderer->underrunCallbacks, 0);
    atomic_init(&renderer->underrunFrames, 0);
    atomic_init(&renderer->droppedFrames, 0);
    atomic_init(&renderer->crossfadeDrops, 0);
    atomic_init(&renderer->averageDepthFrames, 0);
    atomic_init(&renderer->crossfadeDroppedFrames, 0);
    atomic_init(&renderer->rebufferCount, 0);
    atomic_init(&renderer->hardTrimCount, 0);
    atomic_init(&renderer->hardwareBufferGrowthCount, 0);
    atomic_init(&renderer->lastError, AAUDIO_OK);
    atomic_init(&renderer->streamState, AAUDIO_STREAM_STATE_UNINITIALIZED);
    atomic_init(&renderer->armTimeNs, 0);
    atomic_init(&renderer->startRequestTimeNs, 0);
    atomic_init(&renderer->firstCallbackTimeNs, 0);
    atomic_init(&renderer->callbackCount, 0);
    atomic_init(&renderer->maxCallbackGapMicros, 0);
    atomic_init(&renderer->deliveryGapEvents, 0);
    atomic_init(&renderer->diagnosticEventsDropped, 0);
    atomic_init(&renderer->diagnosticEventWriteSequence, 0);
    atomic_init(&renderer->diagnosticEventReadSequence, 0);
    for (uint32_t i = 0; i < DIAGNOSTIC_EVENT_CAPACITY; i++) {
        atomic_init(&renderer->diagnosticEvents[i].publishedSequence, 0);
    }

    if (pthread_mutex_init(&renderer->streamMutex, NULL) != 0) {
        free(renderer->ring);
        free(renderer);
        return 0;
    }
    renderer->streamMutexInitialized = true;
    if (sem_init(&renderer->recoverySemaphore, 0, 0) != 0) {
        destroyRenderer(renderer);
        return 0;
    }
    renderer->recoverySemaphoreInitialized = true;

    pthread_mutex_lock(&renderer->streamMutex);
    aaudio_result_t result = openStreamLocked(renderer);
    pthread_mutex_unlock(&renderer->streamMutex);
    if (result != AAUDIO_OK) {
        destroyRenderer(renderer);
        return 0;
    }

    int threadResult = pthread_create(&renderer->recoveryThread, NULL,
                                      recoveryThreadMain, renderer);
    if (threadResult != 0) {
        LOGE("Unable to start AAudio recovery thread: %d", threadResult);
        destroyRenderer(renderer);
        return 0;
    }
    renderer->recoveryThreadStarted = true;
    pthread_mutex_lock(&gDirectRendererMutex);
    gDirectRenderer = renderer;
    pthread_mutex_unlock(&gDirectRendererMutex);
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

    int64_t armTimeNs = renderer->diagnosticsEnabled ? elapsedRealtimeNs() : 0;
    if (renderer->diagnosticsEnabled) {
        atomic_store_explicit(&renderer->armTimeNs,
                              (unsigned long long)armTimeNs,
                              memory_order_release);
    }
    atomic_store_explicit(&renderer->armed, true, memory_order_release);
    uint32_t readFrame = atomic_load_explicit(&renderer->readFrame, memory_order_acquire);
    uint32_t writeFrame = atomic_load_explicit(&renderer->writeFrame, memory_order_acquire);
    enqueueDiagnosticEvent(renderer,
                           DIAGNOSTIC_EVENT_ARMED,
                           armTimeNs,
                           writeFrame - readFrame,
                           atomic_load_explicit(&renderer->targetFrames,
                                                memory_order_relaxed),
                           atomic_load_explicit(&renderer->streamState,
                                                memory_order_relaxed),
                           0, 0, 0, 0);
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
    if (renderer == NULL || stats == NULL ||
        (*env)->GetArrayLength(env, stats) < NATIVE_STATS_COUNT) {
        return;
    }

    uint32_t readFrame = atomic_load_explicit(&renderer->readFrame, memory_order_acquire);
    uint32_t writeFrame = atomic_load_explicit(&renderer->writeFrame, memory_order_acquire);
    aaudio_stream_state_t streamState = (aaudio_stream_state_t)atomic_load_explicit(
            &renderer->streamState, memory_order_acquire);
    int32_t xRunCount = 0;
    if (pthread_mutex_trylock(&renderer->streamMutex) == 0) {
        if (renderer->stream != NULL) {
            streamState = gApi.streamGetState(renderer->stream);
            xRunCount = gApi.streamGetXRunCount(renderer->stream);
            atomic_store_explicit(&renderer->streamState, streamState, memory_order_release);
        }
        pthread_mutex_unlock(&renderer->streamMutex);
    }
    jlong values[NATIVE_STATS_COUNT] = {
            (jlong)(writeFrame - readFrame),
            (jlong)atomic_load_explicit(&renderer->underrunCallbacks, memory_order_relaxed),
            (jlong)atomic_load_explicit(&renderer->underrunFrames, memory_order_relaxed),
            (jlong)atomic_load_explicit(&renderer->droppedFrames, memory_order_relaxed),
            (jlong)xRunCount,
            (jlong)atomic_load_explicit(&renderer->lastError, memory_order_acquire),
            (jlong)atomic_load_explicit(&renderer->targetFrames, memory_order_acquire),
            (jlong)atomic_load_explicit(&renderer->crossfadeDrops, memory_order_relaxed),
            (jlong)atomic_load_explicit(&renderer->averageDepthFrames, memory_order_relaxed),
            (jlong)atomic_load_explicit(&renderer->crossfadeDroppedFrames, memory_order_relaxed),
            (jlong)atomic_load_explicit(&renderer->started, memory_order_acquire),
            (jlong)atomic_load_explicit(&renderer->callbackCount, memory_order_relaxed),
            (jlong)atomic_load_explicit(&renderer->diagnosticEventsDropped,
                                        memory_order_relaxed),
            (jlong)atomic_load_explicit(&renderer->maxCallbackGapMicros,
                                        memory_order_relaxed),
            (jlong)renderer->steadyCapacityFrames,
            (jlong)renderer->framesPerBurst,
            (jlong)renderer->bufferSizeFrames,
            (jlong)renderer->bufferCapacityFrames,
            (jlong)streamState,
            (jlong)atomic_load_explicit(&renderer->armed, memory_order_acquire),
            (jlong)atomic_load_explicit(&renderer->primed, memory_order_acquire),
            (jlong)atomic_load_explicit(&renderer->rebufferCount, memory_order_relaxed),
            (jlong)atomic_load_explicit(&renderer->hardTrimCount, memory_order_relaxed),
            (jlong)atomic_load_explicit(&renderer->hardwareBufferGrowthCount, memory_order_relaxed),
    };
    (*env)->SetLongArrayRegion(env, stats, 0, NATIVE_STATS_COUNT, values);
}

JNIEXPORT jint JNICALL
Java_com_limelight_binding_audio_AndroidAudioRenderer_nativeDrainDiagnosticEvents(
        JNIEnv* env, jclass clazz, jlong handle, jlongArray output) {
    (void)clazz;
    AAudioRenderer* renderer = (AAudioRenderer*)(intptr_t)handle;
    if (renderer == NULL || output == NULL) {
        return 0;
    }

    jsize outputLength = (*env)->GetArrayLength(env, output);
    uint32_t outputCapacity = (uint32_t)(outputLength / DIAGNOSTIC_EVENT_WORDS);
    if (outputCapacity == 0) {
        return 0;
    }

    jlong* values = (*env)->GetLongArrayElements(env, output, NULL);
    if (values == NULL) {
        return 0;
    }

    uint32_t readSequence = atomic_load_explicit(
            &renderer->diagnosticEventReadSequence, memory_order_relaxed);
    uint32_t writeSequence = atomic_load_explicit(
            &renderer->diagnosticEventWriteSequence, memory_order_acquire);
    uint32_t eventCount = 0;
    while (readSequence != writeSequence && eventCount < outputCapacity) {
        AudioDiagnosticEvent* event =
                &renderer->diagnosticEvents[readSequence % DIAGNOSTIC_EVENT_CAPACITY];
        uint32_t publishedSequence = atomic_load_explicit(
                &event->publishedSequence, memory_order_acquire);
        if (publishedSequence != readSequence + 1) {
            // A producer reserved this position but has not published it yet.
            break;
        }

        size_t outputOffset = (size_t)eventCount * DIAGNOSTIC_EVENT_WORDS;
        values[outputOffset] = event->type;
        values[outputOffset + 1] = event->elapsedRealtimeNanos;
        for (uint32_t i = 0; i < DIAGNOSTIC_EVENT_VALUE_COUNT; i++) {
            values[outputOffset + 2 + i] = event->values[i];
        }

        readSequence++;
        eventCount++;
    }

    atomic_store_explicit(&renderer->diagnosticEventReadSequence, readSequence,
                          memory_order_release);
    (*env)->ReleaseLongArrayElements(env, output, values, 0);
    return (jint)eventCount;
}

JNIEXPORT void JNICALL
Java_com_limelight_binding_audio_AndroidAudioRenderer_nativeDestroy(
        JNIEnv* env, jclass clazz, jlong handle) {
    (void)env;
    (void)clazz;
    AAudioRenderer* renderer = (AAudioRenderer*)(intptr_t)handle;
    pthread_mutex_lock(&gDirectRendererMutex);
    if (gDirectRenderer == renderer) {
        gDirectRenderer = NULL;
    }
    destroyRenderer(renderer);
    pthread_mutex_unlock(&gDirectRendererMutex);
}
