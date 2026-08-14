package com.vocahq.vocaphone.ime

import android.content.res.AssetManager

internal class SuggestionDictionary(
    private val words: List<String>,
    private val bigrams: Map<String, List<String>>,
) {
    private val known = words.toHashSet()

    fun isKnown(word: String): Boolean = known.contains(word.lowercase())

    fun complete(prefix: String, limit: Int = 3): List<String> {
        if (prefix.isEmpty()) return emptyList()
        val lower = prefix.lowercase()
        return words.asSequence()
            .filter { it.startsWith(lower) && it.length > lower.length }
            .take(limit)
            .map { SuggestionEngine.matchCase(prefix, it) }
            .toList()
    }

    fun next(previousWord: String, limit: Int = 3): List<String> {
        if (previousWord.isEmpty()) return emptyList()
        return bigrams[previousWord.lowercase()].orEmpty().take(limit)
    }

    fun correct(typed: String, limit: Int = 3): List<String> {
        val lower = typed.lowercase()
        if (lower.length < 2 || isKnown(lower)) return emptyList()
        val distance1 = ArrayList<String>(limit)
        val distance2 = ArrayList<String>(limit)
        val minLen = (lower.length - 2).coerceAtLeast(1)
        val maxLen = lower.length + 2
        for (word in words) {
            if (word.length !in minLen..maxLen) continue
            val distance = SuggestionEngine.editDistance(lower, word, max = 2)
            when (distance) {
                1 -> distance1.add(word)
                2 -> if (distance2.size < limit) distance2.add(word)
            }
            if (distance1.size >= limit) break
        }
        return (distance1 + distance2)
            .take(limit)
            .map { SuggestionEngine.matchCase(typed, it) }
    }

    fun strip(
        composing: String,
        before: String,
        after: String,
        correctionsEnabled: Boolean,
    ): SuggestionStrip {
        if (composing.isNotEmpty()) {
            val completions = complete(composing)
            val corrections = if (correctionsEnabled) correct(composing) else emptyList()
            return SuggestionStrip((completions + corrections).distinct().take(3))
        }
        if (correctionsEnabled) {
            val span = SuggestionEngine.wordSpan(before, after)
            if (span != null && span.word.length >= 3 && !isKnown(span.word)) {
                val corrections = correct(span.word)
                if (corrections.isNotEmpty()) {
                    return SuggestionStrip(corrections, replacesWord = true)
                }
            }
        }
        return SuggestionStrip(next(SuggestionEngine.lastWord(before).orEmpty()))
    }

    companion object {
        fun load(assets: AssetManager) = SuggestionDictionary(
            words = assets.open("suggestions/en.txt").bufferedReader().readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() },
            bigrams = parseBigrams(
                assets.open("suggestions/en-bigrams.txt").bufferedReader().readLines(),
            ),
        )

        fun parseBigrams(lines: List<String>): Map<String, List<String>> = buildMap {
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                val tab = trimmed.indexOf('\t')
                if (tab <= 0) continue
                val word = trimmed.substring(0, tab)
                val continuations = trimmed.substring(tab + 1)
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                if (continuations.isNotEmpty()) put(word, continuations)
            }
        }
    }
}

internal object SuggestionEngine {
    fun lastWord(textBeforeCursor: CharSequence): String? {
        val trimmed = textBeforeCursor.trimEnd()
        if (trimmed.isEmpty()) return null
        var start = trimmed.length
        while (start > 0) {
            val character = trimmed[start - 1]
            if (!character.isLetterOrDigit() && character != '\'') break
            start--
        }
        val word = trimmed.substring(start).replace("'", "").lowercase()
        return word.takeIf { it.any(Char::isLetter) }
    }

    internal fun matchCase(prefix: String, word: String): String = when {
        prefix.all { it.isUpperCase() } -> word.uppercase()
        prefix.first().isUpperCase() -> word.replaceFirstChar { it.uppercase() }
        else -> word
    }

    fun wordSpan(before: CharSequence, after: CharSequence): WordSpan? {
        var start = before.length
        while (start > 0 && isWordChar(before[start - 1])) start--
        var end = 0
        while (end < after.length && isWordChar(after[end])) end++
        if (start == before.length && end == 0) return null
        val word = buildString {
            append(before.subSequence(start, before.length))
            append(after.subSequence(0, end))
        }
        if (word.none { it.isLetter() }) return null
        return WordSpan(word, before.length - start, end)
    }

    internal fun editDistance(left: String, right: String, max: Int): Int {
        if (left == right) return 0
        if (kotlin.math.abs(left.length - right.length) > max) return max + 1
        val columns = right.length
        var previousPrevious = IntArray(columns + 1)
        var previous = IntArray(columns + 1) { it }
        var current = IntArray(columns + 1)
        for (i in 1..left.length) {
            current[0] = i
            var rowMin = current[0]
            val leftChar = left[i - 1]
            for (j in 1..columns) {
                val substitution = if (leftChar == right[j - 1]) 0 else 1
                var value = minOf(
                    previous[j] + 1,
                    current[j - 1] + 1,
                    previous[j - 1] + substitution,
                )
                if (
                    i > 1 &&
                    j > 1 &&
                    leftChar == right[j - 2] &&
                    left[i - 2] == right[j - 1]
                ) {
                    value = minOf(value, previousPrevious[j - 2] + 1)
                }
                current[j] = value
                if (value < rowMin) rowMin = value
            }
            if (rowMin > max) return max + 1
            val recycled = previousPrevious
            previousPrevious = previous
            previous = current
            current = recycled
        }
        return previous[columns]
    }

    private fun isWordChar(character: Char): Boolean =
        character.isLetterOrDigit() || character == '\''
}
