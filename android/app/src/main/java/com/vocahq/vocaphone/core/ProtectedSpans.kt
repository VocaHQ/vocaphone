package com.vocahq.vocaphone.core

/**
 * The runs of a transcript that no text stage may edit: URLs, email addresses,
 * bare domains, decimals and times, ordinals, and dotted initialisms.
 *
 * Every stage that rewrites punctuation has the same problem — `example.com`
 * ends in a full stop that is not a sentence, `3.5` contains one that is not a
 * mark at all — so they all mask first, work on the masked text, and restore
 * last. The placeholder is a private-use character pair rather than a word, so
 * a stage that lowercases or capitalizes cannot damage it and a stage that
 * splits on letters cannot split it.
 */
class ProtectedSpans private constructor(
    val text: String,
    private val tokens: List<String>,
) {
    /**
     * Puts the original spans back. Takes the text rather than reading [text],
     * because the caller has rewritten everything around them.
     */
    fun restore(masked: String): String = PLACEHOLDER.replace(masked) { match ->
        tokens.getOrNull(match.groupValues[1].toInt()) ?: match.value
    }

    companion object {
        const val OPEN = '\uE000'
        const val CLOSE = '\uE001'

        /**
         * Top-level domains a bare hostname is allowed to end in, plus any
         * two-letter country code.
         *
         * Matching *any* dotted pair instead — which this did — masks
         * `report.Then` in "I finished the report.Then I left" and the missing
         * space after the full stop can then never be repaired. Deliberately
         * case-sensitive: a real domain is written in lowercase, and `.To` at
         * the start of a sentence is not one.
         */
        private const val TOP_LEVEL_DOMAINS =
            "com|org|net|edu|gov|int|mil|io|dev|app|ai|co|me|tv|cc|xyz|info|biz" +
                "|online|site|tech|store|blog|cloud|link|live|news|shop|space|wiki|zone" +
                "|[a-z]{2}"

        private val SPANS = Regex(
            """((?i:https?)://[^\s]+[^\s.,;:!?"“”'\)\]]""" +
                """|[\w.+-]+@(?:[\w-]+\.)+[A-Za-z]{2,}""" +
                """|(?:[\w-]+\.)+(?:$TOP_LEVEL_DOMAINS)\b""" +
                // A path may contain dots; it may not end on the full stop that
                // ends the sentence the address is sitting in.
                """(?:/[^\s]*[^\s.,;:!?"“”'\)\]])?""" +
                """|\d+(?:[.,:/]\d+)+""" +
                """|\d+(?i:st|nd|rd|th)\b""" +
                """|(?:[A-Za-z]\.){2,})""",
        )

        private val PLACEHOLDER = Regex("$OPEN(\\d+)$CLOSE")

        fun mask(text: String): ProtectedSpans {
            val matches = SPANS.findAll(text).toList()
            var result = text
            for (index in matches.indices.reversed()) {
                result = result.replaceRange(matches[index].range, "$OPEN$index$CLOSE")
            }
            return ProtectedSpans(result, matches.map { it.value })
        }

        /**
         * Applies [transform] to everything that is not a placeholder. Case
         * changes have to skip them: the digits inside would survive, but the
         * characters are the only thing [restore] can match on.
         */
        fun mapOutsidePlaceholders(text: String, transform: (String) -> String): String {
            val result = StringBuilder(text.length)
            var end = 0
            PLACEHOLDER.findAll(text).forEach { match ->
                result.append(transform(text.substring(end, match.range.first)))
                result.append(match.value)
                end = match.range.last + 1
            }
            result.append(transform(text.substring(end)))
            return result.toString()
        }
    }
}
