import Foundation

/// What the user saw of the utterance in progress, so Google's second thoughts don't take it away.
///
/// Google sometimes drops the last words of the interim text (noise, a word it isn't sure of, a long
/// sentence) and then leaves them out of its final too, although it had shown them. Replaying the audio
/// into a fresh session doesn't help: it drops the same words again (2026-10-27, 3 of 3 replays). And
/// during a freeze recovery the new session's text starts again from nothing.
///
/// - Interim (A): text 2+ words shorter than what was shown is not displayed; the shown text stays
///   until Google catches up. Small revisions of the last word go through.
/// - Final (B): if the final is the beginning of the shown text and 2+ shown words are missing at its
///   end, they are put back. Google sometimes sends such words in the next utterance after all, so the
///   next session's text starting with them has them removed ("echo").
struct ShownText {
    /// The text shown for the utterance in progress.
    private(set) var shown = ""
    /// Whether the last interim was shorter and the shown text was kept instead.
    private(set) var keeping = false
    /// Words added to the last final, which may still come back from the next session.
    private var echo = ""

    /// Takes a (repaired) interim, returns the text to display.
    mutating func interim(_ text: String) -> String {
        let text = removingEcho(text)
        keeping = Self.wordCount(text) <= Self.wordCount(shown) - 2
        if keeping { return shown }
        shown = text
        return text
    }

    /// Takes a (repaired) final, returns the text to deliver and the words put back, if any.
    /// The utterance is over afterwards.
    mutating func final(_ text: String) -> (text: String, restored: String?) {
        let text = removingEcho(text)
        echo = ""
        let seen = shown
        shown = ""
        keeping = false
        let final = Self.squeezed(text)
        guard let last = text.split(separator: " ").last else { return (text, nil) }
        // Everything but the final's last word (which Google may have revised) must match what was shown.
        let common = Self.commonPrefix(text, seen)
        let beforeLast = final.count - Self.squeezed(String(last)).count
        guard common >= beforeLast else { return (text, nil) }
        // After the shown word the final's last word stands for (شدن for شدند, رفتیم for رفتم).
        let missing = Self.words(of: seen, after: max(common, beforeLast + 1))
        guard Self.wordCount(missing) >= 2 else { return (text, nil) }
        echo = missing
        return (text + " " + missing, missing)
    }

    /// The utterance ends without a usable final (a freeze that replays didn't fix): what was shown, if
    /// it is more than a single word (which is more likely noise).
    mutating func abandon() -> String? {
        let seen = shown
        shown = ""
        keeping = false
        echo = ""
        guard Self.wordCount(seen) >= 2 else { return nil }
        echo = seen
        return seen
    }

    /// An utterance was dropped (a duplicate final from the pre-roll): start over.
    mutating func reset() {
        shown = ""
        keeping = false
    }

    /// `text` without the echo at its start. While the text could still be the start of the echo, nothing
    /// is shown; once it is clearly something else, the echo is forgotten.
    private mutating func removingEcho(_ text: String) -> String {
        guard !echo.isEmpty else { return text }
        let said = Self.squeezed(text)
        let common = Self.commonPrefix(text, echo)
        if common == said.count { return "" }
        let echoLength = Self.squeezed(echo).count
        // All of the echo, give or take a revised ending of its last word.
        if common >= max(4, echoLength - 2) { return Self.words(of: text, after: common) }
        echo = ""
        return text
    }

    private static func wordCount(_ s: String) -> Int {
        s.split(separator: " ").count
    }

    /// Text without spaces and ZWNJ, so "دو تا" = "دوتا" and "می‌خوام" = "میخوام". (By scalars: Swift
    /// keeps a ZWNJ in the same Character as the letter before it.)
    private static func squeezed(_ s: String) -> [Unicode.Scalar] {
        s.unicodeScalars.filter { $0 != " " && $0 != "\u{200C}" }
    }

    private static func commonPrefix(_ a: String, _ b: String) -> Int {
        zip(squeezed(a), squeezed(b)).prefix { $0 == $1 }.count
    }

    /// The words of `text` after its first `length` squeezed scalars. A word that the boundary falls
    /// inside of counts as already delivered.
    private static func words(of text: String, after length: Int) -> String {
        let words = text.split(separator: " ")
        var seen = 0
        for (i, word) in words.enumerated() {
            if seen >= length { return words[i...].joined(separator: " ") }
            seen += squeezed(String(word)).count
        }
        return ""
    }
}
