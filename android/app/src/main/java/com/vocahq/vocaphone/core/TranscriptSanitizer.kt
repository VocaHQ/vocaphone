package com.vocahq.vocaphone.core

/**
 * Speech models annotate non-speech with bracketed markers — `whisper.cpp`
 * emits `[BLANK_AUDIO]` for silence, others emit `[MUSIC]` or `(inaudible)`.
 * They are diagnostics, not something the user dictated, so they never belong
 * in a text field. A transcript that is nothing but markers is treated as
 * nothing having been transcribed at all.
 */
object TranscriptSanitizer {

    /**
     * Deliberately a fixed list rather than "anything in brackets": a user can
     * legitimately dictate "[1,200]" or "(see below)", and swallowing that would
     * be worse than leaving a marker in.
     */
    private val MARKER_WORDS = setOf(
        "blank_audio", "blankaudio", "blank audio",
        "silence", "silent", "no speech", "no_speech", "nospeech",
        "music", "musique", "sound", "sounds",
        "noise", "background noise", "static",
        "inaudible", "unintelligible", "indistinct",
        "laughter", "laughs", "laughing", "applause", "coughing", "sighs",
        "pause", "beep", "clears throat",
    )

    private val BRACKETED = Regex("""[\[(<]\s*([^\[\]()<>]{1,32}?)\s*[\])>]""")

    fun clean(transcript: String?): String {
        if (transcript.isNullOrBlank()) return ""

        val withoutMarkers = BRACKETED.replace(transcript) { match ->
            val inner = match.groupValues[1].trim().lowercase().trim('*', '.', '!', '-', '_')
            if (inner.replace('_', ' ') in MARKER_WORDS || inner in MARKER_WORDS) "" else match.value
        }
        // Collapse the spacing the removal leaves behind, without touching the
        // line breaks the writing style may have produced.
        return withoutMarkers
            .split('\n')
            .joinToString("\n") { line ->
                collapseRepetition(line.replace(Regex("""[ \t]{2,}"""), " ").trim())
            }
            .trim()
            .trim('\n')
    }

    /** Longest run treated as a loop. Beyond this it is prose, not a stutter. */
    private const val MAX_PHRASE_WORDS = 8

    /**
     * Collapses the repetition loop an attention model falls into when it runs
     * out of audio it can make sense of — a phrase emitted over and over until
     * the window ends. It is the single most recognizable way a transcript goes
     * wrong, and unlike a bracketed marker there is no token to look for.
     *
     * The thresholds differ by length on purpose. A repeated phrase of two or
     * more words is almost never something a person said three times running,
     * so one copy is kept. A single word genuinely is — "no no no no" is a
     * sentence — so it takes more repeats to look like a loop, and two copies
     * survive to record that the emphasis was there.
     *
     * Punctuation lowers the bar to a single repeat. A loop restarts the
     * decoder's sentence, so its copies arrive finished — "Hi. Hi." — where
     * emphasis and reduplication do not: "bye bye" and "no no no" are one
     * sentence with the punctuation, if any, only at the end. That distinction
     * is what lets the two-word case be collapsed at all, and it is the shape
     * the smallest and most common loop takes.
     */
    private fun collapseRepetition(line: String): String {
        if (line.isEmpty()) return line
        val words = line.split(' ').filter { it.isNotEmpty() }
        if (words.size < 2) return line

        val result = mutableListOf<String>()
        var index = 0
        while (index < words.size) {
            var phrase = 0
            var repeats = 0
            var kept = 0
            // Ascending, stopping at the first match, so the shortest repeating
            // unit wins. A phrase repeated four times is also a double phrase
            // repeated twice, and only the shorter reading collapses all of it:
            // "Thank you." four times over is one sentence, not two.
            for (length in 1..minOf(MAX_PHRASE_WORDS, (words.size - index) / 2)) {
                val count = countRepeats(words, index, length)
                val survivors = keptCopies(words, index, length, count) ?: continue
                phrase = length
                repeats = count
                kept = survivors
                break
            }
            if (phrase == 0) {
                result += words[index]
                index++
                continue
            }
            // Taken from the source rather than the first copy repeated, so the
            // survivors keep the punctuation they arrived with.
            for (offset in 0 until phrase * kept) result += words[index + offset]
            index += phrase * repeats
        }
        return result.joinToString(" ")
    }

    /**
     * How many copies of this run survive, or null when it is not a loop.
     */
    private fun keptCopies(words: List<String>, start: Int, length: Int, repeats: Int): Int? = when {
        repeats < 2 -> null
        // Every copy a finished sentence: the decoder restarted, a speaker
        // repeating themselves for emphasis did not.
        sentencesRepeated(words, start, length, repeats) -> 1
        repeats >= if (length == 1) 4 else 3 -> if (length == 1) 2 else 1
        else -> null
    }

    /** Whether each of the [repeats] copies ends its own sentence. */
    private fun sentencesRepeated(
        words: List<String>,
        start: Int,
        length: Int,
        repeats: Int,
    ): Boolean = (0 until repeats).all { copy ->
        val last = words[start + copy * length + length - 1]
        val ending = last.trimEnd('"', '\'', ')', ']', '»', '”').lastOrNull()
        ending != null && ending in SENTENCE_ENDINGS
    }

    /** What a decoder finishes a sentence with, across the scripts on offer. */
    private val SENTENCE_ENDINGS = setOf('.', '!', '?', '。', '！', '？', '।', '۔')

    /** How many times the [length]-word unit at [start] repeats back to back. */
    private fun countRepeats(words: List<String>, start: Int, length: Int): Int {
        var repeats = 1
        var next = start + length
        while (next + length <= words.size) {
            for (offset in 0 until length) {
                if (wordKey(words[start + offset]) != wordKey(words[next + offset])) return repeats
            }
            repeats++
            next += length
        }
        return repeats
    }

    /**
     * Case and punctuation are exactly what differ between the copies in a loop
     * — "Thank you." then "thank you," — so neither can take part in matching.
     */
    private fun wordKey(word: String): String = word.lowercase().filter(Char::isLetterOrDigit)
}
