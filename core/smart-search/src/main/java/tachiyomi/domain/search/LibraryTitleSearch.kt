package tachiyomi.domain.search

object LibraryTitleSearch {
    private val matcher = LexicalTitleMatcher()

    fun matches(query: String, title: String, enabled: Boolean): Boolean = enabled &&
        !query.startsWith("-") &&
        !query.contains(',') &&
        !query.startsWith("id:", true) &&
        !query.contains("://") &&
        matcher.score(query, title) >= 75
}
