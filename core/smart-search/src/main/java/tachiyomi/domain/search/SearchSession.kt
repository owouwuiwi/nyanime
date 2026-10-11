package tachiyomi.domain.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface SearchCandidateProvider {
    suspend fun candidates(query: String, medium: SearchMedium, online: Boolean): List<SearchTitle>
    suspend fun remember(items: List<SearchTitle>)
}

class SearchCandidateFailure(val local: List<SearchTitle>, cause: Exception) : java.io.IOException(cause)

data class SearchAssistance(
    val query: String = "",
    val suggestions: List<SearchTitle> = emptyList(),
    val correctedQuery: String? = null,
    val loading: Boolean = false,
    val resolved: Boolean = false,
    val unavailable: Boolean = false,
    val exact: Boolean = false,
    val enabled: Boolean = false,
)

data class SearchPage<T>(val items: List<T>, val hasNextPage: Boolean)

interface ExtensionSearchAdapter<T> {
    val key: String
    fun identity(item: T): String
    fun title(item: T): SearchTitle
    suspend fun fetch(page: Int, query: String): SearchPage<T>
}

interface TitleSearch {
    /** Restore optional names for the same runtime identity, without requests or title association. */
    suspend fun withKnownAliases(titles: List<SearchTitle>): List<SearchTitle> = titles

    fun session(
        query: String,
        medium: SearchMedium,
        exact: Boolean = false,
        sourceId: Long? = null,
        catalogBudget: SearchRequestBudget? = null,
    ): SearchSession
}

