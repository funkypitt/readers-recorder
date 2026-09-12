// JNI bridge for Reader's Recorder: load a GGUF language model, turn one prompt into one
// piece of text, report progress, allow a cancel. Deliberately tiny — the model is only ever
// asked for a list of points, so there is nothing to configure and nothing to stream.
//
// Same shape as jni.c beside it: a handle held by Kotlin, global atomics for progress and
// cancel, and no exception ever thrown across the boundary — a failure returns 0 or "", and
// leaves a short reason behind that Kotlin can read and write into the log.
#include <jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <string.h>
#include <stdatomic.h>
#include <stdarg.h>
#include <stdio.h>
#include "llama.h"

#define TAG "ReadersLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static atomic_int  g_progress = 0;     // 0–100 over the whole call
static atomic_bool g_abort = false;
static atomic_bool g_backend_ready = false;
static char        g_error[160] = "";  // why the last call gave nothing

static void fail(const char * fmt, ...) {
    va_list a; va_start(a, fmt);
    vsnprintf(g_error, sizeof(g_error), fmt, a);
    va_end(a);
    LOGE("%s", g_error);
}

struct session {
    struct llama_model   * model;
    struct llama_context * ctx;
    struct llama_sampler * sampler;
    const struct llama_vocab * vocab;
    int n_ctx;
};

/** A growing byte buffer: the answer is a few hundred words, so doubling is plenty. */
struct out {
    char * data;
    size_t len, cap;
};

static int out_push(struct out * o, const char * s, int n) {
    if (n <= 0) return 0;
    if (o->len + (size_t) n + 1 > o->cap) {
        size_t cap = o->cap ? o->cap * 2 : 4096;
        while (o->len + (size_t) n + 1 > cap) cap *= 2;
        char * p = realloc(o->data, cap);
        if (!p) return -1;
        o->data = p; o->cap = cap;
    }
    memcpy(o->data + o->len, s, (size_t) n);
    o->len += (size_t) n;
    o->data[o->len] = 0;
    return 0;
}

/**
 * The prompt as the model was trained to receive it: the instruction wrapped in the model's own
 * chat template, ending where the answer starts. Without this a chat model treats the text as
 * something to continue, and answers beside the question — the reason a summary measured through
 * Ollama on the workstation and the same model on the phone are not the same model at all.
 * Returns a buffer to free, or NULL when the model carries no template (then the raw text is used).
 */
static char * wrap_in_chat_template(struct llama_model * model, const char * text) {
    const char * tmpl = llama_model_chat_template(model, NULL);
    if (!tmpl) return NULL;
    struct llama_chat_message msg = { "user", text };
    int cap = (int) strlen(text) * 2 + 512;
    char * buf = malloc((size_t) cap);
    if (!buf) return NULL;
    int n = llama_chat_apply_template(tmpl, &msg, 1, true, buf, cap);
    if (n > cap) {                                   // the template asked for more room
        char * bigger = realloc(buf, (size_t) n + 1);
        if (!bigger) { free(buf); return NULL; }
        buf = bigger; cap = n + 1;
        n = llama_chat_apply_template(tmpl, &msg, 1, true, buf, cap);
    }
    if (n <= 0) { free(buf); return NULL; }
    buf[n < cap ? n : cap - 1] = 0;
    return buf;
}

JNIEXPORT jlong JNICALL
Java_com_freedomfighter_readersrecorder_summary_LlamaLib_initContext(
        JNIEnv *env, jclass cls, jstring path, jint threads, jint n_ctx) {
    (void) cls;
    if (!atomic_exchange(&g_backend_ready, true)) llama_backend_init();
    g_error[0] = 0;

    const char * p = (*env)->GetStringUTFChars(env, path, NULL);

    struct llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;                 // a phone: the processor does everything
    mp.load_mode    = LLAMA_LOAD_MODE_MMAP;   // the weights stay file-backed, so the system can
                                             // reclaim them instead of killing the application

    struct llama_model * model = llama_model_load_from_file(p, mp);
    (*env)->ReleaseStringUTFChars(env, path, p);
    if (!model) { fail("the model cannot be loaded"); return 0; }

    struct llama_context_params cp = llama_context_default_params();
    cp.n_ctx           = (uint32_t) (n_ctx > 0 ? n_ctx : 4096);
    // Small batches on purpose: the work buffer grows with the batch, and on a phone every
    // hundred megabytes is one more reason for the system to kill the application mid-answer.
    cp.n_batch         = 128;
    cp.n_ubatch        = 128;
    cp.n_threads       = threads > 0 ? threads : 4;
    cp.n_threads_batch = cp.n_threads;
    cp.no_perf         = true;

    struct llama_context * ctx = llama_init_from_model(model, cp);
    if (!ctx) { fail("no room for a context of %d tokens", (int) cp.n_ctx); llama_model_free(model); return 0; }

    // Deterministic on purpose: the same recording always gives the same summary, which makes
    // a wrong summary reproducible instead of a one-off nobody can chase.
    struct llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());

    struct session * s = calloc(1, sizeof(struct session));
    if (!s) { llama_sampler_free(smpl); llama_free(ctx); llama_model_free(model); return 0; }
    s->model = model; s->ctx = ctx; s->sampler = smpl;
    s->vocab = llama_model_get_vocab(model);
    s->n_ctx = (int) llama_n_ctx(ctx);
    LOGI("model loaded, context %d, %d threads", s->n_ctx, cp.n_threads);
    return (jlong) (intptr_t) s;
}

