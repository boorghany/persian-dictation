import Foundation

/// Works around a Google recognizer bug: when it revises a hypothesis, it sometimes drops the
/// first one or two letters of a word it had already recognized correctly
/// (e.g. "لاجیک" → "اجیک", "خیلی" → "یلی").
///
/// We remember every interim hypothesis of the current utterance. When a later version has a
/// word that equals an earlier word minus 1–2 leading letters, in the same place (same
/// neighbouring word), the earlier, longer word is restored.
struct PrefixDropRepair {
    private var history: [[String]] = []

    /// Call with each interim hypothesis. Returns the repaired text to display.
    mutating func interim(_ text: String) -> String {
        let repaired = repair(text)
        let words = Self.words(text)
        if !words.isEmpty, history.last != words { history.append(words) }
        return repaired
    }

    /// Call with a final result. Returns the repaired text and clears the history,
    /// since the next utterance starts fresh.
    mutating func final(_ text: String) -> String {
        let repaired = repair(text)
        history.removeAll()
        return repaired
    }

    private func repair(_ text: String) -> String {
        var words = Self.words(text)
        guard !words.isEmpty, !history.isEmpty else { return text }
        var changed = false
        for k in words.indices {
            if let restored = restoredWord(for: words, at: k) {
                DiagnosticsLog.write("prefix-drop repair: \"\(words[k])\" → \"\(restored)\"")
                words[k] = restored
                changed = true
            }
        }
        return changed ? words.joined(separator: " ") : text
    }

    private func restoredWord(for words: [String], at k: Int) -> String? {
        let word = Self.bare(words[k])
        let previous = k > 0 ? words[k - 1] : nil
        let next = k + 1 < words.count ? words[k + 1] : nil
        // Google can erode a word in several steps (علی → لی → ی), so look through the whole
        // history and restore the longest earlier version, not just the previous step.
        var best: String?
        var bestLength = 0
        for earlier in history.reversed() {
            for j in earlier.indices {
                let candidate = earlier[j]
                let bareCandidate = Self.bare(candidate)
                let dropped = bareCandidate.count - word.count
                guard (1...2).contains(dropped), bareCandidate.hasSuffix(word),
                      bareCandidate.count > bestLength else { continue }
                // Require the same position: the neighbouring word must match.
                let earlierPrevious = j > 0 ? earlier[j - 1] : nil
                let earlierNext = j + 1 < earlier.count ? earlier[j + 1] : nil
                let samePlace = (previous == earlierPrevious) || (next != nil && next == earlierNext)
                if samePlace {
                    best = candidate
                    bestLength = bareCandidate.count
                }
            }
        }
        return best
    }

    /// The word without ZWNJ (half-space), so "می‌تونی" and "میتونی" compare equal.
    private static func bare(_ word: String) -> String {
        word.replacingOccurrences(of: "\u{200C}", with: "")
    }

    private static func words(_ text: String) -> [String] {
        text.split(whereSeparator: { $0 == " " || $0 == "\t" }).map(String.init)
    }
}
