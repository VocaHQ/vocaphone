package com.vocahq.vocaphone.local

import android.util.Log

internal class WhisperLib {
    companion object {
        init {
            var loaded = false
            for (library in listOf("whisper_v8fp16_va", "whisper_vfpv4", "whisper")) {
                if (loaded) break
                try {
                    System.loadLibrary(library)
                    loaded = true
                    Log.d("VocaPhoneWhisper", "Loaded $library")
                } catch (_: UnsatisfiedLinkError) {
                    // Try the next CPU-specific build.
                }
            }
            check(loaded) { "No VocaPhone whisper native library could be loaded" }
        }

        external fun initContext(modelPath: String): Long
        external fun freeContext(contextPtr: Long)
        /**
         * @param beamSize a beam search width, or zero for greedy sampling.
         * @param prompt vocabulary to bias the decoder toward; empty for none.
         * @return zero on success, or whisper's own negative status on failure.
         */
        external fun fullTranscribe(
            contextPtr: Long,
            numThreads: Int,
            audioData: FloatArray,
            language: String,
            beamSize: Int,
            temperatureFallback: Boolean,
            prompt: String,
        ): Int
        /** The language actually decoded, or empty when the model reported none. */
        external fun getDetectedLanguage(contextPtr: Long): String

        external fun getTextSegmentCount(contextPtr: Long): Int
        external fun getTextSegment(contextPtr: Long, index: Int): String
        external fun getSystemInfo(): String
    }
}
