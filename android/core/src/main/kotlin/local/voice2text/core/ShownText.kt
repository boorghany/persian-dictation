package local.voice2text.core

/**
 * What the user saw of the utterance in progress, so Google's second thoughts don't take it away.
 * Port of the Mac app's ShownText.swift; see there for the full rationale.
 *
 * - Interim (A): text 2+ words shorter than what was shown is not displayed; the shown text stays
 *   until Google catches up. Small revisions of the last word go through.
 * - Final (B): if the final is the beginning of the shown text and 2+ shown words are missing at its
 *   end, they are put back (replaying the audio doesn't help: Google drops the same words again). Google
 *   sometimes sends such words in the next utterance after all, so the next session's text starting
 *   with them has them removed ("echo").
 */
class ShownText {
    /** The text shown for the utterance in progress. */
    var shown = ""
        private set
    /** Whether the last interim was shorter and the shown text was kept instead. */
    var keeping = false
        private set
    /** Words added to the last final, which may still come back from the next session. */
    private var echo = ""

    /** Takes a (repaired) interim, returns the text to display. */
    fun interim(raw: String): String {
        val text = removingEcho(raw)
        keeping = wordCount(text) <= wordCount(shown) - 2
        if (keeping) return shown
        shown = text
        return text
    }

    /** Takes a (repaired) final, returns the text to deliver and the words put back, if any. The utterance is over afterwards. */
    fun final(raw: String): Pair<String, String?> {
        val text = removingEcho(raw)
        echo = ""
        val seen = shown
        shown = ""
        keeping = false
        val final = squeezed(text)
        val last = text.split(' ').lastOrNull { it.isNotEmpty() } ?: return text to null
        // Everything but the final's last word (which Google may have revised) must match what was shown.
        val common = commonPrefix(text, seen)
        val beforeLast = final.length - squeezed(last).length
        if (common < beforeLast) return text to null
        // After the shown word the final's last word stands for (شدن for شدند, رفتیم for رفتم).
        val missing = words(seen, maxOf(common, beforeLast + 1))
        if (wordCount(missing) < 2) return text to null
        echo = missing
        return "$text $missing" to missing
    }

    /** The utterance ends without a usable final (a freeze that replays didn't fix): what was shown, if more than a single word. */
    fun abandon(): String? {
        val seen = shown
        shown = ""
        keeping = false
        echo = ""
        if (wordCount(seen) < 2) return null
        echo = seen
        return seen
    }

    /** An utterance was dropped (a duplicate final from the pre-roll): start over. */
    fun reset() {
        shown = ""
        keeping = false
    }

    /** [text] without the echo at its start. While the text could still be the start of the echo, nothing is shown; once it is clearly something else, the echo is forgotten. */
    private fun removingEcho(text: String): String {
        if (echo.isEmpty()) return text
        val said = squeezed(text)
        val common = commonPrefix(text, echo)
        if (common == said.length) return ""
        // All of the echo, give or take a revised ending of its last word.
        if (common >= maxOf(4, squeezed(echo).length - 2)) return words(text, common)
        echo = ""
        return text
    }

    private companion object {
        fun wordCount(s: String) = s.split(' ').count { it.isNotEmpty() }

        /** Text without spaces and ZWNJ, so "دو تا" = "دوتا" and "می‌خوام" = "میخوام". */
        fun squeezed(s: String) = s.filter { it != ' ' && it != '‌' }

        fun commonPrefix(a: String, b: String): Int {
            val x = squeezed(a)
            val y = squeezed(b)
            var n = 0
            while (n < x.length && n < y.length && x[n] == y[n]) n++
            return n
        }

        /** The words of [text] after its first [length] squeezed characters. A word that the boundary falls inside of counts as already delivered. */
        fun words(text: String, length: Int): String {
            val words = text.split(' ').filter { it.isNotEmpty() }
            var seen = 0
            for ((i, word) in words.withIndex()) {
                if (seen >= length) return words.subList(i, words.size).joinToString(" ")
                seen += squeezed(word).length
            }
            return ""
        }
    }
}
