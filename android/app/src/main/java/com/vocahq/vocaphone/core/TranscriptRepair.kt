package com.vocahq.vocaphone.core

/**
 * Turns what a speech model heard into what the speaker meant to write:
 * hesitation sounds dropped, false starts collapsed, and the punctuation the
 * model left out put in.
 *
 * This is deliberately **not** part of [TranscriptStyler]. That stage documents
 * a contract it has to keep — no style adds, removes, or substitutes a word —
 * and this stage exists precisely to break it, under a switch of its own.
 * Keeping them apart is what lets the styles still be described honestly.
 *
 * Everything here is rule-based. There is no model, no network call, and no
 * language understanding: the rules are the conservative subset where a mistake
 * is nearly impossible to make, and anything needing judgement about what the
 * speaker meant is left alone on purpose. The order of the stages is
 * load-bearing and is documented at each one.
 *
 * Mirrors the iOS client's `TranscriptRepair`; the two are expected to produce
 * the same text for the same transcript.
 */
object TranscriptRepair {

    /**
     * @param text a transcript that has already been through [TranscriptSanitizer].
     * @param language the language the finished text is written in, or `"auto"`.
     */
    fun apply(text: String?, language: String = "auto"): String {
        val source = text.orEmpty().trim()
        if (source.isEmpty()) return ""

        val punctuation = SentencePunctuation.resolve(language, source)
        val code = language.lowercase().substringBefore('-')
        val spans = ProtectedSpans.mask(source)

        // 1. Fillers first: they are the noise every later rule would otherwise
        //    have to reason around. "we should um we should ship" is not a
        //    stutter until the "um" between the copies is gone.
        var working = removeFillers(spans.text, code)
        // 2. Stutters, on the text the fillers left behind.
        working = collapseStutters(working, punctuation)
        // 3. Normalize the marks that are there before inferring the ones that
        //    are not, so inference counts real sentence boundaries.
        working = repairMarks(working, punctuation)
        // 4. Inference is written against how English builds a sentence. In a
        //    script that spaces or terminates differently it would do damage,
        //    and in another Latin language its trigger words never match.
        if (punctuation.usesLatinLayout) working = infer(working, punctuation)

        val repaired = spans.restore(working).trim()
        // A transcript that repaired down to nothing is a rule misfiring, not a
        // silent recording — the sanitizer has already had its say about those.
        // Hand back what came in rather than an empty text field.
        return if (repaired.any { it.isLetterOrDigit() }) repaired else source
    }

    // region Words

    /**
     * A whitespace-delimited chunk split into the punctuation around it and the
     * word inside, so a rule can drop a word without losing the comma attached
     * to it, or match a word without its full stop getting in the way.
     */
    private data class Word(
        var leading: String,
        var text: String,
        var trailing: String,
    ) {
        val isEmpty: Boolean get() = leading.isEmpty() && text.isEmpty() && trailing.isEmpty()

        val rendered: String get() = leading + text + trailing

        /**
         * Lowercased letters and digits only. Apostrophes go, so `don't` and
         * `dont` match, and the placeholder characters stay so a masked URL can
         * never compare equal to a bare number.
         */
        val key: String
            get() = text.lowercase().filter {
                it.isLetterOrDigit() || it == ProtectedSpans.OPEN || it == ProtectedSpans.CLOSE
            }

        val isProtected: Boolean get() = text.contains(ProtectedSpans.OPEN)

        val startsUppercase: Boolean
            get() = text.firstOrNull { it.isLetter() }?.isUpperCase() == true
    }

    private fun isWordCharacter(character: Char): Boolean =
        character.isLetterOrDigit() || character == '\'' || character == '’' ||
            character == '-' || character == ProtectedSpans.OPEN || character == ProtectedSpans.CLOSE

    private fun split(text: String): MutableList<Word> =
        text.split(Regex("\\s+")).filter { it.isNotEmpty() }.map { chunk ->
            var start = 0
            while (start < chunk.length && !isWordCharacter(chunk[start])) start++
            var end = chunk.length
            while (end > start && !isWordCharacter(chunk[end - 1])) end--
            Word(chunk.substring(0, start), chunk.substring(start, end), chunk.substring(end))
        }.toMutableList()

