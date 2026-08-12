package com.vocahq.vocaphone.core

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptStylerTest {
    @Test
    fun `local styles match the gateway examples`() {
        val source = "hello there. how are you"
        assertEquals(source, TranscriptStyler.apply(source, WritingStyle.RAW))
        assertEquals("hello there. how are you.", TranscriptStyler.apply(source, WritingStyle.CLEAN))
        assertEquals("Hello there. How are you.", TranscriptStyler.apply(source, WritingStyle.FORMAL))
        assertEquals("Hello there. How are you", TranscriptStyler.apply(source, WritingStyle.CASUAL))
        assertEquals("hello there, how are you", TranscriptStyler.apply(source, WritingStyle.VERY_CASUAL))
        assertEquals("Hello there! How are you!", TranscriptStyler.apply(source, WritingStyle.EXCITED))
    }

    @Test
    fun `local styling keeps protected spans intact`() {
        val source = "Email John@Example.com at 3:30."
        assertEquals(
            "email John@Example.com at 3:30",
            TranscriptStyler.apply(source, WritingStyle.VERY_CASUAL),
        )
    }

    /**
     * The case that made plumbing the detected language back from the on-device
     * engines worth doing: "auto" can only guess from the text, and unpunctuated
     * Devanagari looks exactly like Latin to that guess.
     */
    @Test
    fun `an unpunctuated script needs the detected language, not auto`() {
        val hindi = "मैं कल बाजार जाऊंगा"
        assertEquals(
            "a detected language punctuates by script",
            "मैं कल बाजार जाऊंगा।",
            TranscriptStyler.apply(hindi, WritingStyle.FORMAL, "hi"),
        )
        assertEquals(
            "auto has nothing to go on and falls back to Latin",
            "मैं कल बाजार जाऊंगा.",
            TranscriptStyler.apply(hindi, WritingStyle.FORMAL, "auto"),
        )
        // Once the model has punctuated it, sniffing the text is enough.
        assertEquals(
            "मैं कल बाजार जाऊंगा। वह ठीक है।",
            TranscriptStyler.apply("मैं कल बाजार जाऊंगा। वह ठीक है", WritingStyle.FORMAL, "auto"),
        )
    }

    @Test
    fun `local styling uses language punctuation`() {
        assertEquals(
            "家に帰りました！ジョンが電話してきました！",
            TranscriptStyler.apply(
                "家に帰りました。ジョンが電話してきました。",
                WritingStyle.EXCITED,
                "ja",
            ),
        )
        assertEquals(
            "मैं कल बाजार जाऊंगा।",
            TranscriptStyler.apply("मैं कल बाजार जाऊंगा", WritingStyle.FORMAL, "hi"),
        )
    }
}
