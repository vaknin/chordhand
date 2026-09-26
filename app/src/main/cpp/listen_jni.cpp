// Transcribes a recording with whisper.cpp and returns every word with the time it is sung.
#include <jni.h>
#include <android/log.h>
#include <string>
#include "whisper.h"

#define TAG "chordhand-listen"

namespace {

struct Progress {
    JNIEnv * env;
    jobject listener;
    jmethodID onProgress;
};

void on_progress(whisper_context *, whisper_state *, int percent, void * user) {
    auto * p = static_cast<Progress *>(user);
    p->env->CallVoidMethod(p->listener, p->onProgress, percent);
}

}  // namespace

/**
 * Returns one line per word, "startMs<TAB>word", as UTF-8 (a token can end inside a
 * character, so it is decoded on the Kotlin side), or null when the model cannot be loaded or
 * decoding fails. Times come from DTW over the alignment heads of base.en, which mark about
 * where a token ends; the caller corrects for that.
 */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_kivan_chordhand_data_listen_WhisperNative_transcribe(
        JNIEnv * env, jobject, jstring modelPath, jfloatArray pcm, jstring prompt, jint threads, jobject listener) {
    const char * path = env->GetStringUTFChars(modelPath, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    cparams.flash_attn = false;  // DTW timestamps need the attention weights.
    cparams.dtw_token_timestamps = true;
    cparams.dtw_aheads_preset = WHISPER_AHEADS_BASE_EN;
    whisper_context * ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);
    if (ctx == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "could not load the model");
        return nullptr;
    }

    // Beam search, as whisper-cli does by default. Greedy is about 30 s faster per song on a
    // Pixel 8 but, over sung words, now and then loses a whole 30 s window.
    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_BEAM_SEARCH);
    params.beam_search.beam_size = 5;
    params.greedy.best_of = 5;
    params.n_threads = threads;
    params.language = "en";
    params.translate = false;
    params.token_timestamps = true;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.print_special = false;
    const char * promptChars = env->GetStringUTFChars(prompt, nullptr);
    std::string promptText(promptChars);
    env->ReleaseStringUTFChars(prompt, promptChars);
    // The known lyrics, before every 30 s window: whisper then hears the words that are sung.
    params.initial_prompt = promptText.c_str();
    params.carry_initial_prompt = true;

    Progress progress{env, listener, env->GetMethodID(env->GetObjectClass(listener), "onProgress", "(I)V")};
    params.progress_callback = on_progress;
    params.progress_callback_user_data = &progress;

    jsize n = env->GetArrayLength(pcm);
    jfloat * samples = env->GetFloatArrayElements(pcm, nullptr);
    int rc = whisper_full(ctx, params, samples, n);
    env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);
    if (rc != 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "whisper_full failed: %d", rc);
        whisper_free(ctx);
        return nullptr;
    }

    // Tokens starting with a space start a word; the rest continue the one before.
    std::string out;
    std::string word;
    long long wordMs = -1;
    auto flush = [&]() {
        if (!word.empty() && wordMs >= 0) out += std::to_string(wordMs) + "\t" + word + "\n";
        word.clear();
        wordMs = -1;
    };
    const whisper_token eot = whisper_token_eot(ctx);
    for (int s = 0; s < whisper_full_n_segments(ctx); s++) {
        for (int t = 0; t < whisper_full_n_tokens(ctx, s); t++) {
            whisper_token_data data = whisper_full_get_token_data(ctx, s, t);
            if (data.id >= eot) continue;
            std::string text = whisper_full_get_token_text(ctx, s, t);
            // DTW time is in 10 ms steps; fall back to the segment-level start without it.
            long long ms = data.t_dtw >= 0 ? data.t_dtw * 10 : data.t0 * 10;
            if (!text.empty() && text[0] == ' ') {
                flush();
                word = text.substr(1);
                wordMs = ms;
            } else {
                if (word.empty()) wordMs = ms;
                word += text;
            }
        }
        flush();
    }
    whisper_free(ctx);
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(out.size()));
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(out.size()), reinterpret_cast<const jbyte *>(out.data()));
    return bytes;
}
