package com.vocahq.vocaphone.writing

import com.vocahq.vocaphone.core.TranscriptRepair

/**
 * Whether Writing check's English rules should look at [text] at all, asked of
 * the text rather than of a keyboard or spell-checker locale: someone with the
 * English checker selected still writes French to some people, and "dont" is a
 * word there, not a missing apostrophe.
 *
 * Text with no letters has nothing to check. Under [SHORT] words it is checked
 * anyway, because a fragment that short says too little about its language and
 * a lone misspelling is exactly what should be caught. From [SHORT] words on it
 * is checked only when [isEnglish] says it is English. A word here is a
 * whitespace-separated piece with a letter in it, so "—" and "42" do not count.
 */
object WritingLanguage {
    const val SHORT = 6

    /** At most one word in this many may be spelled outside a–z for text to still be English. */
    const val BORROWED = 4

    private val whitespace = Regex("\\s+")

    fun applies(text: String, isEnglish: (String) -> Boolean): Boolean {
        if (text.none(Char::isLetter)) return false
        val words = text.split(whitespace).count { piece -> piece.any(Char::isLetter) }
        return words < SHORT || isEnglish(text, isEnglish)
    }

    /**
     * [isEnglish] (the transcript test, which calls any text with an accented
     * letter foreign) asked of [text] without its borrowed words. English
     * borrows "café", "naïve" and "résumé", and one of them in a sentence
     * should not switch off every check in it. So when no more than one word
     * in [BORROWED] has a letter outside a–z, those words are left out and the
     * rest decides; more than that and the text is not English.
     *
     * An accent is still evidence of another language, and dropping it leaves
     * the dictionary half of [isEnglish] easy to fool: "le", "tu", "as" and
     * "menu" are all English words, so "C'est le café dont tu as le menu."
     * would pass on its other words. The rest must also carry an English
     * marker word ([TranscriptRepair.englishMarkers]) other than "dont",
     * which is French as well and is the very word the rules would "correct".
     */
    fun isEnglish(text: String, isEnglish: (String) -> Boolean): Boolean {
        val words = text.split(whitespace).filter { piece -> piece.any(Char::isLetter) }
        val borrowed = words.filter(::isBorrowed)
        if (borrowed.isEmpty()) return isEnglish(text)
        if (borrowed.size * BORROWED > words.size) return false
        val rest = words.filterNot(::isBorrowed).joinToString(" ")
        return isEnglish(rest) && (TranscriptRepair.englishMarkers(rest) - SHARED_MARKERS).isNotEmpty()
    }

    /** English marker words that are words in French too. */
    private val SHARED_MARKERS = setOf("dont")

    private fun isBorrowed(word: String): Boolean =
        word.any { it.isLetter() && it.lowercaseChar() !in 'a'..'z' }
}
