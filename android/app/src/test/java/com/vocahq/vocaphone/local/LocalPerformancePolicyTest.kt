package com.vocahq.vocaphone.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPerformancePolicyTest {

    @Test
    fun `whisper worker count is capped for sustained phone inference`() {
        assertEquals(2, WhisperCpuConfig.whisperThreadCount(4))
        assertEquals(6, WhisperCpuConfig.whisperThreadCount(8))
        assertEquals(6, WhisperCpuConfig.whisperThreadCount(16))
    }

    @Test
    fun `short dictations crop the encoder window instead of padding to thirty seconds`() {
        // A two-second dictation needs 100 units of context; the floor is what
        // keeps the decoder out of a repetition loop at that length.
        assertEquals(448, WhisperCpuConfig.whisperAudioContext(2 * 16000))
        assertEquals(448, WhisperCpuConfig.whisperAudioContext(5 * 16000))
        // Past the floor the window tracks the audio, with margin over the 550
        // units eleven seconds actually occupies.
        assertEquals(825, WhisperCpuConfig.whisperAudioContext(11 * 16000))
    }

    @Test
    fun `long recordings keep whispers own window`() {
        // At twenty seconds the margin already reaches the full window, and a
        // recording whisper splits into thirty-second windows must not be cropped.
        assertEquals(0, WhisperCpuConfig.whisperAudioContext(20 * 16000))
        assertEquals(0, WhisperCpuConfig.whisperAudioContext(120 * 16000))
    }

    @Test
    fun `older high ram phone receives a conservative whisper recommendation`() {
        assertEquals(
            "base-q5_1",
            LocalModelCatalog.recommended(8, 0, sherpaAvailable = false).id,
        )
        assertTrue(
            LocalModelCatalog.usableOnDevice(8).any { it.id == "large-v3-turbo-q5_0" },
        )
    }

    @Test
    fun `large whisper remains the pick when sherpa is missing`() {
        assertEquals(
            "large-v3-turbo-q5_0",
            LocalModelCatalog.recommended(8, 31, sherpaAvailable = false).id,
        )
        assertEquals(
            "large-v3-turbo",
            LocalModelCatalog.recommended(12, 34, sherpaAvailable = false).id,
        )
    }

    @Test
    fun `arm64 phones with enough ram get parakeet`() {
        assertEquals(
            "parakeet-tdt-0.6b-v3",
            LocalModelCatalog.recommended(
                totalRamGB = 8,
                mediaPerformanceClass = 0,
                abi = "arm64-v8a",
                sherpaAvailable = true,
            ).id,
        )
        assertEquals(
            "parakeet-tdt-0.6b-v3",
            LocalModelCatalog.recommended(
                totalRamGB = 4,
                mediaPerformanceClass = 31,
                abi = "arm64-v8a",
                sherpaAvailable = true,
            ).id,
        )
    }

    @Test
    fun `32-bit arm stays on the whisper ladder`() {
        assertEquals(
            "base-q5_1",
            LocalModelCatalog.recommended(
                totalRamGB = 8,
                mediaPerformanceClass = 0,
                abi = "armeabi-v7a",
                sherpaAvailable = true,
            ).id,
        )
    }

    @Test
    fun `parakeet is not treated as heavier than a smaller whisper`() {
        val parakeet = LocalModelCatalog.find("parakeet-tdt-0.6b-v3")!!
        val whisperBase = LocalModelCatalog.find("base-q5_1")!!
        val whisperLarge = LocalModelCatalog.find("large-v3")!!
        assertTrue(parakeet.sizeBytes > whisperBase.sizeBytes)
        assertTrue(!LocalModelCatalog.needsHeavierWarning(parakeet, whisperBase))
        assertTrue(LocalModelCatalog.needsHeavierWarning(whisperLarge, parakeet))
        assertTrue(!LocalModelCatalog.needsHeavierWarning(whisperBase, parakeet))
    }
}
