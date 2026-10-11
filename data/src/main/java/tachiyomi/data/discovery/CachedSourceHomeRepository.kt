package tachiyomi.data.discovery

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tachiyomi.domain.discovery.SectionState
import tachiyomi.domain.discovery.SourceHomeAccess
import tachiyomi.domain.discovery.SourceHomeCache
import tachiyomi.domain.discovery.SourceHomeCacheEntry
import tachiyomi.domain.discovery.SourceHomeCacheKey
import tachiyomi.domain.discovery.SourceHomeGateway
import tachiyomi.domain.discovery.SourceHomePage
import tachiyomi.domain.discovery.SourceHomeRepository
import tachiyomi.domain.discovery.SourceHomeRequest
import java.time.Clock

/** Public feed snapshots survive process death; incognito and searches always bypass persistent storage. */
class CachedSourceHomeRepository(
    private val gateway: SourceHomeGateway,
    private val clock: Clock = Clock.systemUTC(),
    private val capacity: Int = 40,
    private val persistent: SourceHomeCache? = null,
) : SourceHomeRepository {
    init {
        require(capacity > 0)
    }

    private data class Key(val home: String, val revision: String, val request: SourceHomeRequest)
    private data class Entry(val page: SourceHomePage, val at: Long)
    private val entries = LinkedHashMap<Key, Entry>()
    private val cacheLock = Mutex()
    private val requests = List(16) { Mutex() }

    override fun observe(access: SourceHomeAccess, request: SourceHomeRequest, refresh: Boolean) = flow {
        val source = access.source
        if (access.isPrivate) cacheLock.withLock { entries.clear() }
        if (source == null || access.offline || access != gateway.currentAccess()) {
            emit(SectionState<SourceHomePage>(loading = false, error = "Fonte non disponibile in questa modalità"))
            return@flow
        }
        val key = Key(source.key, source.revision, request.copy(query = request.query.trim()))
        val diskKey = SourceHomeCacheKey(source.key, source.id, source.revision, request.cacheSection, request.page)
            .takeIf {
                !access.isPrivate &&
                    request.query.isBlank() &&
                    request.filters.isEmpty() &&
                    !request.browse &&
                    request.sectionId != SourceHomeRequest.SEARCH
            }
        var cached = readMemory(key).takeUnless { access.isPrivate }
        if (cached == null && diskKey != null) {
            val restored = cacheOperation { persistent?.read(diskKey) }
            if (restored != null && clock.millis() - restored.fetchedAt in 0 until MAX_STALE) {
                cacheLock.withLock {
                    cached = entries.getOrPut(key) { Entry(restored.page, restored.fetchedAt) }
                    while (entries.size > capacity) entries.remove(entries.keys.first())
                }
            }
        }
        if (access != gateway.currentAccess()) return@flow
        val snapshot = cached
        val fresh = snapshot != null && clock.millis() - snapshot.at in 0 until TTL
        emit(SectionState(snapshot?.page, loading = refresh || !fresh, stale = snapshot != null && !fresh))
        if (fresh && !refresh) return@flow
        try {
            val page = requests[(key.hashCode() and Int.MAX_VALUE) % requests.size].withLock {
                check(access == gateway.currentAccess()) { "La fonte è stata disabilitata o aggiornata" }
                val updated = readMemory(key).takeUnless { access.isPrivate }
                // Concurrent collectors share a refresh completed after their initial cache read.
                if (updated != null && updated !== snapshot && clock.millis() - updated.at in 0 until TTL) {
                    updated.page
                } else {
                    gateway.fetch(access, key.request).also { result ->
                        check(access == gateway.currentAccess()) { "La fonte non è più disponibile" }
                        if (!access.isPrivate) {
                            val at = clock.millis()
                            cacheLock.withLock {
                                entries.remove(key)
                                entries[key] = Entry(result, at)
                                while (entries.size > capacity) entries.remove(entries.keys.first())
                            }
                            if (diskKey != null && access == gateway.currentAccess()) {
                                cacheOperation { persistent?.write(diskKey, SourceHomeCacheEntry(result, at)) }
                            }
                        }
                    }
                }
            }
            emit(SectionState(page, loading = false))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val stillAllowed = access == gateway.currentAccess()
            emit(
                SectionState(
                    snapshot?.page.takeIf { stillAllowed },
                    loading = false,
                    stale = snapshot != null && stillAllowed,
                    error = e.message ?: "Impossibile caricare la fonte",
                ),
            )
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        const val TTL = 30 * 60_000L
        private const val MAX_STALE = 24 * 60 * 60_000L
    }

    private suspend fun readMemory(key: Key): Entry? = cacheLock.withLock {
        entries.remove(key)?.takeIf { clock.millis() - it.at in 0 until MAX_STALE }?.also {
            entries[key] = it
        }
    }

    private suspend fun <T> cacheOperation(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}
