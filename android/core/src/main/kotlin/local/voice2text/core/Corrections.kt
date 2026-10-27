package local.voice2text.core

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Fixed corrections for words Google keeps writing differently from how the user wants them,
 * e.g. names. Whole words only. Applied before [SpokenPunctuation], to the preview and the final.
 * Keep in sync with `Corrections.swift` on the Mac.
 */
object Corrections {
    /** Longer phrases first so they win over any shorter phrase they contain. */
    private val persian = listOf(
        "عرشیا" to "ارشیا",
        "بورغانی" to "بورقانی",
        "ارزی" to "عرضی",
        "ارز" to "عرض",
        "ناد" to "نآد",
    )

    // A "word" here can contain letters, combining marks and ZWNJ (same rule as SpokenPunctuation).
    private const val NOT_WORD_BEFORE = "(?<![\\p{L}\\p{M}\\u200C])"
    private const val NOT_WORD_AFTER = "(?![\\p{L}\\p{M}\\u200C])"

    private val rules = persian.map { (heard, written) ->
        val words = heard.split(' ').joinToString("\\s+") { Pattern.quote(it) }
        Pattern.compile(NOT_WORD_BEFORE + words + NOT_WORD_AFTER) to Matcher.quoteReplacement(written)
    }

    fun apply(text: String, language: String): String {
        if (!language.startsWith("fa")) return text
        var s = text
        for ((pattern, replacement) in rules) s = pattern.matcher(s).replaceAll(replacement)
        return s
    }
}
