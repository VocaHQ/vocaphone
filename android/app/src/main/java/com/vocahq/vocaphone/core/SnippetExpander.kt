package com.vocahq.vocaphone.core

import java.util.regex.Pattern

/**
 * Expands snippet triggers into their literal expansion text.
 *
 * Runs after [DictatedTranscript.finished], never before: the writing style
 * capitalizes sentence starts and would otherwise rewrite a snippet's literal
 * expansion (an email trigger must not come out "Me@example.com"). Matching
 * itself is case-insensitive, so capitalizing the source text first does not
 * break it.
 *
 * One combined regex over every trigger rather than a snippet-by-snippet
 * loop, so an expansion that happens to contain another trigger is never
 * re-expanded. Matches are applied in reverse order so an earlier match's
 * range stays valid while a later one is replaced.
 */
object SnippetExpander {

    fun expand(text: String, snippets: List<Snippet>): String {
        val active = snippets
            .filter { it.trigger.isNotBlank() }
            // Longer, more specific triggers win over a shorter one that could
            // be a substring of it ("my email" before "email").
            .sortedByDescending { it.trigger.length }
        if (active.isEmpty()) return text

        val pattern = Pattern.compile(
            active.joinToString("|") { "(${boundaryPattern(it.trigger)})" },
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE or Pattern.UNICODE_CHARACTER_CLASS,
        )
        val matcher = pattern.matcher(text)
        val matches = mutableListOf<Triple<Int, Int, Int>>()
        while (matcher.find()) {
            val groupIndex = (1..active.size).first { matcher.group(it) != null } - 1
            matches += Triple(matcher.start(), matcher.end(), groupIndex)
        }
        if (matches.isEmpty()) return text

        val result = StringBuilder(text)
        for ((start, end, index) in matches.asReversed()) {
            result.replace(start, end, active[index].expansion)
        }
        return result.toString()
    }

    /**
     * `\b` on a side whose edge character is a word character; otherwise a
     * non-whitespace lookaround, so a punctuation-only trigger like "->" still
     * matches at the start or end of the string, where `\b` would not fire.
     */
    private fun boundaryPattern(trigger: String): String {
        val prefix = if (trigger.first().isWordChar()) "\\b" else "(?<!\\S)"
        val suffix = if (trigger.last().isWordChar()) "\\b" else "(?!\\S)"
        return prefix + Pattern.quote(trigger) + suffix
    }

    private fun Char.isWordChar(): Boolean = isLetterOrDigit() || this == '_'
}
