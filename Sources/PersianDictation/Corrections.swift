import Foundation

/// Fixed corrections for words Google keeps writing differently from how the user wants them,
/// e.g. names. Whole words only. Applied before `SpokenPunctuation`, to the preview and the final.
/// Keep in sync with `Corrections.kt` on Android.
enum Corrections {
    /// Longer phrases first so they win over any shorter phrase they contain.
    private static let persian: [(heard: String, written: String)] = [
        ("عرشیا", "ارشیا"),
        ("ارزی", "عرضی"),
        ("ارز", "عرض"),
    ]

    static func apply(_ text: String, language: String) -> String {
        guard language.hasPrefix("fa") else { return text }
        var s = text
        // A "word" here can contain letters, combining marks and ZWNJ (same rule as SpokenPunctuation).
        let notWordBefore = "(?<![\\p{L}\\p{M}\\u200C])"
        let notWordAfter = "(?![\\p{L}\\p{M}\\u200C])"
        for (heard, written) in persian {
            let words = heard.split(separator: " ")
                .map { NSRegularExpression.escapedPattern(for: String($0)) }
                .joined(separator: "\\s+")
            s = s.replacingOccurrences(of: notWordBefore + words + notWordAfter,
                                       with: NSRegularExpression.escapedTemplate(for: written),
                                       options: .regularExpression)
        }
        return s
    }
}
