import Foundation

/// One Writing check suggestion, Grammarly's card in data. `start..<end` is
/// the UTF-16 range `replacement` replaces, whitespace included where a word
/// is added or removed; `markStart..<markEnd` is the word to underline. A nil
/// `replacement` is information only. Mirrors WritingSuggestion.kt.
struct WritingSuggestion: Equatable, Codable, Sendable {
    enum Source: String, Codable, Sendable { case model, rule, text }

    var start: Int
    var end: Int
    var original: String
    var replacement: String?
    var markStart: Int
    var markEnd: Int
    var kind: String
    var category: WritingCategory
    var title: String
    var message: String
    /// Spelling, punctuation and the like: safe to accept all at once.
    var mechanical: Bool
    var source: Source
    /// The rule's id for `.rule`.
    var rule: String?

    var shownOriginal: String { original.trimmingCharacters(in: .whitespacesAndNewlines) }
    var shownReplacement: String? { replacement?.trimmingCharacters(in: .whitespacesAndNewlines) }

    /// What a chip says: the words as typed and what they become.
    var label: String {
        guard let replacement = shownReplacement else { return title }
        if replacement.isEmpty { return "Remove “\(shownOriginal)”" }
        if shownOriginal.isEmpty { return "Add “\(replacement)”" }
        return "\(shownOriginal) → \(replacement)"
    }

    /// The same change anywhere in a field; what dismissing one remembers.
    var key: String {
        "\(kind)|\(WritingToken.normalize(shownOriginal))|\(shownReplacement.map(WritingToken.normalize) ?? "null")"
    }

    var isActionable: Bool { replacement != nil }

    func overlaps(_ other: WritingSuggestion) -> Bool {
        if start < other.end, other.start < end { return true }
        if start == end, other.start <= start, start <= other.end { return true }
        if other.start == other.end, start <= other.start, other.start <= end { return true }
        return false
    }
}

/// Turns "these tokens become this text" into a suggestion's ranges, the same
/// way for the rules and for the model's edits. Mirrors `WritingEdits` in Kotlin.
enum WritingEdits {
    struct Edit {
        let start: Int
        let end: Int
        let replacement: String
        let markStart: Int
        let markEnd: Int
    }

    static func build(
        _ tokens: [WritingToken], floor: Int, from: Int, to: Int, inserted: String
    ) -> Edit {
        let removed = to > from
        if removed, !inserted.isEmpty {
            return Edit(start: tokens[from].start, end: tokens[to - 1].end, replacement: inserted,
                        markStart: tokens[from].start, markEnd: tokens[to - 1].end)
        }
        if removed {
            // Take the space before the word with it, or after it at the start.
            if from > floor {
                return Edit(start: tokens[from - 1].end, end: tokens[to - 1].end, replacement: "",
                            markStart: tokens[from].start, markEnd: tokens[to - 1].end)
            }
            if to < tokens.count {
                return Edit(start: tokens[from].start, end: tokens[to].start, replacement: "",
                            markStart: tokens[from].start, markEnd: tokens[to - 1].end)
            }
            return Edit(start: tokens[from].start, end: tokens[to - 1].end, replacement: "",
                        markStart: tokens[from].start, markEnd: tokens[to - 1].end)
        }
        if from > floor {
            let anchor = tokens[from - 1]
            let glue = WritingText.isPunctuation(String(inserted.prefix(1))) ? "" : " "
            return Edit(start: anchor.end, end: anchor.end, replacement: glue + inserted,
                        markStart: anchor.start, markEnd: anchor.end)
        }
        let next = tokens[from]
        return Edit(start: next.start, end: next.start, replacement: inserted + " ",
                    markStart: next.start, markEnd: next.end)
    }

    /// Fills `{name}` placeholders from `values`; unknown names become empty.
    static func format(_ template: String, _ values: [String: String]) -> String {
        var out = ""
        var rest = Substring(template)
        while let open = rest.firstIndex(of: "{") {
            out += rest[..<open]
            let after = rest[rest.index(after: open)...]
            guard let close = after.firstIndex(of: "}") else {
                out += rest[open...]
                return out
            }
            let name = after[..<close]
            if !name.isEmpty, name.allSatisfy({ $0.isLetter || $0.isNumber || $0 == "_" }) {
                out += values[String(name)] ?? ""
            } else {
                out += rest[open...close]
            }
            rest = after[after.index(after: close)...]
        }
        return out + rest
    }
}

extension String {
    /// The UTF-16 slice `from..<to`, as `NSString` would give it.
    func utf16Slice(_ from: Int, _ to: Int) -> String {
        (self as NSString).substring(with: NSRange(location: from, length: to - from))
    }

    var utf16Length: Int { (self as NSString).length }
}
