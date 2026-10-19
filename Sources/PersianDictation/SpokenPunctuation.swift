import Foundation

/// Turns spoken punctuation commands ("نقطه", "ویرگول", …) into the actual symbols.
enum SpokenPunctuation {
    /// Longer phrases first so they win over any shorter phrase they contain.
    private static let persian: [(phrase: String, symbol: String)] = [
        ("پاراگراف بعد", "\n\n"),
        ("پاراگراف بد", "\n\n"), // Google often hears «بعد» as «بد»
        ("علامت سوال", "؟"),
        ("علامت سؤال", "؟"),
        ("خط بعد", "\n"),
        ("خط بد", "\n"), // Google often hears «بعد» as «بد»
        ("پرانتز باز", "("),
        ("پرانتز بسته", ")"),
        ("دو نقطه", ":"),
        ("دونقطه", ":"),
        ("نقطه", "."),
        ("ویرگول", "،"),
    ]

    /// Whether `word` begins a multi-word phrase ("علامت" of "علامت سوال"), so text ending in it may
    /// still turn into a symbol.
    static func startsPhrase(_ word: String, language: String) -> Bool {
        guard language.hasPrefix("fa") else { return false }
        return persian.contains { $0.phrase.hasPrefix(word + " ") }
    }

    /// Attach to the previous word: no space before, one after when a word follows.
    private static let symbols = ".،؟:)"

    /// Characters that attach to the previous word, so no space goes before them.
    static func attachesToPrevious(_ text: String) -> Bool {
        guard let first = text.first else { return false }
        return symbols.contains(first) || first == "\n"
    }

    /// Text ending like this attaches to whatever comes next, so no space goes after it.
    static func attachesToNext(_ text: String) -> Bool {
        text.hasSuffix("\n") || text.hasSuffix("(")
    }

    static func apply(_ text: String, language: String) -> String {
        guard language.hasPrefix("fa") else { return text }
        var s = text
        // A "word" here can contain letters, combining marks and ZWNJ, so "نقطه‌ی" is left alone.
        let notWordBefore = "(?<![\\p{L}\\p{M}\\u200C])"
        let notWordAfter = "(?![\\p{L}\\p{M}\\u200C])"
        for (phrase, symbol) in persian {
            let words = phrase.split(separator: " ")
                .map { NSRegularExpression.escapedPattern(for: String($0)) }
                .joined(separator: "\\s+")
            s = s.replacingOccurrences(of: notWordBefore + words + notWordAfter,
                                       with: NSRegularExpression.escapedTemplate(for: symbol),
                                       options: .regularExpression)
        }
        // No space before punctuation.
        s = s.replacingOccurrences(of: "[ \\t]+([\(symbols)])", with: "$1", options: .regularExpression)
        // One space after punctuation when a word follows (but leave decimals like 3.5 alone).
        s = s.replacingOccurrences(of: "([\(symbols)])(?=\\p{L})", with: "$1 ", options: .regularExpression)
        // "(" attaches to the next word: a space before it (unless at the start), none after.
        s = s.replacingOccurrences(of: "\\([ \\t]+", with: "(", options: .regularExpression)
        s = s.replacingOccurrences(of: "(?<=[^\\s(])\\(", with: " (", options: .regularExpression)
        // No spaces around line breaks.
        s = s.replacingOccurrences(of: "[ \\t]*\\n[ \\t]*", with: "\n", options: .regularExpression)
        return s.trimmingCharacters(in: CharacterSet(charactersIn: " \t"))
    }
}