    private fun joined(words: List<Word>): String =
        words.filterNot { it.isEmpty }.joinToString(" ") { it.rendered }

    // endregion

    // region Fillers

    /**
     * The hesitation sounds, after [canonical] has flattened however many
     * letters the model chose to spell them with.
     *
     * Every one of these is non-lexical: there is no sentence in which the word
     * carries meaning, which is the whole reason removing it is safe. That is
     * also why `like`, `you know`, `I mean` and `actually` are **not** here.
     * Each is a real word often enough that dropping it needs judgement about
     * what the speaker meant, and this stage does not have any.
     */
    private val UNIVERSAL_FILLERS = setOf("um", "uhm", "umh", "uh", "er", "erm", "hm")

    /**
     * Sounds that mean *yes* or *no*, and must survive. `mhm` and `uh-huh` are
     * answers to a question, and a transcript that drops them says the opposite
     * of what was said. They are listed rather than merely absent so that
     * widening [UNIVERSAL_FILLERS] cannot quietly swallow one.
     */
    private val AFFIRMATIONS = setOf("mhm", "mhmm", "mmhm", "uhuh", "uhhuh", "nuhuh", "huh", "hu")

    /**
     * Only the non-lexical hesitation sounds, same bar as [UNIVERSAL_FILLERS].
     * A language is absent here because nobody has checked it, not because it
     * has no fillers.
     */
    private val LOCAL_FILLERS = mapOf(
        "de" to setOf("äh", "ähm", "öh", "öhm"),
        "nl" to setOf("eh", "ehm", "uh"),
        "fr" to setOf("euh", "heu"),
        "es" to setOf("eh", "em"),
        "it" to setOf("ehm", "eh"),
    )

    /**
     * Fillers in the scripts that are written without spaces, where there are
     * no words to walk. Removed as plain substrings, which is only safe because
     * each of these carries a length mark that a content word does not.
     */
    private val UNSPACED_FILLERS = mapOf(
        "ja" to listOf("えーと", "えっと", "ええと", "あのー", "あのう"),
        "zh" to listOf("呃"),
        "yue" to listOf("呃"),
    )

    private const val QUOTES = "\"'“”‘’«»„"

    /**
     * Collapses a run of one repeated letter, so however long the model drew
     * the sound out it lands on the same key: `ummmm` and `uhhh` become `um`
     * and `uh`. Also drops the hyphen in `uh-huh`, which is why that spelling
     * reaches [AFFIRMATIONS] intact.
     */
    private fun canonical(key: String): String {
        val result = StringBuilder(key.length)
        var previous: Char? = null
        for (character in key) {
            if (character == '-') continue
            if (character != previous) result.append(character)
            previous = character
        }
        return result.toString()
    }

    private fun isFiller(word: Word, code: String): Boolean {
        if (word.isProtected) return false
        // A quoted filler is being talked about rather than said: someone
        // dictating `he said "um" a lot` means the word to be there.
        if (word.leading.any { it in QUOTES } || word.trailing.any { it in QUOTES }) return false
        val key = canonical(word.key)
        if (key.isEmpty() || key in AFFIRMATIONS) return false
        return key in UNIVERSAL_FILLERS || LOCAL_FILLERS[code]?.contains(key) == true
    }

    private fun removeFillers(text: String, code: String): String {
        var result = text
        UNSPACED_FILLERS[code]?.forEach { filler -> result = result.replace(filler, "") }

        val kept = mutableListOf<Word>()
        for (word in split(result)) {
            if (!isFiller(word, code)) {
                kept += word
                continue
            }
            // The filler goes, but a sentence it happened to end does not:
            // "I was thinking. Um. We should ship" keeps both full stops.
            val terminators = word.trailing.filter { it in "!?.。！？।۔" }
            val previous = kept.lastOrNull()
            if (terminators.isNotEmpty() && previous != null) {
                if (previous.trailing.none { it in terminators }) {
                    previous.trailing += terminators
                }
            } else if (word.trailing.contains(',') && previous?.trailing?.endsWith(",") == true) {
                // A filler set off by commas on both sides takes both with it:
                // "I think, um, we should" is "I think we should", not
                // "I think, we should".
                previous.trailing = previous.trailing.dropLast(1)
            }
        }
        return joined(kept)
    }

