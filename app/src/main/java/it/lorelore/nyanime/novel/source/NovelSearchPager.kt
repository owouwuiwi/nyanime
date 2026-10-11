package it.lorelore.nyanime.novel.source

import it.lorelore.nyanime.source.novel.NovelSearchPage
import it.lorelore.nyanime.source.novel.NovelSearchRequest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.IOException

/** Bridges numbered search branches to source-owned opaque cursors without guessing page URLs. */
internal class NovelSearchPager(private val resolve: suspend (Long) -> NovelSourceRegistration) {
    private data class Key(
        val source: Long,
        val revision: String,
        val query: String,
        val filters: Map<String, String>,
    )

    private class Branch(var page: Int = 1, var cursor: String? = null) {
        val seen = linkedSetOf<String>()
    }

    private val lock = Mutex()
    private val branches = linkedMapOf<Key, Branch>()

    suspend fun fetch(
        source: Long,
        revision: String,
        query: String,
        page: Int,
        filters: Map<String, String> = emptyMap(),
    ): NovelSearchPage = lock.withLock {
        require(query.isNotBlank() && page in 1 until Int.MAX_VALUE)
        val key = Key(source, revision, query, filters.toMap())
        val branch =
            if (page == 1) {
                Branch().also { branches[key] = it }
            } else {
                branches[key] ?: throw IOException("Novel search must start from its first page")
            }
        check(branch.page == page) { "Novel search cursor is no longer current" }
        check(page == 1 || branch.cursor != null) { "Novel search has reached its last page" }
        withTimeout(30_000) {
            val registration = resolve(source)
            check(registration.revision == revision)
            val answer =
                registration.source.search(NovelSearchRequest(query, branch.cursor, filters))
            currentCoroutineContext().ensureActive()
            val current = resolve(source)
            check(
                current.packageName == registration.packageName &&
                    current.providerId == registration.providerId &&
                    current.revision == revision,
            )
            val next = answer.nextCursor?.takeIf { it != branch.cursor && it !in branch.seen }
            branch.cursor?.let { branch.seen += it }
            while (branch.seen.size > 128) branch.seen.remove(branch.seen.first())
            branch.page = page + 1
            branch.cursor = next
            // New logical searches receive a new pager. Only recovery branches live here.
            while (branches.size > 16) branches.remove(branches.keys.first())
            answer.copy(items = answer.items.distinctBy { it.reference }, nextCursor = next)
        }
    }
}
