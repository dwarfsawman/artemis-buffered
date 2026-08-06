#include <aaudio/AAudio.h>
#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

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
    uint32_t capacityFrames;
    uint32_t targetFrames;
    int32_t sampleRate;
    int32_t channelCount;
    atomic_uint readFrame;
    atomic_uint writeFrame;
    atomic_bool armed;
    atomic_bool started;
    atomic_bool closing;
    atomic_uint underrunCallbacks;
    atomic_uint underrunFrames;
    atomic_uint droppedFrames;
    atomic_int lastError;
} AAudioRenderer;

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
    if (writeFrame - readFrame < renderer->targetFrames) {
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

static uint32_t writeFrames(AAudioRenderer* renderer, const int16_t* samples, uint32_t frames) {
    if (frames == 0 || atomic_load_explicit(&renderer->closing, memory_order_acquire)) {
        return 0;
    }

    uint32_t readFrame = atomic_load_explicit(&renderer->readFrame, memory_order_acquire);
    uint32_t writeFrame = atomic_load_explicit(&renderer->writeFrame, memory_order_relaxed);
    uint32_t queuedFrames = writeFrame - readFrame;
    uint32_t freeFrames = queuedFrames < renderer->capacityFrames ?
            renderer->capacityFrames - queuedFrames : 0;
    uint32_t acceptedFrames = frames < freeFrames ? frames : freeFrames;

    if (acceptedFrames > 0) {
        copyToRing(renderer, samples, writeFrame, acceptedFrames);
        atomic_store_explicit(&renderer->writeFrame, writeFrame + acceptedFrames, memory_order_release);
    }

    if (acceptedFrames < frames) {
        atomic_fetch_add_explicit(&renderer->droppedFrames,
                                  frames - acceptedFrames,
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
    free(renderer);
}

JNIEXPORT jlong JNICALL
Java_com_limelight_binding_audio_AndroidAudioRenderer_nativeCreate(
        JNIEnv* env, jclass clazz, jint sampleRate, jint channelCount, jint bufferMs) {
    (void)env;
    (void)clazz;

    pthread_once(&gApiOnce, loadAAudioApi);
    if (!gApi.ready || sampleRate <= 0 || channelCount <= 0 || channelCount > 8 ||
        bufferMs < 0 || bufferMs > 500) {
        return 0;
    }

    AAudioRenderer* renderer = (AAudioRenderer*)calloc(1, sizeof(*renderer));
    if (renderer == NULL) {
        return 0;
    }

    renderer->sampleRate = sampleRate;
    renderer->channelCount = channelCount;
    renderer->targetFrames = bufferMs == 0 ? 1U :
            (uint32_t)(((int64_t)sampleRate * bufferMs) / 1000);

    // The ring owns jitter buffering. AAudio itself remains at a two-burst low-latency size.
    // Keep 100 ms of non-blocking producer headroom beyond the selected target.
    uint32_t headroomFrames = (uint32_t)(((int64_t)sampleRate * 100) / 1000);
    renderer->capacityFrames = renderer->targetFrames + headroomFrames;
    if (renderer->capacityFrames < (uint32_t)sampleRate / 10) {
        renderer->capacityFrames = (uint32_t)sampleRate / 10;
    }

    renderer->ring = (int16_t*)calloc((size_t)renderer->capacityFrames * channelCount,
                                      sizeof(int16_t));
    if (renderer->ring == NULL) {
        // The atomic fields are initialized below, so don't route this early failure
        // through destroyRenderer(), which deliberately performs atomic stores.
        free(renderer);
        return 0;
    }

    atomic_init(&renderer->readFrame, 0);
    atomic_init(&renderer->writeFrame, 0);
    atomic_init(&renderer->armed, false);
    atomic_init(&renderer->started, false);
    atomic_init(&renderer->closing, false);
    atomic_init(&renderer->underrunCallbacks, 0);
    atomic_init(&renderer->underrunFrames, 0);
    atomic_init(&renderer->droppedFrames, 0);
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

    LOGI("AAudio opened: %d Hz, %d channels, target=%d ms, capacity=%u frames, burst=%d, mode=%d",
         sampleRate, channelCount, bufferMs, renderer->capacityFrames, framesPerBurst,
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
    if (renderer == NULL || stats == NULL || (*env)->GetArrayLength(env, stats) < 6) {
        return;
    }

    uint32_t readFrame = atomic_load_explicit(&renderer->readFrame, memory_order_acquire);
    uint32_t writeFrame = atomic_load_explicit(&renderer->writeFrame, memory_order_acquire);
    jlong values[6] = {
            (jlong)(writeFrame - readFrame),
            (jlong)atomic_load_explicit(&renderer->underrunCallbacks, memory_order_relaxed),
            (jlong)atomic_load_explicit(&renderer->underrunFrames, memory_order_relaxed),
            (jlong)atomic_load_explicit(&renderer->droppedFrames, memory_order_relaxed),
            (jlong)gApi.streamGetXRunCount(renderer->stream),
            (jlong)atomic_load_explicit(&renderer->lastError, memory_order_acquire),
    };
    (*env)->SetLongArrayRegion(env, stats, 0, 6, values);
}

JNIEXPORT void JNICALL
Java_com_limelight_binding_audio_AndroidAudioRenderer_nativeDestroy(
        JNIEnv* env, jclass clazz, jlong handle) {
    (void)env;
    (void)clazz;
    destroyRenderer((AAudioRenderer*)(intptr_t)handle);
}