    // endregion

    // region False starts

    /**
     * Words whose immediate repeat is a stutter rather than a sentence.
     * Closed-class only: an article or a pronoun said twice in a row is the
     * speaker restarting, where a repeated content word ("very very") is
     * emphasis the speaker meant.
     */
    private val FUNCTION_WORDS = setOf(
        "the", "a", "an", "and", "but", "so", "or", "to", "of", "in", "on", "at",
        "for", "with", "from", "by", "as", "if", "then", "than",
        "i", "you", "we", "they", "he", "she", "it", "this", "these", "those",
        "there", "my", "your", "our", "their", "his", "her", "its",
        "is", "are", "was", "were", "am", "be", "been",
        "do", "does", "did", "have", "has",
        "can", "could", "will", "would", "should", "must",
        "what", "when", "where", "why", "who", "how",
    )

    /**
     * Doubles that are ordinary English. "The thing that that man said" and
     * "she had had enough" are both correct, and both look exactly like a
     * stutter to a rule that only compares two words.
     */
    private val LEGITIMATE_DOUBLES = setOf("that", "had")

    /**
     * Longest false start collapsed. Beyond this it is a repeated sentence,
     * which [TranscriptSanitizer] already has thresholds for.
     */
    private const val MAX_STUTTER_WORDS = 4

    /**
     * Collapses a phrase said twice in a row — "we should we should probably".
     *
     * Only an immediate repeat, and only the second copy is kept, because it is
     * the copy the speaker carried on from: "we should, we should probably
     * ship" has the comma on the abandoned half and the sentence on the other.
     */
    private fun collapseStutters(text: String, punctuation: SentencePunctuation): String {
        val words = split(text)
        if (words.size < 2) return text

        val result = mutableListOf<Word>()
        var index = 0
        while (index < words.size) {
            // Longest first, so "we should we should" is one phrase twice over
            // rather than two separate doubled words.
            val length = (minOf(MAX_STUTTER_WORDS, (words.size - index) / 2) downTo 1)
                .firstOrNull { matchesRepeat(words, index, it, punctuation) }
            if (length == null) {
                result += words[index]
                index++
                continue
            }
            val leading = words[index].leading
            val second = words.subList(index + length, index + length * 2).map { it.copy() }
            if (leading.isNotEmpty() && second.first().leading.isEmpty()) {
                second.first().leading = leading
            }
            result += second
            index += length * 2
        }
        return joined(result)
    }

    private fun matchesRepeat(
        words: List<Word>,
        index: Int,
        length: Int,
        punctuation: SentencePunctuation,
    ): Boolean {
        if (index + length * 2 > words.size) return false
        for (offset in 0 until length) {
            val first = words[index + offset]
            val second = words[index + length + offset]
            if (first.isProtected || first.key.isEmpty() || first.key != second.key) return false
            // A sentence ended between the copies, so the second one starts a
            // new thought: "I went home. Home is quiet."
            if (ends(first, punctuation)) return false
        }
        if (length == 1) {
            val key = words[index].key
            if (key !in FUNCTION_WORDS || key in LEGITIMATE_DOUBLES) return false
        }
        return true
    }

    private fun ends(word: Word, punctuation: SentencePunctuation): Boolean =
        word.trailing.any { it in punctuation.terminators }

    // endregion

    // region Marks that are already there

