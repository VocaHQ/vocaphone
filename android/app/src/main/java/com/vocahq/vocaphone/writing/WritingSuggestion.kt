package com.vocahq.vocaphone.writing

/**
 * One Writing check suggestion, Grammarly's card in data: what to change,
 * into what, which of the four groups it belongs to, and why.
 *
 * [start] and [end] are the UTF-16 range [replacement] replaces, whitespace
 * included where a word is added or removed; [markStart] and [markEnd] are
 * the word to underline. A null [replacement] is information only (passive
 * voice, a long sentence): there is nothing to apply.
 */
data class WritingSuggestion(
    val start: Int,
    val end: Int,
    val original: String,
    val replacement: String?,
    val markStart: Int,
    val markEnd: Int,
    val kind: String,
    val category: WritingCategory,
    val title: String,
    val message: String,
    /** Spelling, punctuation and the like: safe to accept all at once. */
    val mechanical: Boolean,
    val source: Source,
    /** The rule's id for [Source.RULE]. */
    val rule: String? = null,
) {
    enum class Source { MODEL, RULE, TEXT }

    val shownOriginal: String get() = original.trim()
    val shownReplacement: String? get() = replacement?.trim()

    /** What a chip says: the words as typed and what they become. */
    val label: String
        get() {
            val replacement = shownReplacement ?: return title
            return when {
                replacement.isEmpty() -> "Remove “$shownOriginal”"
                shownOriginal.isEmpty() -> "Add “$replacement”"
                else -> "$shownOriginal → $replacement"
            }
        }

    /** The same change anywhere in a field; what dismissing one remembers. */
    val key: String get() = "$kind|${WritingToken.normalize(shownOriginal)}|${shownReplacement?.let(WritingToken::normalize)}"

    val isActionable: Boolean get() = replacement != null

    fun overlaps(other: WritingSuggestion): Boolean {
        if (start < other.end && other.start < end) return true
        if (start == end && other.start <= start && start <= other.end) return true
        if (other.start == other.end && start <= other.start && other.start <= end) return true
        return false
    }
}

/**
 * Turns "these tokens become this text" into a [WritingSuggestion]'s ranges,
 * the same way for the rules and for the model's edits.
 */
internal object WritingEdits {
    /**
     * Removed tokens `[from, to)` of [tokens] become [inserted] (already
     * spaced, nothing around it). [floor] is the first token the edit may
     * reach back to for its whitespace: the sentence's first token.
     */
    fun build(
        text: String,
        tokens: List<WritingToken>,
        floor: Int,
        from: Int,
        to: Int,
        inserted: String,
    ): Edit {
        val removed = to > from
        return when {
            removed && inserted.isNotEmpty() -> Edit(tokens[from].start, tokens[to - 1].end, inserted, tokens[from].start, tokens[to - 1].end)
            removed -> {
                // Take the space before the word with it, or after it at the start.
                if (from > floor) {
                    Edit(tokens[from - 1].end, tokens[to - 1].end, "", tokens[from].start, tokens[to - 1].end)
                } else if (to < tokens.size) {
                    Edit(tokens[from].start, tokens[to].start, "", tokens[from].start, tokens[to - 1].end)
                } else {
                    Edit(tokens[from].start, tokens[to - 1].end, "", tokens[from].start, tokens[to - 1].end)
                }
            }
            from > floor -> {
                val anchor = tokens[from - 1]
                val glue = if (WritingText.isPunctuation(inserted.take(1))) "" else " "
                Edit(anchor.end, anchor.end, glue + inserted, anchor.start, anchor.end)
            }
            else -> {
                val next = tokens[from]
                Edit(next.start, next.start, "$inserted ", next.start, next.end)
            }
        }.also { check(it.start <= it.end && it.end <= text.length) }
    }

    data class Edit(val start: Int, val end: Int, val replacement: String, val markStart: Int, val markEnd: Int)

    /** Fills `{name}` placeholders from [values]; unknown names become empty. */
    fun format(template: String, values: Map<String, String>): String =
        PLACEHOLDER.replace(template) { values[it.groupValues[1]] ?: "" }

    private val PLACEHOLDER = Regex("\\{(\\w+)\\}")
}
