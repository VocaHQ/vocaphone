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
         * Top-level domains common enough to recognize even when the name in
         * front of them was capitalized — "Example.com" opening a sentence.
         */
        private const val TOP_LEVEL_DOMAINS =
            "com|org|net|edu|gov|int|mil|io|dev|app|ai|co|me|tv|cc|xyz|info|biz" +
                "|online|site|tech|store|blog|cloud|link|live|news|shop|space|wiki|zone" +
                "|[a-z]{2}"

        /**
         * Any other bare hostname, recognized by case rather than by a list.
         *
         * There are roughly 1,500 top-level domains, so an allowlist misses
         * real ones — `example.museum` — and punctuation repair then splits the
         * address in half. Matching *any* dotted pair instead has the opposite
         * fault: it masks `report.Then` in "I finished the report.Then I left",
         * and the missing space can never be repaired.
         *
         * Case is what actually separates them. A hostname is written in
         * lowercase from end to end; a full stop that ended a sentence is
         * followed by a capital. This clause requires the former, and the
         * leading `\b` keeps it from matching the tail of a capitalized word.
         *
         * What is left over is `report.then` — an all-lowercase run-on, from an
         * engine that emits a full stop but no capital. That one is genuinely
         * ambiguous, and it is masked, because leaving a space out is a blemish
         * where breaking an address in half is data loss.
         */
        private const val LOWERCASE_HOSTNAME = """\b(?:[a-z0-9][a-z0-9-]*\.)+[a-z]{2,24}\b"""

        /**
         * A path may contain dots; it may not end on the full stop that ends
         * the sentence the address is sitting in.
         */
        private const val PATH = """(?:/[^\s]*[^\s.,;:!?"“”'\)\]])?"""

        private val SPANS = Regex(
            """((?i:https?)://[^\s]+[^\s.,;:!?"“”'\)\]]""" +
                """|[\w.+-]+@(?:[\w-]+\.)+[A-Za-z]{2,}""" +
                """|(?:[\w-]+\.)+(?:$TOP_LEVEL_DOMAINS)\b$PATH""" +
                """|$LOWERCASE_HOSTNAME$PATH""" +
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
