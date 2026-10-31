package com.vocahq.vocaphone.writing

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

    private val whitespace = Regex("\\s+")

    fun applies(text: String, isEnglish: (String) -> Boolean): Boolean {
        if (text.none(Char::isLetter)) return false
        val words = text.split(whitespace).count { piece -> piece.any(Char::isLetter) }
        return words < SHORT || isEnglish(text)
    }
}
