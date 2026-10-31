package com.vocahq.vocaphone.spell

import com.vocahq.vocaphone.writing.WritingCategory
import com.vocahq.vocaphone.writing.WritingCheck
import com.vocahq.vocaphone.writing.WritingLanguage
import com.vocahq.vocaphone.writing.WritingText

/**
 * A Writing check report as Android's spell checker framework wants it: one
 * span per word, each either fine, a typo or a grammar error, with the text
 * that replaces it. Pure, so it is unit-tested without the framework.
 *
 * The framework replaces a span with the suggestion the user taps, so the
 * span and its suggestion have to cover the same words. An added word has no
 * span of its own and rides on the word before it ("I going" → "I" becomes
 * "I am"); a removed word takes the word before it along ("the the" → "the").
 * Every other word is reported fine, which is what lets a field drop an
 * underline once the mistake under it is fixed.
 */
object SpellCheckSpans {
    enum class Kind { FINE, TYPO, GRAMMAR }

    data class Span(val offset: Int, val length: Int, val kind: Kind, val suggestions: List<String>)

    /** Kinds the framework draws as a misspelling rather than a grammar error. */
    private val TYPO_KINDS = setOf("spelling", "apostrophe")

    /**
     * What the service reports for [text]: [of] the report [check] gives, or,
     * when [WritingLanguage] says the English rules should not look at the
     * text, every word fine without asking [check] at all. Fine rather than
     * nothing, so an underline drawn while a French sentence was still too
     * short to tell clears once it is long enough.
     */
    fun checked(text: String, isEnglish: (String) -> Boolean, check: () -> WritingCheck.Report): List<Span> =
        if (WritingLanguage.applies(text, isEnglish)) of(text, check()) else fine(text)

    /** Every word in [text], reported fine. */
    fun fine(text: String): List<Span> =
        WritingText.tokenize(text).filter { it.isWord }.map { Span(it.start, it.end - it.start, Kind.FINE, emptyList()) }

    fun of(text: String, report: WritingCheck.Report): List<Span> {
        val tokens = WritingText.tokenize(text)
        val flagged = ArrayList<Span>()
        for (s in report.suggestions) {
            val replacement = s.replacement ?: continue
            if (s.category != WritingCategory.CORRECTNESS) continue
            var start = minOf(s.start, s.markStart)
            val end = maxOf(s.end, s.markEnd)
            if (replacement.isEmpty()) {
                // Nothing to show for "remove": take the word before along, so
                // the suggestion is that word on its own.
                tokens.lastOrNull { it.isWord && it.end <= s.start }?.let { start = minOf(start, it.start) }
            }
            if (end <= start) continue
            val suggestion = text.substring(start, s.start) + replacement + text.substring(s.end, end)
            if (suggestion == text.substring(start, end)) continue
            val kind = if (s.kind in TYPO_KINDS) Kind.TYPO else Kind.GRAMMAR
            if (flagged.none { start < it.offset + it.length && it.offset < end }) {
                flagged.add(Span(start, end - start, kind, listOf(suggestion)))
            }
        }
        val fine = tokens.filter { it.isWord }
            .filter { t -> flagged.none { t.start < it.offset + it.length && it.offset < t.end } }
            .map { Span(it.start, it.end - it.start, Kind.FINE, emptyList()) }
        return (flagged + fine).sortedBy { it.offset }
    }
}
