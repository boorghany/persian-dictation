package local.voice2text.core

import java.util.regex.Matcher
import java.util.regex.Pattern

/** Turns spoken punctuation commands ("نقطه", "ویرگول", …) into the actual symbols. */
object SpokenPunctuation {
    /** Longer phrases first so they win over any shorter phrase they contain. */
    private val persian = listOf(
        "پاراگراف بعد" to "\n\n",
        "پاراگراف بد" to "\n\n", // Google often hears «بعد» as «بد»
        "علامت سوال" to "؟",
        "علامت سؤال" to "؟",
        "خط بعد" to "\n",
        "خط بد" to "\n", // Google often hears «بعد» as «بد»
        "دو نقطه" to ":",
        "دونقطه" to ":",
        "نقطه" to ".",
        "ویرگول" to "،",
    )

    private const val SYMBOLS = ".،؟:"

    // A "word" here can contain letters, combining marks and ZWNJ, so "نقطه‌ی" is left alone.
    private const val NOT_WORD_BEFORE = "(?<![\\p{L}\\p{M}\\u200C])"
    private const val NOT_WORD_AFTER = "(?![\\p{L}\\p{M}\\u200C])"

    private val commands = persian.map { (phrase, symbol) ->
        val words = phrase.split(' ').joinToString("\\s+") { Pattern.quote(it) }
        Pattern.compile(NOT_WORD_BEFORE + words + NOT_WORD_AFTER) to Matcher.quoteReplacement(symbol)
    }

    /** Characters that attach to the previous word, so no space goes before them. */
    fun attachesToPrevious(text: String): Boolean {
        val first = text.firstOrNull() ?: return false
        return first in SYMBOLS || first == '\n'
    }

    fun apply(text: String, language: String): String {
        if (!language.startsWith("fa")) return text
        var s = text
        for ((pattern, replacement) in commands) s = pattern.matcher(s).replaceAll(replacement)
        // No space before punctuation.
        s = s.replace(Regex("[ \\t]+([$SYMBOLS])"), "$1")
        // One space after punctuation when a word follows (but leave decimals like 3.5 alone).
        s = s.replace(Regex("([$SYMBOLS])(?=\\p{L})"), "$1 ")
        // No spaces around line breaks.
        s = s.replace(Regex("[ \\t]*\\n[ \\t]*"), "\n")
        return s.trim(' ', '\t')
    }
}
