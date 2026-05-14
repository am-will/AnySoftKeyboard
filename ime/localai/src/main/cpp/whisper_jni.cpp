#include <jni.h>
#include <atomic>
#include <algorithm>
#include <cstdint>
#include <string>
#include <thread>
#include <vector>

#include "whisper.h"

namespace {
std::atomic<bool> cancelled{false};

bool should_abort(void*) {
    return cancelled.load();
}

void throw_illegal_state(JNIEnv* env, const char* message) {
    jclass exception_class = env->FindClass("java/lang/IllegalStateException");
    if (exception_class != nullptr) {
        env->ThrowNew(exception_class, message);
    }
}

int whisper_thread_count() {
    const unsigned int cores = std::thread::hardware_concurrency();
    if (cores == 0) {
        return 2;
    }
    return static_cast<int>(std::min<unsigned int>(4, std::max<unsigned int>(2, cores)));
}

std::vector<float> pcm16_to_float(JNIEnv* env, jshortArray pcm16) {
    const jsize sample_count = env->GetArrayLength(pcm16);
    std::vector<jshort> shorts(static_cast<size_t>(sample_count));
    env->GetShortArrayRegion(pcm16, 0, sample_count, shorts.data());

    std::vector<float> floats(static_cast<size_t>(sample_count));
    std::transform(shorts.begin(), shorts.end(), floats.begin(), [](jshort sample) {
        return static_cast<float>(sample) / 32768.0f;
    });
    return floats;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_amwill_keeb_voice_NativeWhisperBridge_nativeCreateContext(
    JNIEnv* env, jobject, jstring model_path) {
    if (model_path == nullptr) {
        return 0L;
    }
    const char* path = env->GetStringUTFChars(model_path, nullptr);
    whisper_context* context = nullptr;
    if (path != nullptr) {
        whisper_context_params params = whisper_context_default_params();
        params.use_gpu = false;
        context = whisper_init_from_file_with_params(path, params);
        env->ReleaseStringUTFChars(model_path, path);
    }
    cancelled.store(false);
    return reinterpret_cast<jlong>(context);
}

extern "C" JNIEXPORT void JNICALL
Java_com_amwill_keeb_voice_NativeWhisperBridge_nativeFreeContext(
    JNIEnv*, jobject, jlong context_handle) {
    auto* context = reinterpret_cast<whisper_context*>(context_handle);
    if (context != nullptr) {
        whisper_free(context);
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_amwill_keeb_voice_NativeWhisperBridge_nativeTranscribe(
    JNIEnv* env, jobject, jlong context_handle, jshortArray pcm16, jint sample_rate) {
    if (context_handle == 0L) {
        throw_illegal_state(env, "Whisper native context is not loaded");
        return nullptr;
    }
    cancelled.store(false);

    auto* context = reinterpret_cast<whisper_context*>(context_handle);
    std::vector<float> samples = pcm16_to_float(env, pcm16);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.language = "en";
    params.n_threads = whisper_thread_count();
    params.offset_ms = 0;
    params.no_context = true;
    params.single_segment = false;
    params.abort_callback = should_abort;
    params.abort_callback_user_data = nullptr;

    if (sample_rate != WHISPER_SAMPLE_RATE) {
        throw_illegal_state(env, "Whisper native transcription requires 16000 Hz PCM audio");
        return nullptr;
    }

    whisper_reset_timings(context);
    if (whisper_full(context, params, samples.data(), static_cast<int>(samples.size())) != 0) {
        if (cancelled.load()) {
            return env->NewStringUTF("");
        }
        throw_illegal_state(env, "Whisper native transcription failed");
        return nullptr;
    }

    std::string transcript;
    const int segment_count = whisper_full_n_segments(context);
    for (int i = 0; i < segment_count; ++i) {
        const char* segment = whisper_full_get_segment_text(context, i);
        if (segment != nullptr) {
            transcript += segment;
        }
    }
    return env->NewStringUTF(transcript.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_amwill_keeb_voice_NativeWhisperBridge_nativeCancel(
    JNIEnv*, jobject, jlong) {
    cancelled.store(true);
}
