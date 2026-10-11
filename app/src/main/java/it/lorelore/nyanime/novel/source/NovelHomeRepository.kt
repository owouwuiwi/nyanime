package it.lorelore.nyanime.novel.source

import it.lorelore.nyanime.source.novel.NovelHomeRequest
import it.lorelore.nyanime.source.novel.NovelHomeSection
import it.lorelore.nyanime.source.novel.NovelHomeSource
import it.lorelore.nyanime.source.novel.NovelSearchPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Bounded in-memory title metadata. This path never writes books, indexes chapters or reads bodies.
 */
internal class NovelHomeRepository(
    private val resolve: suspend (Long) -> NovelSourceRegistration,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private data class Key(val sourceId: Long, val revision: String, val section: String)

    private data class Cached(val page: NovelSearchPage, val savedAt: Long)

    private val cache =
        object : LinkedHashMap<Key, Cached>(32, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Cached>?) =
                size > 32
        }

    fun clear() = synchronized(cache) { cache.clear() }

    suspend fun load(
        registration: NovelSourceRegistration,
        section: NovelHomeSection,
        refresh: Boolean = false,
    ): NovelSearchPage =
        withContext(Dispatchers.IO) {
            val current = resolve(registration.source.id)
            check(same(current, registration)) { "Novel source changed" }
            val source = current.source as? NovelHomeSource ?: error("No declared novel Home")
            check(section in source.homeSections)
            val key = Key(current.source.id, current.revision, section.id)
            synchronized(cache) { cache[key] }
                ?.takeIf {
                    !refresh && now() - it.savedAt in 0..300_000
                }
                ?.let {
                    return@withContext it.page
                }
            val response = withTimeout(20_000) { source.home(NovelHomeRequest(section.id)) }
            check(same(resolve(current.source.id), current)) {
                "Novel source changed during Home request"
            }
            // A Home is a preview, not an unbounded source catalogue.
            val page = response.copy(items = response.items.distinctBy { it.reference }.take(24))
            synchronized(cache) { cache[key] = Cached(page, now()) }
            page
        }

    private fun same(one: NovelSourceRegistration, two: NovelSourceRegistration) =
        one.source.id == two.source.id &&
            one.revision == two.revision &&
            one.packageName == two.packageName &&
            one.providerId == two.providerId
}
