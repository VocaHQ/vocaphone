import Foundation

/// The Writing check screen's two decisions, kept out of the view so they can
/// be tested: whether to check a text at all, and when to stop checking it.
/// Mirrors `writingCheckApplies` and `checkUntilCancelled` in
/// android/.../ui/WritingCheckScreen.kt.
extension WritingCheck {
    /// How many words text needs before the language test decides whether
    /// to check it. See `applies(to:isDictionaryWord:)`.
    static let languageTestMinimumWords = 6

    /// Whether the Writing check screen checks `text` at all. The rules are
    /// English ones: on a French sentence, "dont" would be "corrected" to
    /// "don't". So text is left alone only when it is clearly not English,
    /// by the test the keyboard makes, with the keyboard's word list as
    /// `isDictionaryWord`.
    ///
    /// That test is only trusted from `languageTestMinimumWords` words on. It
    /// calls text English on two marker words, or on two thirds of its words
    /// being in the list, and a misspelling or a name is a word the list does
    /// not know. Below six words a single unknown word is enough to fail it:
    /// "please recieve" is half unknown, and a lone "recieve" has no known
    /// word at all. From six on, English text can carry two (a typo and a
    /// name) and still pass, and a whole French sentence still fails. Shorter
    /// text is always checked: the cost of being wrong there is one
    /// suggestion to ignore, and the cost of skipping is a missed fix on
    /// exactly the text, a selected word or a short message, people most
    /// often bring here.
    static func applies(to text: String, isDictionaryWord: (String) -> Bool = EnglishWords.contains) -> Bool {
        guard text.contains(where: \.isLetter) else { return false }
        let words = text.split(whereSeparator: \.isWhitespace).count { $0.contains(where: \.isLetter) }
        return words < languageTestMinimumWords
            || isEnglish(text) { TranscriptRepair.isEnglish($0, isDictionaryWord: isDictionaryWord) }
    }

    /// At most one word in this many may be spelled outside a–z for text to
    /// still be English. See `isEnglish(_:isEnglish:)`.
    static let borrowedWordShare = 4

    /// `isEnglish` (the transcript test, which calls any text with an
    /// accented letter foreign) asked of `text` without its borrowed words.
    /// English borrows "café", "naïve" and "résumé", and one of them in a
    /// sentence should not switch off every check in it. So when no more than
    /// one word in `borrowedWordShare` has a letter outside a–z, those words
    /// are left out and the rest decides; more than that and the text is not
    /// English. A French or Spanish sentence with a few accents still fails,
    /// on its other words. As `WritingLanguage.isEnglish` on Android.
    static func isEnglish(_ text: String, isEnglish: (String) -> Bool) -> Bool {
        let words = text.split(whereSeparator: \.isWhitespace).filter { $0.contains(where: \.isLetter) }
        let borrowed = words.filter(isBorrowed)
        if borrowed.isEmpty { return isEnglish(text) }
        if borrowed.count * borrowedWordShare > words.count { return false }
        return isEnglish(words.filter { !isBorrowed($0) }.joined(separator: " "))
    }

    private static func isBorrowed(_ word: Substring) -> Bool {
        word.unicodeScalars.contains { scalar in
            scalar.properties.isAlphabetic && !("a"..."z").contains(scalar) && !("A"..."Z").contains(scalar)
        }
    }

    /// `check`, with a second pass after `retryAfter` if the model was still
    /// loading, that gives up when its task is cancelled: the model is asked
    /// nothing more, the retry never starts, and the answer is nil.
    func checkUntilCancelled(
        _ text: String,
        model: Model?,
        options: Options,
        retryAfter: Duration = .milliseconds(1200)
    ) async -> Report? {
        let asked: Model? = model.map { model in
            { sentence in Task.isCancelled ? nil : model(sentence) }
        }
        var report = check(text, model: asked, options: options)
        if report.modelPending {
            // The model was loading; asking started it. Once more, a moment later.
            try? await Task.sleep(for: retryAfter)
            guard !Task.isCancelled else { return nil }
            report = check(text, model: asked, options: options)
        }
        return Task.isCancelled ? nil : report
    }
}