JNIEXPORT void JNICALL
Java_com_freedomfighter_readersrecorder_summary_LlamaLib_freeContext(JNIEnv *env, jclass cls, jlong ptr) {
    (void) env; (void) cls;
    struct session * s = (struct session *) (intptr_t) ptr;
    if (!s) return;
    if (s->sampler) llama_sampler_free(s->sampler);
    if (s->ctx)     llama_free(s->ctx);
    if (s->model)   llama_model_free(s->model);
    free(s);
}

/**
 * One prompt in, one answer out. Returns "" when cancelled, when the prompt does not fit the
 * context, or on any failure: the caller then keeps the transcript and simply has no summary.
 */
JNIEXPORT jstring JNICALL
Java_com_freedomfighter_readersrecorder_summary_LlamaLib_generate(
        JNIEnv *env, jclass cls, jlong ptr, jstring prompt, jint max_tokens) {
    (void) cls;
    struct session * s = (struct session *) (intptr_t) ptr;
    jstring empty = (*env)->NewStringUTF(env, "");
    if (!s) return empty;

    atomic_store(&g_progress, 0);
    atomic_store(&g_abort, false);
    g_error[0] = 0;
    llama_memory_clear(llama_get_memory(s->ctx), true);   // each piece starts from nothing

    const char * text = (*env)->GetStringUTFChars(env, prompt, NULL);
    char * wrapped = wrap_in_chat_template(s->model, text);
    const char * ask = wrapped ? wrapped : text;
    const int ask_len = (int) strlen(ask);

    int cap = -llama_tokenize(s->vocab, ask, ask_len, NULL, 0, true, true);
    if (cap <= 0) cap = ask_len / 2 + 16;
    llama_token * toks = malloc(sizeof(llama_token) * (size_t) cap);
    if (!toks) { free(wrapped); (*env)->ReleaseStringUTFChars(env, prompt, text); return empty; }
    const int n_prompt = llama_tokenize(s->vocab, ask, ask_len, toks, cap, true, true);
    free(wrapped);
    (*env)->ReleaseStringUTFChars(env, prompt, text);
    if (n_prompt <= 0) { free(toks); fail("the text could not be tokenised"); return empty; }

    // Room for the answer as well, else the model would run into the wall mid-sentence.
    if (n_prompt + max_tokens > s->n_ctx) {
        fail("%d tokens to read and %d to write do not fit a context of %d", n_prompt, max_tokens, s->n_ctx);
        free(toks);
        return empty;
    }

    const int batch = 128;
    for (int i = 0; i < n_prompt; i += batch) {
        if (atomic_load(&g_abort)) { free(toks); return empty; }
        int n = n_prompt - i < batch ? n_prompt - i : batch;
        if (llama_decode(s->ctx, llama_batch_get_one(toks + i, n)) != 0) {
            free(toks); fail("reading the text failed at token %d of %d", i, n_prompt); return empty;
        }
        atomic_store(&g_progress, 5 + 25 * (i + n) / n_prompt);   // reading: 5 → 30 %
    }
    free(toks);

    struct out o = {0};
    char piece[512];
    for (int produced = 0; produced < max_tokens; produced++) {
        if (atomic_load(&g_abort)) { free(o.data); return empty; }

        llama_token id = llama_sampler_sample(s->sampler, s->ctx, -1);
        if (llama_vocab_is_eog(s->vocab, id)) break;
        llama_sampler_accept(s->sampler, id);

        int n = llama_token_to_piece(s->vocab, id, piece, (int) sizeof(piece), 0, false);
        if (n < 0) {                                   // never seen with these models, but cheap to survive
            int need = -n;
            char * big = malloc((size_t) need);
            if (big) {
                n = llama_token_to_piece(s->vocab, id, big, need, 0, false);
                if (n > 0) out_push(&o, big, n);
                free(big);
            }
        } else if (out_push(&o, piece, n) != 0) {
            free(o.data); fail("out of memory while writing the answer"); return empty;
        }

        atomic_store(&g_progress, 30 + 70 * (produced + 1) / max_tokens);   // writing: 30 → 100 %

        if (llama_decode(s->ctx, llama_batch_get_one(&id, 1)) != 0) {
            fail("writing stopped at token %d", produced);
            break;                                     // keep what was written rather than lose it
        }
    }
    atomic_store(&g_progress, 100);
    if (!o.data || o.len == 0) fail("the model wrote nothing");

    jstring out = (*env)->NewStringUTF(env, o.data ? o.data : "");
    free(o.data);
    return out ? out : empty;
}

JNIEXPORT void JNICALL
Java_com_freedomfighter_readersrecorder_summary_LlamaLib_cancel(JNIEnv *env, jclass cls) {
    (void) env; (void) cls;
    atomic_store(&g_abort, true);
}

JNIEXPORT jint JNICALL
Java_com_freedomfighter_readersrecorder_summary_LlamaLib_progress(JNIEnv *env, jclass cls) {
    (void) env; (void) cls;
    return atomic_load(&g_progress);
}

/** Why the last call gave nothing, in one short sentence, or "" when nothing went wrong. */
JNIEXPORT jstring JNICALL
Java_com_freedomfighter_readersrecorder_summary_LlamaLib_lastError(JNIEnv *env, jclass cls) {
    (void) cls;
    return (*env)->NewStringUTF(env, g_error);
}
