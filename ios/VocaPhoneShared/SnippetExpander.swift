import Foundation

protocol SnippetExpanding {
    func expand(in text: String, using snippets: [Snippet]) -> String
}

/// Expands every snippet trigger found in a finished transcript, in one pass.
///
/// One combined regex rather than looping snippet-by-snippet, so an expansion
/// that happens to contain another trigger is never itself re-expanded — a
/// second pass would see it as ordinary dictated text.
final class SnippetExpander: SnippetExpanding {
    func expand(in text: String, using snippets: [Snippet]) -> String {
        let candidates = snippets.filter {
            !$0.trigger.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        }
        guard !candidates.isEmpty else { return text }

        // Longest first, so "good morning team" wins over "good morning"
        // rather than the shorter trigger firing on a substring of it.
        let ordered = candidates.sorted { $0.trigger.count > $1.trigger.count }

        let pattern = ordered.map { "(\(boundaryPattern(for: $0.trigger)))" }.joined(separator: "|")
        guard let regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive])
        else { return text }

        let nsText = text as NSString
        let matches = regex.matches(in: text, range: NSRange(location: 0, length: nsText.length))
        guard !matches.isEmpty else { return text }

        var result = text
        // Reverse order, so replacing a later match never invalidates the
        // range of a match still waiting to be replaced.
        for match in matches.reversed() {
            guard let groupIndex = matchedGroup(match), groupIndex - 1 < ordered.count,
                  let range = Range(match.range, in: result)
            else { continue }
            result.replaceSubrange(range, with: ordered[groupIndex - 1].expansion)
        }
        return result
    }

    private func matchedGroup(_ match: NSTextCheckingResult) -> Int? {
        for index in 1..<match.numberOfRanges where match.range(at: index).location != NSNotFound {
            return index
        }
        return nil
    }

    /// A trigger that starts or ends on a letter, digit, or underscore uses
    /// `\b`; one that starts or ends on punctuation — "brb", ")))" — cannot,
    /// because `\b` only fires at a word/non-word boundary and punctuation is
    /// non-word on both sides. A non-whitespace lookaround matches it at
    /// either edge of the string as well as between words.
    private func boundaryPattern(for trigger: String) -> String {
        let escaped = NSRegularExpression.escapedPattern(for: trigger)
        let left = isWordCharacter(trigger.first) ? "\\b" : "(?<!\\S)"
        let right = isWordCharacter(trigger.last) ? "\\b" : "(?!\\S)"
        return left + escaped + right
    }

    private func isWordCharacter(_ character: Character?) -> Bool {
        guard let character else { return false }
        return character.isLetter || character.isNumber || character == "_"
    }
}
