package com.vocahq.vocaphone.ime

import com.vocahq.vocaphone.writing.WritingCategory
import com.vocahq.vocaphone.writing.WritingCheck
import com.vocahq.vocaphone.writing.WritingFixtures
import com.vocahq.vocaphone.writing.WritingSuggestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WritingContextTest {
    private val checker = WritingCheck(WritingFixtures.rules)

    private fun context(text: String, original: String): WritingContext {
        val s = checker.check(text).suggestions.first { it.shownOriginal == original }
        return writingContext(text, s)
    }

    @Test
    fun `the sentence around the words`() {
        val c = context("Hello there. what is you doing tonight? See you.", "is")
        assertEquals("what ", c.before)
        assertEquals(" you doing tonight?", c.after)
    }

    @Test
    fun `a line break ends the context`() {
        val c = context("First line\nI could of told you\nlast", "of")
        assertEquals("I could ", c.before)
        assertEquals(" told you", c.after)
    }

    @Test
    fun `long context is cut at a word with an ellipsis`() {
        val lead = "word ".repeat(30)
        val c = context("${lead}I could of told you " + "more ".repeat(30), "of")
        assertEquals('…', c.before.first())
        assertEquals('…', c.after.last())
        assert(c.before.length <= 61) { c.before }
        assert(c.after.length <= 61) { c.after }
    }

    private fun suggestion(original: String, replacement: String?) = WritingSuggestion(
        start = 0, end = original.length, original = original, replacement = replacement,
        markStart = 0, markEnd = original.length, kind = "passive", category = WritingCategory.CLARITY,
        title = "Passive voice", message = "", mechanical = false, source = WritingSuggestion.Source.RULE,
    )

    @Test
    fun `advice with no replacement is not struck through`() {
        assertFalse(strikesOriginal(suggestion("was written by", null)))
    }

    @Test
    fun `a replacement or a removal strikes the words`() {
        assertTrue(strikesOriginal(suggestion("teh", "the")))
        assertTrue(strikesOriginal(suggestion(" very", "")))
        assertFalse(strikesOriginal(suggestion("", ",")))
    }
}
