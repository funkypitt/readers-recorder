// JNI bridge for Reader's Recorder: load a ggml model, transcribe 16 kHz mono float PCM,
// read the segments back, report progress, allow a cancel. Adapted from whisper.cpp's
// Android example (MIT).
#include <jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <string.h>
#include <stdatomic.h>
#include "whisper.h"

#define TAG "ReadersWhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

static atomic_int g_progress = 0;
static atomic_bool g_abort = false;

static void on_progress(struct whisper_context * ctx, struct whisper_state * state, int progress, void * user) {
    (void) ctx; (void) state; (void) user;
    atomic_store(&g_progress, progress);
}

static bool on_abort(void * user) {
    (void) user;
    return atomic_load(&g_abort);
}

JNIEXPORT jlong JNICALL
Java_com_freedomfighter_readersrecorder_whisper_WhisperLib_initContext(JNIEnv *env, jclass cls, jstring path) {
    (void) cls;
    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    struct whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    struct whisper_context *ctx = whisper_init_from_file_with_params(p, cp);
    (*env)->ReleaseStringUTFChars(env, path, p);
    return (jlong) ctx;
}

JNIEXPORT void JNICALL
Java_com_freedomfighter_readersrecorder_whisper_WhisperLib_freeContext(JNIEnv *env, jclass cls, jlong ptr) {
    (void) env; (void) cls;
    if (ptr) whisper_free((struct whisper_context *) ptr);
}

/** Returns 0 on success, -1 on failure, 1 when cancelled. */
JNIEXPORT jint JNICALL
Java_com_freedomfighter_readersrecorder_whisper_WhisperLib_fullTranscribe(JNIEnv *env, jclass cls, jlong ptr, jint threads, jstring language, jstring prompt, jfloatArray audio) {
    (void) cls;
    struct whisper_context *ctx = (struct whisper_context *) ptr;
    if (!ctx) return -1;
    jfloat *data = (*env)->GetFloatArrayElements(env, audio, NULL);
    const jsize n = (*env)->GetArrayLength(env, audio);
    const char *lang = language ? (*env)->GetStringUTFChars(env, language, NULL) : NULL;
    const char *hint = prompt ? (*env)->GetStringUTFChars(env, prompt, NULL) : NULL;

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.language = (lang && lang[0]) ? lang : "auto";
    params.detect_language = false;
    params.n_threads = threads;
    params.no_context = false;
    params.single_segment = false;
    params.suppress_blank = true;
    params.suppress_nst = true;
    params.progress_callback = on_progress;
    params.abort_callback = on_abort;
    // A well-punctuated prompt: Whisper imitates its style (sentences, commas, capitals).
    if (hint && hint[0]) params.initial_prompt = hint;

    atomic_store(&g_progress, 0);
    atomic_store(&g_abort, false);
    int rc = whisper_full(ctx, params, data, n);
    (*env)->ReleaseFloatArrayElements(env, audio, data, JNI_ABORT);
    if (lang) (*env)->ReleaseStringUTFChars(env, language, lang);
    if (hint) (*env)->ReleaseStringUTFChars(env, prompt, hint);
    if (atomic_load(&g_abort)) return 1;
    return rc == 0 ? 0 : -1;
}

JNIEXPORT void JNICALL
Java_com_freedomfighter_readersrecorder_whisper_WhisperLib_cancel(JNIEnv *env, jclass cls) {
    (void) env; (void) cls;
    atomic_store(&g_abort, true);
}

JNIEXPORT jint JNICALL
Java_com_freedomfighter_readersrecorder_whisper_WhisperLib_progress(JNIEnv *env, jclass cls) {
    (void) env; (void) cls;
    return atomic_load(&g_progress);
}

JNIEXPORT jint JNICALL
Java_com_freedomfighter_readersrecorder_whisper_WhisperLib_segmentCount(JNIEnv *env, jclass cls, jlong ptr) {
    (void) env; (void) cls;
    return whisper_full_n_segments((struct whisper_context *) ptr);
}

JNIEXPORT jstring JNICALL
Java_com_freedomfighter_readersrecorder_whisper_WhisperLib_segmentText(JNIEnv *env, jclass cls, jlong ptr, jint i) {
    (void) cls;
    return (*env)->NewStringUTF(env, whisper_full_get_segment_text((struct whisper_context *) ptr, i));
}

JNIEXPORT jlong JNICALL
Java_com_freedomfighter_readersrecorder_whisper_WhisperLib_segmentT0(JNIEnv *env, jclass cls, jlong ptr, jint i) {
    (void) env; (void) cls;
    return whisper_full_get_segment_t0((struct whisper_context *) ptr, i);
}

JNIEXPORT jlong JNICALL
Java_com_freedomfighter_readersrecorder_whisper_WhisperLib_segmentT1(JNIEnv *env, jclass cls, jlong ptr, jint i) {
    (void) env; (void) cls;
    return whisper_full_get_segment_t1((struct whisper_context *) ptr, i);
}

JNIEXPORT jstring JNICALL
Java_com_freedomfighter_readersrecorder_whisper_WhisperLib_detectedLanguage(JNIEnv *env, jclass cls, jlong ptr) {
    (void) cls;
    int id = whisper_full_lang_id((struct whisper_context *) ptr);
    const char *s = id >= 0 ? whisper_lang_str(id) : "";
    return (*env)->NewStringUTF(env, s ? s : "");
}

JNIEXPORT jstring JNICALL
Java_com_freedomfighter_readersrecorder_whisper_WhisperLib_systemInfo(JNIEnv *env, jclass cls) {
    (void) cls;
    return (*env)->NewStringUTF(env, whisper_print_system_info());
}
