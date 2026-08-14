package com.vocahq.vocaphone.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestionEngineTest {

    private val dictionary = SuggestionDictionary(
        words = listOf("hello", "help", "held", "the", "there", "their", "book"),
        bigrams = mapOf(
            "the" to listOf("first", "same", "other"),
            "see" to listOf("you", "the"),
        ),
    )

    @Test
    fun `completions follow frequency order and skip the exact prefix`() {
        assertEquals(listOf("hello", "help", "held"), dictionary.complete("hel"))
        assertEquals(listOf("Hello", "Help", "Held"), dictionary.complete("Hel"))
        assertEquals(listOf("HELLO", "HELP", "HELD"), dictionary.complete("HEL"))
        assertTrue(dictionary.complete("zzz").isEmpty())
    }

    @Test
    fun `next word uses the last token before the cursor`() {
        assertEquals("world", SuggestionEngine.lastWord("hello world"))
        assertEquals("world", SuggestionEngine.lastWord("hello world "))
        assertEquals("dont", SuggestionEngine.lastWord("I don't"))
        assertEquals(listOf("you", "the"), dictionary.next("see"))
        assertTrue(dictionary.next("xyz").isEmpty())
    }

    @Test
    fun `corrections prefer nearby dictionary words`() {
        assertEquals(listOf("hello"), dictionary.correct("helllo"))
        assertEquals(listOf("the"), dictionary.correct("teh"))
        assertTrue(dictionary.correct("hello").isEmpty())
        assertEquals(listOf("Hello"), dictionary.correct("Helllo"))
    }

    @Test
    fun `word span covers the token around the cursor`() {
        val span = SuggestionEngine.wordSpan("say hel", "lo there")
        assertEquals("hello", span?.word)
        assertEquals(3, span?.beforeLength)
        assertEquals(2, span?.afterLength)
    }

    @Test
    fun `strip offers corrections for a misspelled committed word`() {
        val strip = dictionary.strip(
            composing = "",
            before = "helllo",
            after = "",
            correctionsEnabled = true,
        )
        assertEquals(listOf("hello"), strip.words)
        assertTrue(strip.replacesWord)
    }

    @Test
    fun `swipe matches a path across letter keys`() {
        assertEquals(listOf("the"), dictionary.swipe("the"))
        assertEquals(listOf("the"), dictionary.swipe("tghe"))
        assertEquals(listOf("hello"), dictionary.swipe("hjkello"))
        assertEquals(listOf("hello"), dictionary.swipe("helo"))
        assertEquals(listOf("book"), dictionary.swipe("bok"))
        assertTrue(dictionary.swipe("qz").isEmpty())
        assertTrue(dictionary.swipe("h").isEmpty())
    }

    @Test
    fun `replaceable word includes a trailing space after a swipe`() {
        val span = SuggestionEngine.replaceableWord("hello ", "")
        assertEquals("hello", span?.word)
        assertEquals(6, span?.beforeLength)
        assertEquals(0, span?.afterLength)
    }
}
