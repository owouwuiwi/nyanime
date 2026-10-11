package it.lorelore.nyanime.novel

import java.text.Normalizer
import java.util.Locale

/** User input never becomes FTS syntax; folded matches retain offsets in the original text. */
internal class NovelSearchQuery(query: String) {
    val text = query.trim().take(256)
    val terms = Regex("[\\p{L}\\p{N}]+").findAll(text).map { it.value }.take(16).toList()
    val literal = terms.any { term ->
        term.any { it.code in 0x3040..0x30FF || it.code in 0x3400..0x9FFF }
    }
    val expression =
        if (literal) {
            "%${text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")}%"
        } else {
            terms.joinToString(" AND ") { "\"$it\"*" }
        }

    fun range(body: String): IntRange? {
        if (terms.isEmpty()) return null
        val folded = StringBuilder(body.length)
        val starts = ArrayList<Int>(body.length)
        val ends = ArrayList<Int>(body.length)
        var index = 0
        while (index < body.length) {
            val point = body.codePointAt(index)
            val next = index + Character.charCount(point)
            val normalized =
                if (point < 128) {
                    point.toChar().lowercaseChar().toString()
                } else {
                    fold(String(Character.toChars(point)))
                }
            if (normalized.isEmpty() && ends.isNotEmpty()) ends[ends.lastIndex] = next
            for (char in normalized) {
                folded.append(char)
                starts += index
                ends += next
            }
            index = next
        }
        val candidates = if (literal) listOf(fold(text)) else listOf(fold(text)) + terms.map(::fold)
        for (candidate in candidates.filter { it.isNotEmpty() }) {
            var from = 0
            while (from < folded.length) {
                val start = folded.indexOf(candidate, from)
                if (start < 0) break
                val previous = if (start == 0) -1 else Character.codePointBefore(folded, start)
                val tokenStart =
                    previous < 0 ||
                        !Character.isLetterOrDigit(previous) &&
                        Character.getType(previous) !in TOKEN_CONTINUATION
                if (literal || tokenStart) {
                    return starts[start] until ends[start + candidate.length - 1]
                }
                from = start + 1
            }
        }
        return null
    }

    private fun fold(text: String) =
        Normalizer.normalize(text, Normalizer.Form.NFKD).replace(MARKS, "").lowercase(Locale.ROOT)

    private companion object {
        val MARKS = Regex("\\p{M}")
        val TOKEN_CONTINUATION =
            setOf(
                Character.NON_SPACING_MARK.toInt(),
                Character.COMBINING_SPACING_MARK.toInt(),
                Character.ENCLOSING_MARK.toInt(),
                Character.PRIVATE_USE.toInt(),
            )
    }
}