/** One session per user query, shared by all sources. Each source/query owns its pagination. */
class SearchSession(
    val query: String,
    private val medium: SearchMedium,
    private val provider: SearchCandidateProvider,
    private val matcher: TitleMatcher,
    private val enabled: Boolean = true,
    private val online: Boolean = true,
    private val exact: Boolean = false,
    private val allowNetwork: Boolean = true,
) {
    private val mutableAssistance = MutableStateFlow(
        SearchAssistance(query = query, exact = exact, enabled = enabled, unavailable = !allowNetwork),
    )
    val assistance = mutableAssistance.asStateFlow()
    private val candidateLock = Mutex()
    private var candidateSnapshot: List<SearchTitle>? = null
    private val sourceLocks = mutableMapOf<String, Mutex>()
    private data class Cursor<T>(
        val query: String,
        var nextPage: Int,
        var more: Boolean,
        var pending: SearchPage<T>? = null,
    )
    private val cursors = mutableMapOf<String, MutableList<Cursor<*>>>()
    private val displayed = mutableMapOf<String, MutableSet<String>>()
    private val extraRequests = mutableMapOf<String, SearchRequestBudget>()
    private data class RecoveredQuery(
        val query: String,
        val expected: List<String>,
        val canPage: Boolean,
        val interpretation: String,
    )
    private val recoveredQueries = java.util.concurrent.ConcurrentHashMap<String, List<RecoveredQuery>>()

    suspend fun suggestions(): List<SearchTitle> = candidateLock.withLock {
        candidateSnapshot?.let { return@withLock it }
        if (!canAssist()) return@withLock emptyList()
        mutableAssistance.update { it.copy(loading = true) }
        try {
            val found = matcher.rank(query, provider.candidates(query, medium, online)).take(30).map { it.item }
            candidateSnapshot = found
            mutableAssistance.update { it.copy(suggestions = titleOptions(found), loading = false, resolved = true) }
            found
        } catch (failure: SearchCandidateFailure) {
            val found = matcher.rank(query, failure.local).take(30).map { it.item }
            candidateSnapshot = found
            mutableAssistance.update {
                it.copy(
                    suggestions = titleOptions(found),
                    loading = false,
                    unavailable = true,
                    resolved = true,
                )
            }
            found
        } catch (cancelled: CancellationException) {
            mutableAssistance.update { it.copy(loading = false) }
            throw cancelled
        } catch (_: Exception) {
            candidateSnapshot = emptyList()
            mutableAssistance.update { it.copy(loading = false, unavailable = true, resolved = true) }
            emptyList()
        }
    }

    suspend fun <T> search(adapter: ExtensionSearchAdapter<T>, page: Int): SearchPage<T> {
        val lock = synchronized(sourceLocks) { sourceLocks.getOrPut(adapter.key) { Mutex() } }
        return lock.withLock {
            if (!allowNetwork) return@withLock SearchPage(emptyList(), false)
            if (page == 1) firstPage(adapter) else nextPage(adapter)
        }
    }

    private suspend fun <T> firstPage(adapter: ExtensionSearchAdapter<T>): SearchPage<T> {
        // A failed original request stays an error; it must not trigger spelling retries.
        val original = adapter.fetch(1, query)
        remember(original.items.map(adapter::title))
        val branches = mutableListOf(Cursor<T>(query, 2, original.hasNextPage))
        val merged = original.items.toMutableList()
        val previousRecovery = recoveredQueries[adapter.key]
        if (canAssist() && previousRecovery != null) {
            for (recovery in previousRecovery) {
                val recovered = try {
                    adapter.fetch(1, recovery.query)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    mutableAssistance.update { it.copy(unavailable = true) }
                    null
                }
                if (recovered != null) {
                    remember(recovered.items.map(adapter::title))
                    merged += recovered.items.filter {
                        pertinent(adapter.title(it), recovery.expected, recovery.interpretation)
                    }
                    branches += Cursor(recovery.query, 2, recovered.hasNextPage && recovery.canPage)
                }
            }
        } else if (canAssist() &&
            (
                original.items.none { matcher.rank(query, listOf(adapter.title(it))).any { it.score >= 92 } } ||
                    TitleNormalizer.equivalentSpellings(
                        query,
                        original.items.flatMap {
                            adapter.title(it).names
                        },
                    ).isNotEmpty()
                )
        ) {
            val candidates = suggestions() + original.items.map(adapter::title)
            val ranked = matcher.rank(query, candidates)
            val first = ranked.firstOrNull()
            val interpretation = if (first?.numericFallback ==
                true
            ) {
                TitleNormalizer.trailingNumberBase(query) ?: query
            } else {
                query
            }
            // Retrieve the best grounded spelling even when alternatives are plausible.
            // All suggestions stay visible; this never selects or links a work for the user.
            val corrected = if (first != null) {
                requireNotNull(first).item.names
                    .filter { name ->
                        TitleNormalizer.numericParts(name) == TitleNormalizer.numericParts(interpretation) &&
                            (
                                LexicalTitleMatcher.editDistance(
                                    TitleNormalizer.compact(interpretation),
                                    TitleNormalizer.compact(name),
                                    2,
                                ) <= 2 ||
                                    matcher.score(interpretation, name) >= 85
                                )
                    }.maxByOrNull { matcher.score(interpretation, it) }
            } else {
                null
            }
            // An equivalent short spelling must stay a broad search, not become one full catalog title.
            val spellings = TitleNormalizer.equivalentSpellings(interpretation, ranked.flatMap { it.item.names })
            val attempts = buildList {
                addAll(spellings)
                corrected?.let(::add)
                if (corrected != null &&
                    first != null &&
                    TitleNormalizer.numericParts(first.item.title) == TitleNormalizer.numericParts(interpretation)
                ) {
                    add(first.item.title)
                }
                recoveryAnchor(corrected ?: first?.item?.title)?.let(::add)
            }.distinctBy { it.trim().lowercase() }.filter { it != query }.take(2)
            val verified = mutableListOf<RecoveredQuery>()
            for (variant in attempts) {
                val equivalent = variant in spellings
                if (verified.isNotEmpty() && !equivalent) break
                val budget = synchronized(extraRequests) {
                    extraRequests.getOrPut(adapter.key) { SearchRequestBudget(2) }
                }
                if (!budget.take()) break
                val recovered = try {
                    adapter.fetch(1, variant)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Preserve the successful original response and suggestions.
                    mutableAssistance.update { it.copy(unavailable = true) }
                    break
                }
                remember(recovered.items.map(adapter::title))
                val namedVariant = equivalent || (corrected != null && variant in listOf(corrected, first?.item?.title))
                val expected = if (equivalent) {
                    listOf(interpretation, variant)
                } else if (namedVariant) {
                    listOfNotNull(corrected, variant, first?.item?.title) +
                        ranked.filter { candidate ->
                            candidate.item.names.any { TitleNormalizer.compact(it) == TitleNormalizer.compact(variant) }
                        }.map { it.item.title }
                } else {
                    listOfNotNull(query, corrected)
                }
                val matches = recovered.items.filter { pertinent(adapter.title(it), expected, interpretation) }
                merged += matches
                // An anchor is a single bounded probe, never a crawl of a broad catalog.
                branches += Cursor(variant, 2, recovered.hasNextPage && matches.isNotEmpty() && namedVariant)
                if (matches.isNotEmpty()) {
                    if (verified.isEmpty()) {
                        mutableAssistance.update {
                            it.copy(
                                correctedQuery = if (equivalent) {
                                    variant
                                } else {
                                    corrected
                                        ?: variant
                                },
                            )
                        }
                    }
                    verified += RecoveredQuery(variant, expected, namedVariant, interpretation)
                }
            }
            if (verified.isNotEmpty()) recoveredQueries[adapter.key] = verified
        }
        synchronized(cursors) { cursors[adapter.key] = branches.toMutableList<Cursor<*>>() }
        val unique = merged.distinctBy(adapter::identity)
        synchronized(displayed) { displayed[adapter.key] = unique.mapTo(mutableSetOf(), adapter::identity) }
        val ordered = if (canAssist()) {
            unique.map { it to matcher.rank(query, listOf(adapter.title(it))).firstOrNull() }
                .sortedWith(
                    compareBy<Pair<T, TitleMatch?>> { it.second?.numericFallback ?: true }
                        .thenByDescending { it.second?.score ?: 0 },
                ).map { it.first }
        } else {
            unique
        }
        return SearchPage(ordered, branches.any { it.more })
    }

    private suspend fun <T> nextPage(adapter: ExtensionSearchAdapter<T>): SearchPage<T> {
        @Suppress("UNCHECKED_CAST")
        val branches = synchronized(cursors) { cursors[adapter.key]?.toList() } as? List<Cursor<T>>
            ?: return SearchPage(emptyList(), false)
        val items = mutableListOf<T>()
        val active = branches.filter { it.more }
        // Keep successful pages pending until every branch succeeds, so a retry cannot skip data.
        for (branch in active) {
            if (branch.pending == null) branch.pending = adapter.fetch(branch.nextPage, branch.query)
        }
        for (branch in active) {
            val result = requireNotNull(branch.pending)
            branch.pending = null
            branch.nextPage++
            branch.more = result.hasNextPage
            remember(result.items.map(adapter::title))
            val filtered = if (branch.query == query) {
                result.items
            } else {
                val recovery = recoveredQueries[adapter.key]?.firstOrNull { it.query == branch.query }
                val expected = recovery?.expected ?: listOf(branch.query)
                result.items.filter { pertinent(adapter.title(it), expected, recovery?.interpretation ?: query) }
            }
            synchronized(displayed) {
                val seen = displayed.getOrPut(adapter.key) { mutableSetOf() }
                items += filtered.filter { seen.add(adapter.identity(it)) }
            }
        }
        return SearchPage(items, branches.any { it.more })
    }

    private fun recoveryAnchor(corrected: String?): String? {
        val wanted = TitleNormalizer.words(query).split(' ')
        val recognized = TitleNormalizer.words(corrected ?: query).split(' ')
        val compact = TitleNormalizer.compact(query)
        val joinedPrefix = recognized.indices.firstOrNull { index ->
            LexicalTitleMatcher.editDistance(compact, recognized.take(index + 1).joinToString(""), 2) <= 2
        }?.let { recognized.take(it + 1) }
        val grounded = joinedPrefix ?: wanted.map { token ->
            val limit = if (token.length < 4) {
                0
            } else if (token.length < 8) {
                1
            } else {
                2
            }
            recognized.minByOrNull { LexicalTitleMatcher.editDistance(token, it, limit) }
                ?.takeIf { LexicalTitleMatcher.editDistance(token, it, limit) <= limit } ?: token
        }
        return grounded.filter { it.length >= 4 && it.any(Char::isLetter) }.maxByOrNull { it.length }
    }

    // One chip per distinct search text. Candidate identities and aliases remain separate.
    private fun titleOptions(items: List<SearchTitle>) = items.distinctBy { TitleNormalizer.words(it.title) }.take(5)

    private fun pertinent(item: SearchTitle, expected: List<String>, interpretation: String): Boolean {
        val wanted = TitleNormalizer.numericParts(interpretation)
        val numbers = TitleNormalizer.numericParts(item.title)
        if (numbers.isNotEmpty() && !TitleNormalizer.preservesNumbers(wanted, numbers)) {
            return false
        }
        if (item.names.none { TitleNormalizer.preservesNumbers(wanted, TitleNormalizer.numericParts(it)) }) return false
        return expected.any { matcher.rank(it, listOf(item)).any { match -> match.score >= 75 } }
    }

    private suspend fun remember(items: List<SearchTitle>) {
        try {
            provider.remember(items)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A disposable index cannot turn a successful source response into an error.
        }
    }

    private fun canAssist() = enabled &&
        !exact &&
        query.length in 3..256 &&
        !query.contains("://") &&
        !query.startsWith("id:", true)
}
