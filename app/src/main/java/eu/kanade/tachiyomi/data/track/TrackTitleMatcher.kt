package eu.kanade.tachiyomi.data.track

import java.text.Normalizer
import java.util.Locale

/** A title is an identity hint, never an identity on its own. Ambiguous results are left unbound. */
internal object TrackTitleMatcher {
    private val combiningMarks = Regex("\\p{M}+")
    private val punctuation = Regex("[^\\p{L}\\p{N}]+")
    private val whitespace = Regex("\\s+")
    private val releaseTag =
        Regex("(?i)\\s*[\\[(](?:sub(?:bed)?|dub(?:bed)?|ita|eng|en|it|raw)(?:[\\s-]+(?:ita|eng|en|it))?[\\])]\\s*$")
    private val romanNumbers = mapOf("ii" to "2", "iii" to "3", "iv" to "4", "v" to "5", "vi" to "6")
    private val ordinalSeason = Regex("\\b(\\d+)(?:st|nd|rd|th) season\\b")

    fun normalize(title: String): String {
        val cleaned = title.trim().replace(releaseTag, "").replace("&", " and ")
        val unaccented = Normalizer.normalize(cleaned, Normalizer.Form.NFKD)
            .replace(combiningMarks, "")
            .lowercase(Locale.ROOT)
        val canonical = unaccented.replace(ordinalSeason, "season \$1")
        return canonical.replace(punctuation, " ").trim().replace(whitespace, " ")
            .split(' ').joinToString(" ") { romanNumbers[it] ?: it }
    }

    fun <T> choose(
        title: String,
        candidates: List<T>,
        names: (T) -> Iterable<String>,
        id: (T) -> Long,
    ): T? {
        val wanted = normalize(title)
        if (wanted.length !in 3..256) return null
        val scored = candidates.filter { id(it) > 0 }.distinctBy(id).mapNotNull { candidate ->
            val score = names(candidate).map(::normalize).filter { it.length in 3..256 }
                .maxOfOrNull { similarity(wanted, it) } ?: return@mapNotNull null
            candidate to score
        }.sortedByDescending { it.second }
        val first = scored.firstOrNull() ?: return null
        val next = scored.getOrNull(1)?.second ?: 0.0
        // For fuzzy matches, require a long title and a clear gap to the runner-up.
        return first.first.takeIf {
            (first.second == 1.0 && next < 1.0) ||
                (wanted.length >= 12 && first.second >= 0.94 && first.second - next >= 0.10)
        }
    }

    private fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isBlank() || b.isBlank()) return 0.0
        // A season number or edition number must never be silently changed.
        val numbers = Regex("\\d+")
        if (numbers.findAll(a).map { it.value }.toList() != numbers.findAll(b).map { it.value }.toList()) return 0.0
        if (kotlin.math.abs(a.length - b.length) > (a.length * 0.06).coerceAtLeast(2.0)) return 0.0
        var previous = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val current = IntArray(b.length + 1)
            current[0] = i + 1
            for (j in b.indices) {
                current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1, previous[j] + if (a[i] == b[j]) 0 else 1)
            }
            previous = current
        }
        return 1.0 - previous[b.length].toDouble() / maxOf(a.length, b.length)
    }
}