    /**
     * Normalizes the punctuation the model emitted: spacing around it, runs of
     * it, and pairs of marks that cannot both be right. Nothing here decides
     * where a sentence ends; it only tidies the decisions already made.
     */
    private fun repairMarks(text: String, punctuation: SentencePunctuation): String {
        var result = text.replace(Regex("\\s+"), " ").trim()

        // Never a space before a mark.
        result = result.replace(Regex("\\s+([.!?。！？।۔،,;:、၊…])"), "$1")
        // A run of one separator is one separator.
        result = result.replace(Regex("([,;:、])\\1+"), "$1")
        // A separator touching a terminator: the terminator wins, whichever
        // order the model put them in.
        result = result.replace(Regex("[,;:、]\\s*([.!?。！？।۔])"), "$1")
        result = result.replace(Regex("([.!?。！？।۔])\\s*[,;:、]"), "$1")
        // Shouting and stammering. Four or more stops is an ellipsis that got
        // away; three stays an ellipsis.
        result = result.replace(Regex("!{2,}"), "!")
        result = result.replace(Regex("\\?{2,}"), "?")
        result = result.replace(Regex("\\.{4,}"), "...")
        result = result.replace(Regex("([!?])\\.+"), "$1")
        result = result.replace(Regex("\\.([!?])"), "$1")

        if (punctuation.join == " ") {
            // Always a space after a mark, unless another mark follows it —
            // that is an ellipsis or a quoted close, not two sentences.
            result = result.replace(Regex("([,;:])(?=[^\\s,;:.!?…\\)\\]\"”’])"), "$1 ")
            result = result.replace(
                Regex("([.!?])(?=[\\p{L}\\p{N}${ProtectedSpans.OPEN}])"),
                "$1 ",
            )
        }

        // A mark with nothing in front of it, and a separator with nothing
        // after it, are both left over from something that was removed.
        result = result.replace(Regex("^[\\s,;:.!?、。…]+"), "")
        result = result.replace(Regex("[,;:、]+\\s*$"), "")
        return result.trim()
    }

    // endregion

    // region Marks that are missing

    /**
     * Phrases that start a new thought when they turn up mid-flow. Each is a
     * discourse marker with no other job, which is what makes a sentence break
     * in front of it safe.
     */
    private val SENTENCE_STARTERS = listOf(
        listOf("okay"), listOf("ok"), listOf("alright"), listOf("all", "right"),
        listOf("anyway"), listOf("anyhow"), listOf("by", "the", "way"),
        listOf("in", "any", "case"),
    )

    /**
     * Markers that take a comma when they open a sentence. `so` and `well` are
     * missing on purpose: both open a sentence far more often without one.
     */
    private val OPENERS_TAKING_COMMA = listOf(
        listOf("okay"), listOf("ok"), listOf("alright"), listOf("all", "right"),
        listOf("anyway"), listOf("anyhow"), listOf("however"), listOf("actually"),
        listOf("by", "the", "way"), listOf("in", "fact"), listOf("of", "course"),
        listOf("for", "example"),
    )

    /** Conjunctions that take a comma when they join two full clauses. */
    private val CLAUSE_CONJUNCTIONS = setOf("but", "so", "yet", "however")

    /**
     * Words that can open a clause of their own. The test for "is a full clause
     * coming" is "does a subject start right here", which these are the common
     * ones for.
     */
    private val CLAUSE_STARTERS = setOf(
        "i", "im", "ive", "ill", "id", "you", "youre", "youve", "youll", "youd",
        "we", "weve", "well", "wed", "were", "they", "theyre", "theyve", "theyll",
        "he", "hes", "hed", "she", "shes", "shed", "it", "its", "thats", "this",
        "there", "theres", "my", "your", "our", "their", "his", "her",
        "the", "a", "an", "nobody", "everyone", "someone", "people", "lets",
        "maybe", "now", "then", "today", "tomorrow", "yesterday",
    )

    /** "so that", "but as" — a phrase, not a new clause. */
    private val NOT_CLAUSE_STARTERS = setOf("that", "much", "many", "far", "long", "as")

