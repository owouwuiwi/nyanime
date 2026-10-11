package it.lorelore.nyanime.themes

import eu.kanade.tachiyomi.data.workinfo.WorkCacheEntry
import eu.kanade.tachiyomi.data.workinfo.WorkInfoCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.io.IOException
import java.time.Clock

class AnimeThemesRepository(
    private val providers: List<ThemeProvider>,
    private val cache: WorkInfoCache,
    private val json: Json,
    private val enabled: () -> Boolean,
    private val clock: Clock = Clock.systemUTC(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private class Flight(val result: Deferred<Result<List<AnimeTheme>>>, var readers: Int = 0)
    private val gate = Mutex()
    private val flights = mutableMapOf<String, Flight>()

    fun observe(
        identity: ThemeIdentity,
        network: Boolean,
        persist: Boolean,
        force: Boolean = false,
    ): Flow<ThemeCatalogue> = channelFlow {
        if (!enabled() || !identity.valid) {
            send(ThemeCatalogue())
            return@channelFlow
        }
        val values = mutableMapOf<ThemeOrigin, List<AnimeTheme>>()
        val snapshots = providers.associate { provider ->
            val key = cacheKey(provider, identity)
            val cached = cache.read(key)?.let { entry ->
                runCatching { json.decodeFromString<List<AnimeTheme>>(entry.payload) }.getOrNull()?.let { entry to it }
            }
            if (cached != null) values[provider.origin] = cached.second
            provider.origin to cached?.first
        }
        val pending = providers.filter { network && (force || snapshots[it.origin]?.fresh(clock.millis()) != true) }
        var remaining = pending.size
        var failed = false
        val stateGate = Mutex()
        fun state() = ThemeCatalogue(
            mergeThemes(values.values.flatten()),
            remaining > 0,
            failed,
            snapshots.values.any { it != null },
        )
        send(state())
        pending.map { provider ->
            async {
                val outcome = try {
                    Result.success(fetch(provider, identity, persist))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Result.failure(error)
                }
                stateGate.withLock {
                    if (outcome.isSuccess) {
                        values[provider.origin] = outcome.getOrThrow()
                    } else {
                        val partial = outcome.exceptionOrNull() as? PartialThemeData
                        if (partial != null) {
                            values[provider.origin] = mergeThemes(values[provider.origin].orEmpty() + partial.songs)
                        }
                        failed = true
                    }
                    remaining--
                    send(state())
                }
            }
        }.awaitAll()
    }.flowOn(workDispatcher)

    private suspend fun fetch(provider: ThemeProvider, identity: ThemeIdentity, persist: Boolean): List<AnimeTheme> {
        val cacheKey = cacheKey(provider, identity)
        val key = "$cacheKey:$persist"
        val flight = gate.withLock {
            flights.getOrPut(key) {
                Flight(
                    scope.async(start = CoroutineStart.LAZY) {
                        try {
                            if (!enabled()) throw IOException("Theme metadata disabled")
                            val songs = withTimeout(25_000) { provider.themes(identity) }
                            if (!enabled()) throw IOException("Theme metadata disabled")
                            if (persist) {
                                cache.write(cacheKey, WorkCacheEntry(clock.millis(), json.encodeToString(songs)))
                            }
                            Result.success(songs)
                        } catch (timeout: TimeoutCancellationException) {
                            Result.failure(IOException("Theme metadata timeout", timeout))
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            Result.failure(error)
                        }
                    },
                )
            }.also { it.readers++ }
        }
        try {
            return flight.result.await().getOrThrow()
        } finally {
            withContext(NonCancellable) {
                gate.withLock {
                    flight.readers--
                    if (flight.readers == 0) {
                        if (flights[key] === flight) flights.remove(key)
                        flight.result.cancel()
                    }
                }
            }
        }
    }

    private fun cacheKey(provider: ThemeProvider, identity: ThemeIdentity): String =
        "theme-v1:${provider.origin}:${identity.key}"
}
