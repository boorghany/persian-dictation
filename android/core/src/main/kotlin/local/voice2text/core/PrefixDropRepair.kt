package local.voice2text.core

/**
 * Works around a Google recognizer bug: when it revises a hypothesis, it sometimes drops the
 * first one or two letters of a word it had already recognized correctly
 * (e.g. "لاجیک" → "اجیک", "خیلی" → "یلی").
 *
 * We remember every interim hypothesis of the current utterance. When a later version has a
 * word that equals an earlier word minus 1–2 leading letters, in the same place (same
 * neighbouring word), the earlier, longer word is restored.
 */
class PrefixDropRepair(private val log: (String) -> Unit = {}) {
    private val history = mutableListOf<List<String>>()

    /** Call with each interim hypothesis. Returns the repaired text to display. */
    fun interim(text: String): String {
        val repaired = repair(text)
        val words = words(text)
        if (words.isNotEmpty() && history.lastOrNull() != words) history += words
        return repaired
    }

    /** Call with a final result. Returns the repaired text and clears the history. */
    fun final(text: String): String {
        val repaired = repair(text)
        history.clear()
        return repaired
    }

    private fun repair(text: String): String {
        val words = words(text).toMutableList()
        if (words.isEmpty() || history.isEmpty()) return text
        var changed = false
        for (k in words.indices) {
            val restored = restoredWord(words, k) ?: continue
            log("prefix-drop repair: \"${words[k]}\" → \"$restored\"")
            words[k] = restored
            changed = true
        }
        return if (changed) words.joinToString(" ") else text
    }

    private fun restoredWord(words: List<String>, k: Int): String? {
        val word = bare(words[k])
        val previous = words.getOrNull(k - 1)
        val next = words.getOrNull(k + 1)
        // Google can erode a word in several steps (علی → لی → ی), so look through the whole
        // history and restore the longest earlier version, not just the previous step.
        var best: String? = null
        var bestLength = 0
        for (earlier in history.asReversed()) {
            for (j in earlier.indices) {
                val candidate = earlier[j]
                val bareCandidate = bare(candidate)
                val dropped = bareCandidate.length - word.length
                if (dropped !in 1..2 || !bareCandidate.endsWith(word) || bareCandidate.length <= bestLength) continue
                // Require the same position: the neighbouring word must match.
                val samePlace = previous == earlier.getOrNull(j - 1) || (next != null && next == earlier.getOrNull(j + 1))
                if (samePlace) {
                    best = candidate
                    bestLength = bareCandidate.length
                }
            }
        }
        return best
    }

    companion object {
        /** The word without ZWNJ (half-space), so "می‌تونی" and "میتونی" compare equal. */
        private fun bare(word: String) = word.replace("‌", "")

        private fun words(text: String) = text.split(' ', '\t').filter { it.isNotEmpty() }
    }
}
