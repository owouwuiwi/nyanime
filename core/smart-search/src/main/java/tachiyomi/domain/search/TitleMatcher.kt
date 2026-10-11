package tachiyomi.domain.search

import kotlinx.serialization.Serializable
import java.text.Normalizer
import java.util.Locale

internal const val MIN_TITLE_MATCH_SCORE = 75
private const val EXACT_TITLE_SCORE = 100
private const val ASCII_MAX_CODE_POINT = 0x7F

@Serializable
enum class SearchMedium { VIDEO, MANGA, NOVEL }

/** Data received at runtime. A catalog suggestion is never a source identity. */
@Serializable
data class SearchTitle(
    val key: String,
    val title: String,
    val medium: SearchMedium,
    val aliases: List<String> = emptyList(),
    val origin: String = "extension",
    val sourceId: Long? = null,
) {
    val names get() = (listOf(title) + aliases).filter { it.length in 1..256 }.distinct().take(16)
}

data class TitleMatch(val item: SearchTitle, val score: Int, val numericFallback: Boolean = false)

interface TitleMatcher {
    fun score(query: String, title: String): Int
    fun rank(query: String, candidates: Collection<SearchTitle>): List<TitleMatch>
}

object TitleNormalizer {
    private val punctuation = Regex("[^\\p{L}\\p{N}]+")
    private val numbers = Regex("\\d+")
    private val nameTokens = Regex("[\\p{L}\\p{N}]+")

    fun words(value: String): String = Normalizer.normalize(value.take(256), Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace(punctuation, " ").trim()

    fun compact(value: String) = words(value).replace(" ", "")

    fun numericParts(value: String) = numbersIn(words(value))

    internal fun numbersIn(normalized: String) = numbers.findAll(normalized).map { it.value }.toList()

    fun preservesNumbers(query: List<String>, candidate: List<String>) =
        query.isEmpty() || (candidate.size >= query.size && query.indices.all { query[it] == candidate[it] })

    /** Real title fragments with the same letters/numbers, retaining their original separators. */
    fun equivalentSpellings(query: String, names: Iterable<String>): List<String> {
        val normalized = words(query)
        val wanted = normalized.replace(" ", "")
        if (wanted.length < 3) return emptyList()
        val queryWordCount = normalized.split(' ').size
        val spellings = names.flatMap { name ->
            val parts = nameTokens.findAll(name.take(256)).toList()
            buildList {
                for (start in parts.indices) {
                    for (end in start..parts.lastIndex) {
                        val fragment = name.substring(parts[start].range.first, parts[end].range.last + 1)
                        val joined = compact(fragment)
                        if (joined == wanted) add(fragment)
                        if (joined.length >= wanted.length) break
                    }
                }
            }
        }
        return spellings.distinctBy { it.lowercase(Locale.ROOT) }
            .filterNot { it.equals(query.trim(), ignoreCase = true) }
            .sortedBy { kotlin.math.abs(words(it).split(' ').size - queryWordCount) }
    }

    /** A separate trailing number can be a season/part, but never overrides a matching numbered name. */
    fun trailingNumberBase(value: String): String? = trailingNumberBaseOfWords(words(value))

    internal fun trailingNumberBaseOfWords(normalized: String): String? {
        val tokens = normalized.split(' ')
        if (tokens.size < 2 || !tokens.last().all(Char::isDigit)) return null
        val base = tokens.dropLast(1).let {
            if (it.lastOrNull() in listOf("season", "stagione", "part", "parte")) it.dropLast(1) else it
        }.joinToString(" ")
        return base.takeIf { it.length >= 3 && it.any(Char::isLetter) }
    }

    /** Accent folding is secondary and Latin-only: Japanese voicing marks retain their meaning. */
    fun folded(value: String): String = foldWords(words(value))

    internal fun foldWords(normalized: String): String =
        buildString(normalized.length) {
            for (character in normalized) {
                if (
                    character.code <= ASCII_MAX_CODE_POINT ||
                    Character.UnicodeScript.of(character.code) != Character.UnicodeScript.LATIN
                ) {
                    append(character)
                } else {
                    append(Normalizer.normalize(character.toString(), Normalizer.Form.NFD).first())
                }
            }
        }
}

class LexicalTitleMatcher : TitleMatcher {
    private data class NormalizedTitle(
        val words: String,
        val compact: String,
        val numbers: List<String>,
        val tokens: List<String>,
        val folded: String,
        val numberlessBase: String?,
    )
    private data class DistanceKey(val first: String, val second: String, val limit: Int)

    // Bounded, memory-only normalization. It never writes titles or user queries to disk.
    private val normalized = object : LinkedHashMap<String, NormalizedTitle>(256, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, NormalizedTitle>) = size > 4_096
    }

    private fun prepare(value: String): NormalizedTitle = synchronized(normalized) {
        val key = value.take(256)
        normalized.getOrPut(key) {
            val words = TitleNormalizer.words(key)
            // Preserve the second 256-character cap after Unicode normalization can expand a title.
            val canonical = TitleNormalizer.words(words)
            NormalizedTitle(
                words,
                words.replace(" ", ""),
                TitleNormalizer.numbersIn(canonical),
                words.split(' '),
                TitleNormalizer.foldWords(canonical),
                TitleNormalizer.trailingNumberBaseOfWords(canonical),
            )
        }
    }

    override fun score(query: String, title: String): Int = score(prepare(query), prepare(title), null)

