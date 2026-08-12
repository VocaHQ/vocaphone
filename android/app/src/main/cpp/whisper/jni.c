#include <jni.h>
#include <android/log.h>
#include "whisper.h"

#define TAG "VocaPhoneWhisper"
#define UNUSED(value) (void)(value)

JNIEXPORT jlong JNICALL
Java_com_vocahq_vocaphone_local_WhisperLib_00024Companion_initContext(
        JNIEnv *env, jobject thiz, jstring model_path) {
    UNUSED(thiz);
    const char *path = (*env)->GetStringUTFChars(env, model_path, NULL);
    __android_log_print(ANDROID_LOG_INFO, TAG, "Loading model from %s", path);
    struct whisper_context *context = whisper_init_from_file_with_params(
            path, whisper_context_default_params());
    (*env)->ReleaseStringUTFChars(env, model_path, path);
    return (jlong) context;
}

JNIEXPORT void JNICALL
Java_com_vocahq_vocaphone_local_WhisperLib_00024Companion_freeContext(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    if (context_ptr != 0) whisper_free((struct whisper_context *) context_ptr);
}

JNIEXPORT void JNICALL
Java_com_vocahq_vocaphone_local_WhisperLib_00024Companion_fullTranscribe(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint num_threads,
        jfloatArray audio_data, jstring language_str, jint beam_size,
        jboolean temperature_fallback, jstring prompt_str) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    jfloat *audio = (*env)->GetFloatArrayElements(env, audio_data, NULL);
    jsize length = (*env)->GetArrayLength(env, audio_data);
    const char *language = (*env)->GetStringUTFChars(env, language_str, NULL);
    const char *prompt = prompt_str == NULL
            ? NULL
            : (*env)->GetStringUTFChars(env, prompt_str, NULL);

    // Beam search is what the Accurate setting buys; every other setting decodes
    // greedily, which is roughly twice as fast for slightly worse text.
    struct whisper_full_params params = whisper_full_default_params(
            beam_size > 0 ? WHISPER_SAMPLING_BEAM_SEARCH : WHISPER_SAMPLING_GREEDY);
    if (beam_size > 0) {
        params.beam_search.beam_size = beam_size;
    }
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.language = language;
    params.n_threads = num_threads;
    params.no_context = true;
    params.single_segment = false;
    // Stops the decoder emitting the `[MUSIC]`-style annotations at the source,
    // rather than stripping them from the text afterwards where a marker the
    // sanitizer has not seen before gets through.
    params.suppress_nst = true;
    // Zero disables the retry of a window whose result looks degenerate. That
    // retry is what breaks whisper out of a repetition loop, so only the Fast
    // setting gives it up.
    params.temperature_inc = temperature_fallback ? 0.2f : 0.0f;

    if (prompt != NULL && prompt[0] != '\0') {
        params.initial_prompt = prompt;
        // Without this the vocabulary reaches only the first 30-second window,
        // because whisper otherwise treats the prompt as rolling context that
        // each decoded window overwrites.
        params.carry_initial_prompt = true;
    }

    whisper_reset_timings(context);
    whisper_full(context, params, audio, length);

    if (prompt != NULL) {
        (*env)->ReleaseStringUTFChars(env, prompt_str, prompt);
    }
    (*env)->ReleaseStringUTFChars(env, language_str, language);
    (*env)->ReleaseFloatArrayElements(env, audio_data, audio, JNI_ABORT);
}

JNIEXPORT jint JNICALL
Java_com_vocahq_vocaphone_local_WhisperLib_00024Companion_getTextSegmentCount(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    return whisper_full_n_segments((struct whisper_context *) context_ptr);
}

JNIEXPORT jstring JNICALL
Java_com_vocahq_vocaphone_local_WhisperLib_00024Companion_getTextSegment(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(thiz);
    const char *text = whisper_full_get_segment_text(
            (struct whisper_context *) context_ptr, index);
    return (*env)->NewStringUTF(env, text == NULL ? "" : text);
}

/**
 * The language whisper actually decoded, which is the only place "auto" turns
 * into something a caller can act on. Empty when the model reported nothing.
 */
JNIEXPORT jstring JNICALL
Java_com_vocahq_vocaphone_local_WhisperLib_00024Companion_getDetectedLanguage(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    UNUSED(thiz);
    const int id = whisper_full_lang_id((struct whisper_context *) context_ptr);
    if (id < 0) return (*env)->NewStringUTF(env, "");
    const char *code = whisper_lang_str(id);
    return (*env)->NewStringUTF(env, code == NULL ? "" : code);
}

JNIEXPORT jstring JNICALL
Java_com_vocahq_vocaphone_local_WhisperLib_00024Companion_getSystemInfo(
        JNIEnv *env, jobject thiz) {
    UNUSED(thiz);
    return (*env)->NewStringUTF(env, whisper_print_system_info());
}
