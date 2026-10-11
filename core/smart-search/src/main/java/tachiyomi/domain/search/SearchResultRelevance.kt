package tachiyomi.domain.search

enum class SearchMatchKind { TITLE, ALIAS, WORDS, CORRECTION, OTHER }

/** Presentation evidence only: matching names never establishes a content identity. */
data class SearchResultRelevance(
    val kind: SearchMatchKind = SearchMatchKind.OTHER,
    val score: Int = 0,
    val numericFallback: Boolean = false,
    val extraWords: Int = 0,
) : Comparable<SearchResultRelevance> {
    override fun compareTo(other: SearchResultRelevance): Int =
        compareValuesBy(this, other, {
            it.kind == SearchMatchKind.OTHER
        }, { it.numericFallback }, { it.kind.ordinal }, { it.extraWords }, { -it.score })
}

/** Reuses the lexical engine while retaining unranked source results at the end. */
class SearchResultRanker(private val matcher: TitleMatcher = LexicalTitleMatcher()) {
    fun rank(query: String, titles: Collection<SearchTitle>): Map<String, SearchResultRelevance> {
        if (query.isBlank()) return emptyMap()
        val wanted = TitleNormalizer.words(query)
        val compact = TitleNormalizer.compact(query)
        val folded = TitleNormalizer.folded(query)
        val wantedTokens = wanted.split(' ')
        val matches = matcher.rank(query, titles).associateBy { it.item.key }
        return titles.associate { item ->
            val match = matches[item.key]
            val relevance = if (match == null) {
                SearchResultRelevance()
            } else {
                val numbers = TitleNormalizer.numericParts(item.title)
                val names = item.names.filter { numbers.isEmpty() || TitleNormalizer.numericParts(it) == numbers }
                fun complete(name: String): Boolean = TitleNormalizer.words(name) == wanted ||
                    TitleNormalizer.compact(name) == compact ||
                    TitleNormalizer.folded(name) == folded
                val kind = when {
                    complete(item.title) -> SearchMatchKind.TITLE
                    names.drop(1).any(::complete) -> SearchMatchKind.ALIAS
                    names.any { name ->
                        val words = TitleNormalizer.words(name)
                        words.startsWith(wanted) || wantedTokens.all { it in words.split(' ') }
                    } -> SearchMatchKind.WORDS
                    else -> SearchMatchKind.CORRECTION
                }
                val extraWords = if (kind == SearchMatchKind.WORDS) {
                    names.mapNotNull { name ->
                        val words = TitleNormalizer.words(name)
                        val tokens = words.split(' ')
                        if (words.startsWith(wanted) || wantedTokens.all { it in tokens }) {
                            (tokens.size - wantedTokens.size).coerceAtLeast(0)
                        } else {
                            null
                        }
                    }.minOrNull() ?: 0
                } else {
                    0
                }
                SearchResultRelevance(kind, match.score, match.numericFallback, extraWords)
            }
            item.key to relevance
        }
    }
}