    /**
     * After one of these, `okay` and `alright` are adjectives describing
     * something, not somebody starting a sentence.
     */
    private val COPULAS = setOf(
        "is", "are", "was", "were", "am", "be", "been", "being",
        "seem", "seems", "seemed", "look", "looks", "looked",
        "feel", "feels", "felt", "sound", "sounds", "sounded",
        "not", "quite", "pretty", "totally", "perfectly", "really",
        "its", "thats", "everything", "all", "more", "less", "about",
    )

    private val AUXILIARIES = setOf(
        "do", "does", "did", "dont", "doesnt", "didnt",
        "is", "are", "was", "were", "isnt", "arent", "wasnt", "werent",
        "can", "cant", "could", "couldnt", "will", "wont", "would", "wouldnt",
        "should", "shouldnt", "shall", "may", "might", "must",
        "have", "has", "had", "havent", "hasnt", "hadnt", "am",
    )

    private val QUESTION_WORDS = setOf(
        "what", "whats", "when", "whens", "where", "wheres",
        "why", "whys", "who", "whos", "whom", "whose", "how", "hows",
    )

    private val SUBJECT_PRONOUNS = setOf(
        "i", "you", "we", "they", "he", "she", "it", "there",
        "that", "this", "anyone", "anybody", "someone", "somebody", "everyone",
    )

    /**
     * Longest sentence still short enough for the question test to be worth
     * trusting. Past this a wh-word is far more often opening a noun clause.
     */
    private const val MAX_QUESTION_WORDS = 12

    private fun infer(text: String, punctuation: SentencePunctuation): String {
        val words = split(text)
        if (words.isEmpty()) return text
        splitAtStarters(words, punctuation)
        commaAfterOpeners(words, punctuation)
        commaBeforeConjunctions(words, punctuation)
        markQuestions(words, punctuation)
        return joined(words)
    }

    /**
     * Ends the sentence in front of a discourse marker that has clearly started
     * a new one. Requires a substantial unpunctuated clause behind it and a
     * clause of its own in front, so a marker inside a sentence is left alone.
     */
    private fun splitAtStarters(words: MutableList<Word>, punctuation: SentencePunctuation) {
        var index = 1
        while (index < words.size) {
            val length = match(SENTENCE_STARTERS, words, index)
            if (length == null ||
                index + length + 2 > words.size ||
                wordsSinceMark(words, index) < 4 ||
                words[index - 1].trailing.isNotEmpty() ||
                words[index - 1].key in COPULAS
            ) {
                index++
                continue
            }
            words[index - 1].trailing = punctuation.terminator
            matchCaseOfSentence(words, index)
            index += length
        }
    }

    /**
     * "Okay we should ship" — the marker opens the sentence and the rest of it
     * follows, which in writing takes a comma.
     */
    private fun commaAfterOpeners(words: MutableList<Word>, punctuation: SentencePunctuation) {
        for (index in words.indices) {
            if (!opensSentence(words, index, punctuation)) continue
            val length = match(OPENERS_TAKING_COMMA, words, index) ?: continue
            if (words[index + length - 1].trailing.isNotEmpty()) continue
            if (index + length + 2 > words.size) continue
            words[index + length - 1].trailing = punctuation.separator
        }
    }

    /**
     * "…ship it on Friday but I don't know…" — a conjunction joining two full
     * clauses takes a comma in front of it.
     */
    private fun commaBeforeConjunctions(
        words: MutableList<Word>,
        punctuation: SentencePunctuation,
    ) {
        for (index in words.indices) {
            if (index < 4 || index + 2 >= words.size) continue
            if (words[index].key !in CLAUSE_CONJUNCTIONS) continue
            if (words[index].leading.isNotEmpty() || words[index].trailing.isNotEmpty()) continue
            if (words[index - 1].trailing.isNotEmpty()) continue
            if (wordsSinceMark(words, index) < 4) continue
            val next = words[index + 1].key
            if (next !in CLAUSE_STARTERS || next in NOT_CLAUSE_STARTERS) continue
            words[index - 1].trailing = punctuation.separator
        }
    }

