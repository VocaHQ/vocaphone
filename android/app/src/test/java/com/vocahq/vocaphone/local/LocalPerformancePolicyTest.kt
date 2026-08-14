package com.vocahq.vocaphone.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals(768, WhisperCpuConfig.whisperAudioContext(2 * 16000))
        assertEquals(768, WhisperCpuConfig.whisperAudioContext(5 * 16000))
        // Past the floor the window tracks the audio, with margin over the 550
        // units eleven seconds actually occupies.
        assertEquals(1100, WhisperCpuConfig.whisperAudioContext(11 * 16000))
    }

    @Test
    fun `long recordings keep whispers own window`() {
        // At fifteen seconds the margin already reaches the full window, and a
        // recording whisper splits into thirty-second windows must not be cropped.
        assertEquals(0, WhisperCpuConfig.whisperAudioContext(15 * 16000))
        assertEquals(0, WhisperCpuConfig.whisperAudioContext(120 * 16000))
    }

    @Test
    fun `only the models verified against a cropped window ask for one`() {
        // The repetition this prevents was reported on the large models: "hi"
        // transcribed as "Hi. Hi." while the same build was fine on small.
        assertTrue(LocalModelCatalog.find("small-q5_1")!!.cropsAudioContext)
        assertTrue(LocalModelCatalog.find("tiny.en")!!.cropsAudioContext)
        assertFalse(LocalModelCatalog.find("medium-q5_0")!!.cropsAudioContext)
        assertFalse(LocalModelCatalog.find("large-v3-turbo-q5_0")!!.cropsAudioContext)
        assertFalse(LocalModelCatalog.find("large-v3")!!.cropsAudioContext)
    }

    @Test
    fun `older high ram phone receives a conservative recommendation`() {
        assertEquals("base-q5_1", LocalModelCatalog.recommended(8, "auto", 0).id)
        assertTrue(
            LocalModelCatalog.usableOnDevice(8).any { it.id == "large-v3-turbo-q5_0" },
        )
    }

    @Test
    fun `a capable phone is recommended a whisper it can keep up with`() {
        // One tier below the large-turbo builds this used to name: those took
        // five to eight seconds for a few words on a mid-range phone.
        assertEquals("small-q5_1", LocalModelCatalog.recommended(8, "auto", 31).id)
        assertEquals("large-v3-turbo-q5_0", LocalModelCatalog.recommended(12, "auto", 34).id)
    }

    @Test
    fun `a language with a specialist model is recommended it over whisper`() {
        fun recommended(ramGB: Long, language: String, performanceClass: Int) =
            LocalModelCatalog.recommended(ramGB, language, performanceClass, sherpaAvailable = true).id

        assertEquals("parakeet-tdt-0.6b-v2-en", recommended(8, "en", 34))
        assertEquals("giga-am-ctc-ru", recommended(8, "ru", 34))
        // A phone too small for Parakeet still gets a fast English model.
        assertEquals("moonshine-tiny-en", recommended(2, "en", 31))
        // Automatic and the languages without one stay on whisper: the
        // multilingual transducer is weaker on French and German than it looks.
        assertEquals("small-q5_1", recommended(8, "fr", 31))
        assertEquals("small-q5_1", recommended(8, "auto", 31))
    }

    @Test
    fun `a build without sherpa still recommends a whisper model`() {
        assertEquals(
            "small-q5_1",
            LocalModelCatalog.recommended(8, "en", 34, sherpaAvailable = false).id,
        )
    }
}
