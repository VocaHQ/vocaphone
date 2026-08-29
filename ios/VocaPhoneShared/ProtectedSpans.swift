import Foundation

/// The runs of a transcript that no text stage may edit: URLs, email addresses,
/// bare domains, decimals and times, ordinals, and dotted initialisms.
///
/// Every stage that rewrites punctuation has the same problem — `example.com`
/// ends in a full stop that is not a sentence, `3.5` contains one that is not a
/// mark at all — so they all mask first, work on the masked text, and restore
/// last. The placeholder is a private-use scalar pair rather than a word, so a
/// stage that lowercases or capitalizes cannot damage it and a stage that splits
/// on letters cannot split it.
struct ProtectedSpans {
    static let open: Character = "\u{E000}"
    static let close: Character = "\u{E001}"

    let text: String
    let tokens: [String]

    /// Top-level domains a bare hostname is allowed to end in, plus any
    /// two-letter country code.
    ///
    /// Matching *any* dotted pair instead — which this did — masks
    /// `report.Then` in "I finished the report.Then I left" and the missing
    /// space after the full stop can then never be repaired. Deliberately
    /// case-sensitive: a real domain is written in lowercase, and `.To` at the
    /// start of a sentence is not one.
    private static let topLevelDomains =
        "com|org|net|edu|gov|int|mil|io|dev|app|ai|co|me|tv|cc|xyz|info|biz"
        + "|online|site|tech|store|blog|cloud|link|live|news|shop|space|wiki|zone"
        + "|[a-z]{2}"

    private static let pattern =
        "((?i:https?)://[^\\s]+[^\\s.,;:!?\\\"“”'\\)\\]]"
        + "|[\\w.+-]+@(?:[\\w-]+\\.)+[A-Za-z]{2,}"
        + "|(?:[\\w-]+\\.)+(?:" + topLevelDomains + ")\\b"
        // A path may contain dots; it may not end on the full stop that ends
        // the sentence the address is sitting in.
        + "(?:/[^\\s]*[^\\s.,;:!?\\\"“”'\\)\\]])?"
        + "|\\d+(?:[.,:/]\\d+)+"
        + "|\\d+(?i:st|nd|rd|th)\\b"
        + "|(?:[A-Za-z]\\.){2,})"

    private static let expression = try? NSRegularExpression(pattern: pattern)
    private static let placeholder = try? NSRegularExpression(
        pattern: "\u{E000}(\\d+)\u{E001}"
    )

    static func mask(_ text: String) -> ProtectedSpans {
        guard let expression else { return ProtectedSpans(text: text, tokens: []) }
        let string = text as NSString
        let matches = expression.matches(
            in: text, range: NSRange(location: 0, length: string.length)
        )
        let result = NSMutableString(string: text)
        for index in matches.indices.reversed() {
            result.replaceCharacters(
                in: matches[index].range,
                with: "\(open)\(index)\(close)"
            )
        }
        return ProtectedSpans(
            text: String(result),
            tokens: matches.map { string.substring(with: $0.range) }
        )
    }

    /// Puts the original spans back. Takes the text rather than reading
    /// ``text``, because the caller has rewritten everything around them.
    func restore(_ masked: String) -> String {
        guard let placeholder = Self.placeholder else { return masked }
        let string = masked as NSString
        let matches = placeholder.matches(
            in: masked, range: NSRange(location: 0, length: string.length)
        )
        let result = NSMutableString(string: masked)
        for match in matches.reversed() {
            let index = Int(string.substring(with: match.range(at: 1))) ?? -1
            guard tokens.indices.contains(index) else { continue }
            result.replaceCharacters(in: match.range, with: tokens[index])
        }
        return String(result)
    }

    /// Applies `transform` to everything that is not a placeholder. Case
    /// changes have to skip them: the digits inside would survive, but the
    /// scalars are the only thing ``restore(_:)`` can match on.
    static func mapOutsidePlaceholders(
        _ text: String,
        _ transform: (String) -> String
    ) -> String {
        guard let placeholder else { return transform(text) }
        let string = text as NSString
        let matches = placeholder.matches(
            in: text, range: NSRange(location: 0, length: string.length)
        )
        var result = ""
        var cursor = 0
        for match in matches {
            let before = NSRange(location: cursor, length: match.range.location - cursor)
            result += transform(string.substring(with: before))
            result += string.substring(with: match.range)
            cursor = match.range.location + match.range.length
        }
        result += transform(string.substring(from: cursor))
        return result
    }

    /// Whether `character` opens a placeholder, for the stages that walk
    /// characters and have to step over one whole.
    static func isPlaceholderStart(_ character: Character) -> Bool { character == open }
}
