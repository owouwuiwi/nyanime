package eu.kanade.tachiyomi.data.episodeguide

import eu.kanade.tachiyomi.data.workinfo.WorkCacheEntry
import eu.kanade.tachiyomi.data.workinfo.WorkInfoCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Public, rebuildable data. Private sessions never join a persistent request or write to disk. */
internal class EpisodeGuideCache(
    private val cache: WorkInfoCache,
    private val scope: CoroutineScope,
    private val json: Json,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val memory = ConcurrentHashMap<String, WorkCacheEntry>()
    private val gate = Mutex()
    private val privateEpoch = AtomicLong()
    private data class Flight(val result: Deferred<Result<String>>, var readers: Int)
    private val flights = mutableMapOf<String, Flight>()

    fun clearPrivate() {
        privateEpoch.incrementAndGet()
        memory.clear()
    }

    suspend fun saved(key: String, persist: Boolean): WorkCacheEntry? =
        if (persist) cache.read(key) else memory[key] ?: cache.read(key)

    suspend fun load(key: String, network: Boolean, persist: Boolean, fetch: suspend (String) -> String): String {
        val cached = saved(key, persist)
        if (cached != null && (!network || cached.fresh(now()))) return cached.payload
        if (!network) return cached?.payload ?: error("No saved guide")
        val flightKey = "$persist:$key"
        val epoch = privateEpoch.get()
        val flight = gate.withLock {
            flights[flightKey]?.also { it.readers++ } ?: Flight(
                scope.async {
                    try {
                        val raw = fetch(UUID.randomUUID().toString())
                        val bytes = raw.toByteArray().size
                        require(bytes <= 2 * 1024 * 1024)
                        json.parseToJsonElement(raw)
                        val value = WorkCacheEntry(now(), raw)
                        if (persist) {
                            cache.write(key, value)
                        } else if (epoch == privateEpoch.get()) {
                            val used = memory.values.sumOf { it.payload.toByteArray().size.toLong() }
                            if (memory.size >= 256 || used + bytes > 32 * 1024 * 1024) memory.clear()
                            memory[key] = value
                        }
                        Result.success(raw)
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (error: Exception) {
                        Result.failure(error)
                    }
                },
                1,
            ).also { flights[flightKey] = it }
        }
        try {
            return flight.result.await().getOrThrow()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            return cached?.payload ?: throw error
        } finally {
            withContext(NonCancellable) {
                gate.withLock {
                    if (--flight.readers == 0) {
                        flights.remove(flightKey)
                        flight.result.cancel()
                    }
                }
            }
        }
    }
}