    /**
     * Puts a question mark on a sentence that asks something. Applies to a
     * sentence with no terminator at all and to one the model closed with a
     * plain full stop; `!` is left alone, because someone chose it.
     */
    private fun markQuestions(words: MutableList<Word>, punctuation: SentencePunctuation) {
        for (sentence in sentences(words, punctuation)) {
            val last = words[sentence.last]
            val closing = last.trailing.lastOrNull()
            val open = closing == null || closing !in punctuation.terminators
            if (!open && closing.toString() != punctuation.terminator) continue
            if (sentence.count() > MAX_QUESTION_WORDS) continue
            if (!isQuestion(words, sentence)) continue
            if (!open) words[sentence.last].trailing = last.trailing.dropLast(1)
            words[sentence.last].trailing += punctuation.question
        }
    }

    private fun isQuestion(words: List<Word>, sentence: IntRange): Boolean {
        val keys = sentence.map { words[it].key }
        if (keys.size < 2) return false
        if (keys[0] in QUESTION_WORDS) {
            // "What time is it" asks; "What I meant was simple" states. Both
            // reach an auxiliary, but only the second puts a subject in front
            // of it — the wh-word is that clause's own subject or object.
            for (offset in 1 until minOf(keys.size, 4)) {
                if (keys[offset] in SUBJECT_PRONOUNS) return false
                if (keys[offset] in AUXILIARIES) return true
            }
            return false
        }
        return keys[0] in AUXILIARIES && keys[1] in SUBJECT_PRONOUNS
    }

    // endregion

    // region Inference helpers

    /** Whether the phrase at [index] is one of [phrases], and how long it is. */
    private fun match(phrases: List<List<String>>, words: List<Word>, index: Int): Int? {
        for (phrase in phrases) {
            if (index + phrase.size > words.size) continue
            if (phrase.withIndex().any { (offset, key) -> words[index + offset].key != key }) {
                continue
            }
            // A mark inside the phrase means it is not being said as one.
            if ((0 until phrase.size - 1).any { words[index + it].trailing.isNotEmpty() }) continue
            return phrase.size
        }
        return null
    }

    private fun opensSentence(
        words: List<Word>,
        index: Int,
        punctuation: SentencePunctuation,
    ): Boolean = index == 0 || ends(words[index - 1], punctuation)

    /**
     * How many words back to the last punctuation of any kind. This is the
     * stand-in for "is there a whole clause behind me": a rule that would add a
     * mark four words after the last one is guessing, not repairing.
     */
    private fun wordsSinceMark(words: List<Word>, index: Int): Int {
        var count = 0
        var cursor = index - 1
        while (cursor >= 0) {
            count++
            if (words[cursor].trailing.isNotEmpty()) break
            cursor--
        }
        return count
    }

    private fun sentences(words: List<Word>, punctuation: SentencePunctuation): List<IntRange> {
        val result = mutableListOf<IntRange>()
        var start = 0
        for (index in words.indices) {
            if (!ends(words[index], punctuation)) continue
            result += start..index
            start = index + 1
        }
        if (start < words.size) result += start..words.lastIndex
        return result
    }

    /**
     * Capitalizes a sentence this stage just created, but only when the
     * sentence it was split out of was capitalized too.
     *
     * The alternative — always capitalizing — puts a stray capital in the
     * middle of `clean` style output, which exists to flatten exactly that.
     * Following the text's own convention is right on both routes: a gateway
     * transcript arrives already cased and gets a capital, a raw local one
     * arrives lowercase and is left for [TranscriptStyler] to decide about.
     */
    private fun matchCaseOfSentence(words: MutableList<Word>, index: Int) {
        var cursor = index - 1
        var sentenceStart = index
        while (cursor >= 0) {
            if (words[cursor].trailing.isNotEmpty() && cursor != index - 1) break
            sentenceStart = cursor
            cursor--
        }
        if (!words[sentenceStart].startsUppercase) return
        val text = words[index].text
        val position = text.indexOfFirst { it.isLetter() }
        if (position < 0 || !text[position].isLowerCase()) return
        words[index].text = text.replaceRange(
            position,
            position + 1,
            text[position].uppercaseChar().toString(),
        )
    }

    // endregion
}