    private fun score(
        wanted: NormalizedTitle,
        actual: NormalizedTitle,
        distances: MutableMap<DistanceKey, Int>?,
    ): Int {
        fun distance(first: String, second: String, limit: Int): Int {
            if (first == second) return 0
            if (kotlin.math.abs(first.length - second.length) > limit) return limit + 1
            if (distances == null) return editDistance(first, second, limit)
            return distances.getOrPut(DistanceKey(first, second, limit)) { editDistance(first, second, limit) }
        }
        if (wanted.words.isBlank() || actual.words.isBlank()) return 0
        if (!TitleNormalizer.preservesNumbers(wanted.numbers, actual.numbers)) {
            val base = wanted.numberlessBase?.let(::prepare) ?: return 0
            if (actual.numbers != base.numbers) return 0
            val penalty = if (wanted.tokens.last() == "1") 5 else 12
            return score(base, actual, distances).takeIf { it >= 85 }?.minus(penalty) ?: 0
        }
        val editionPenalty = if (wanted.numbers != actual.numbers) 12 else 0
        if (wanted.words == actual.words) return EXACT_TITLE_SCORE
        val a = wanted.compact
        val b = actual.compact
        if (a == b) return 99
        if (wanted.folded == actual.folded) return 98
        if (actual.words.startsWith(wanted.words) || (a.length >= 3 && b.startsWith(a))) return 94 - editionPenalty
        if (wanted.tokens.all { word -> actual.tokens.any { it == word } }) return 93 - editionPenalty
        if (wanted.words.length < 4) return 0
        val limit = if (a.length < 8) 1 else 2
        val compactDistance = distance(a, b, limit)
        if (compactDistance <= limit) return (96 - compactDistance * 7 - editionPenalty).coerceAtLeast(0)
        if (a.length >= 6 && b.length > a.length + limit) {
            val prefixDistance = ((a.length - limit)..(a.length + limit)).minOf { length ->
                distance(a, b.take(length), limit)
            }
            if (prefixDistance <= limit) return 92 - prefixDistance * 5 - editionPenalty
        }
        // Every query word must belong to this complete candidate, including partial searches.
        var totalCost = 0
        for (word in wanted.tokens) {
            val maximum = if (word.length < 4) {
                0
            } else if (word.length < 8) {
                1
            } else {
                2
            }
            var best = maximum + 1
            for (available in actual.tokens) {
                best = minOf(best, distance(word, available, maximum))
                if (best == 0) break
            }
            if (best > if (word.length < 4) 0 else 1) return 0
            totalCost += best
            if (totalCost > 2) return 0
        }
        return 92 - totalCost * 5 - editionPenalty
    }

    override fun rank(query: String, candidates: Collection<SearchTitle>): List<TitleMatch> {
        val wanted = prepare(query)
        val baseNumbers = wanted.numberlessBase?.let(::prepare)?.numbers
        val distances = mutableMapOf<DistanceKey, Int>()
        return candidates.asSequence().distinctBy { it.key }
            .mapNotNull { item ->
                val canonical = prepare(item.title)
                val consistent = canonical.numbers.isEmpty() ||
                    TitleNormalizer.preservesNumbers(wanted.numbers, canonical.numbers) ||
                    canonical.numbers == baseNumbers
                if (!consistent) return@mapNotNull null
                var best = 0
                var matchingNumbers = wanted.numbers.isEmpty()
                for (name in item.names) {
                    val alias = if (name == item.title) canonical else prepare(name)
                    matchingNumbers = matchingNumbers || TitleNormalizer.preservesNumbers(wanted.numbers, alias.numbers)
                    // An alias must not erase a number present in the canonical name.
                    val omitted = canonical.numbers.size > alias.numbers.size && alias.numbers == wanted.numbers
                    best = maxOf(best, score(wanted, alias, distances) - if (omitted) 12 else 0)
                    if (best == EXACT_TITLE_SCORE && matchingNumbers) break
                }
                if (best < MIN_TITLE_MATCH_SCORE) null else TitleMatch(item, best, numericFallback = !matchingNumbers)
            }
            .sortedWith(
                compareBy<TitleMatch> {
                    it.numericFallback
                }.thenByDescending { it.score }.thenBy { it.item.title },
            )
            .toList()
    }

    companion object {
        /** Bounded optimal-string-alignment distance, including adjacent letter transpositions. */
        fun editDistance(a: String, b: String, limit: Int): Int {
            if (a == b) return 0
            if (kotlin.math.abs(a.length - b.length) > limit) return limit + 1
            if (limit == 0) return 1
            val outside = limit + 1
            var before = IntArray(b.length + 1) { it }
            var previous = before.copyOf()
            var current = IntArray(b.length + 1)
            for (i in a.indices) {
                current[0] = i + 1
                val start = (i - limit).coerceAtLeast(0)
                val end = (i + limit).coerceAtMost(b.lastIndex)
                // Only the two cells beside the band can be read by the next row.
                // Reset them so values from an earlier row cannot take part in a match.
                if (start > 0) current[start] = outside
                if (end + 1 < b.length) current[end + 2] = outside
                if (start <= end) {
                    for (j in start..end) {
                        current[j + 1] =
                            minOf(current[j] + 1, previous[j + 1] + 1, previous[j] + if (a[i] == b[j]) 0 else 1)
                        if (i > 0 && j > 0 && a[i] == b[j - 1] && a[i - 1] == b[j]) {
                            current[j + 1] = minOf(current[j + 1], before[j - 1] + 1)
                        }
                    }
                }
                val reusable = before
                before = previous
                previous = current
                current = reusable
            }
            return previous[b.length].coerceAtMost(outside)
        }
    }
}
