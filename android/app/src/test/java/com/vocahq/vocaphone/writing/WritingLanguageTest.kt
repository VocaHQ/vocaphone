package com.vocahq.vocaphone.writing

import com.vocahq.vocaphone.core.TranscriptRepair
import com.vocahq.vocaphone.spell.SpellCheckSpans
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WritingLanguageTest {
    private val check = WritingCheck(WritingFixtures.rules)

    /** The keyboard's word list, as the spell checker service reads it from its assets. */
    private val englishWords: Set<String> by lazy {
        val file = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "assets/keyboard/en.txt") }
            .first(File::isFile)
        file.readLines().map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toHashSet()
    }

    private fun isEnglish(text: String) = TranscriptRepair.isEnglish(text, "auto") { it in englishWords }

    private fun report(text: String) =
        check.check(text, null, WritingCheck.Options(setOf(WritingCategory.CORRECTNESS), typed = true))

    /** What the system spell checker underlines in [text], as "original → suggestion". */
    private fun underlines(text: String): List<String> =
        SpellCheckSpans.checked(text, ::isEnglish) { report(text) }
            .filter { it.kind != SpellCheckSpans.Kind.FINE }
            .map { "${text.substring(it.offset, it.offset + it.length)} → ${it.suggestions.single()}" }

    @Test
    fun `a French sentence gets no English suggestions`() {
        val french = "C'est le livre dont elle parle."
        // The rules alone would offer "don't" for the French "dont".
        assertTrue(SpellCheckSpans.of(french, report(french)).any { it.kind != SpellCheckSpans.Kind.FINE })
        assertEquals(emptyList<String>(), underlines(french))
    }

    @Test
    fun `a French sentence still reports every word fine, so old underlines clear`() {
        val french = "C'est le livre dont elle parle."
        val spans = SpellCheckSpans.checked(french, ::isEnglish) { error("the rules should not run") }
        assertEquals(setOf(SpellCheckSpans.Kind.FINE), spans.map { it.kind }.toSet())
        assertEquals(listOf("C'est", "le", "livre", "dont", "elle", "parle"), spans.map { french.substring(it.offset, it.offset + it.length) })
    }

    @Test
    fun `an English sentence of six words or more is still checked`() {
        assertEquals(listOf("helps → help"), underlines("I was wondering if you could helps."))
    }

    @Test
    fun `a single misspelled word is still checked`() {
        assertEquals(listOf("definately → definitely"), underlines("definately"))
    }

    @Test
    fun `under six words the language is not asked`() {
        assertTrue(WritingLanguage.applies("le livre dont elle parle") { error("not asked") })
    }

    @Test
    fun `six words or more are checked only when English`() {
        assertFalse(WritingLanguage.applies("one two three four five six") { false })
        assertTrue(WritingLanguage.applies("one two three four five six") { true })
    }

    @Test
    fun `pieces without a letter are not words`() {
        assertTrue(WritingLanguage.applies("one two — 42 three four five") { false })
    }

    @Test
    fun `text without letters is not checked`() {
        assertFalse(WritingLanguage.applies("") { true })
        assertFalse(WritingLanguage.applies("42 — 7:30 !!") { true })
    }
}
