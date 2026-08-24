package com.vocahq.vocaphone.ime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * What a careful swipe of a common word actually returns.
 *
 * The production matcher sees geometry, not an ideal letter string. This
 * traces the polyline through each word's keys, then also the keys a finger
 * would fly over on that polyline — the path that "with" actually draws.
 */
class SwipeCommonWordsTest {

    private val words: List<String>? by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "assets/keyboard/en.txt") }
            .firstOrNull(File::isFile)
            ?.readLines()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
    }

    private fun dictionary(): SuggestionDictionary {
        val list = words
        assumeTrue("assets/keyboard is only present in the repository", list != null)
        return SuggestionDictionary(words = list!!, bigrams = emptyMap())
    }

    private val common = listOf(
        "the", "of", "and", "to", "in", "for", "is", "on", "that", "this",
        "with", "you", "it", "not", "or", "are", "from", "at", "as", "have",
        "was", "we", "will", "can", "about", "but", "our", "what", "which",
        "they", "just", "like", "some", "would", "there", "good", "go",
        "top", "hello", "when", "your", "all", "more", "if", "out", "see",
        "how", "been", "make", "should", "into", "over", "time", "get",
    )

    @Test
    fun `ideal path through a common word ranks it first`() {
        val dict = dictionary()
        val misses = ArrayList<String>()
        for (word in common) {
            val top = dict.swipe(word, points = SwipeLayout.interpolate(word))
            if (top.firstOrNull() != word) {
                misses.add("$word → $top")
            }
        }
        assertTrue("ideal-path misses:\n${misses.joinToString("\n")}", misses.isEmpty())
    }

    @Test
    fun `a fly-over path through a common word still ranks it in the top suggestions`() {
        val dict = dictionary()
        val misses = ArrayList<String>()
        for (word in common) {
            val points = SwipeLayout.interpolate(word, stepsPerSegment = 12)
            val keys = SwipeLayout.nearestKeyString(points)
            val top = dict.swipe(keys, points = points)
            // Top four is what the strip shows. Missing the strip is the
            // failure the user feels: they swiped "with" and never saw it.
            if (word !in top) {
                misses.add("$word keys=$keys → $top")
            }
        }
        assertTrue("fly-over misses:\n${misses.joinToString("\n")}", misses.isEmpty())
    }

    @Test
    fun `a shortcut swipe that skips reverse letters still finds with`() {
        val dict = dictionary()
        val points = SwipeLayout.shortcutInterpolate("with")
        val keys = SwipeLayout.nearestKeyString(points)
        val top = dict.swipe(keys, points = points)
        assertTrue("with keys=$keys → $top", top.firstOrNull() == "with" || "with" in top)
        assertEquals("with", top.first())
    }

    @Test
    fun `shortcut swipes of common words stay in the strip`() {
        val dict = dictionary()
        val misses = ArrayList<String>()
        for (word in common) {
            val points = SwipeLayout.shortcutInterpolate(word)
            val keys = SwipeLayout.nearestKeyString(points)
            val top = dict.swipe(keys, points = points)
            if (word !in top) misses.add("$word keys=$keys → $top")
        }
        assertTrue("shortcut misses:\n${misses.joinToString("\n")}", misses.isEmpty())
    }
}
