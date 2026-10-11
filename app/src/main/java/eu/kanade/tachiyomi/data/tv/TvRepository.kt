package eu.kanade.tachiyomi.data.tv

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import nyanime.tv.api.TvCacheEntry
import nyanime.tv.api.TvCatalogue
import nyanime.tv.api.TvCatalogueRequest
import nyanime.tv.api.TvChannel
import nyanime.tv.api.TvChannelLookup
import nyanime.tv.api.TvChannelRef
import nyanime.tv.api.TvGuide
import nyanime.tv.api.TvGuideRequest
import nyanime.tv.api.TvPlayback
import nyanime.tv.api.TvRules
import nyanime.tv.api.TvSource
import nyanime.tv.api.TvSourceCache
import java.io.IOException
import java.util.UUID

data class TvRegistration(val source: TvSource, val cache: TvSourceCache, val packageName: String = "")

class TvRegistry {
    private val mutable = MutableStateFlow<Map<Long, TvRegistration>>(emptyMap())
    private val mutableFailures = MutableStateFlow<Set<String>>(emptySet())
    val state = mutable.asStateFlow()
    val failures = mutableFailures.asStateFlow()

    fun replace(sources: List<TvRegistration>, failures: Set<String> = emptySet()) {
        validate(sources)
        mutableFailures.value = failures
        mutable.value = sources.associateBy { it.source.id }
    }

    fun validate(sources: List<TvRegistration>) {
        require(sources.size <= 128 && sources.map { it.source.id }.distinct().size == sources.size)
        require(
            sources.all {
                it.source.id != 0L &&
                    it.source.name.isNotBlank() &&
                    it.source.name.length <= 240 &&
                    it.source.name.none(Char::isISOControl)
            },
        )
        require(sources.all { TvRules.valid(it.source.capabilities) })
    }
}

data class TvResult<T>(
    val data: T? = null,
    val loading: Boolean = false,
    val saved: Boolean = false,
    val error: Boolean = false,
    val checkedAt: Long = 0L,
)

data class TvGuideResult(
    val data: TvGuide,
    val loading: Set<TvChannelRef> = emptySet(),
    val saved: Set<TvChannelRef> = emptySet(),
    val checkedAt: Map<TvChannelRef, Long> = emptyMap(),
)

/** Cache-first, bounded calls. A failed refresh keeps saved data and never pretends it is fresh. */
class TvRepository(
    val registry: TvRegistry,
    val preferences: TvPreferences,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
    private val allowAdult: () -> Boolean = { true },
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val gate = TvRequestGate()

    /** Used by empty search exploration: read local data without starting a catalogue request. */
    suspend fun cachedCatalogue(sourceId: Long, request: TvCatalogueRequest): TvResult<TvCatalogue> = withContext(io) {
        val registration = registry.state.value[sourceId] ?: return@withContext TvResult(error = true)
        val cached = readCatalogue(registration.cache, catalogueKey(request), sourceId)
        checkCurrent(sourceId, registration)
        TvResult(
            cached?.value,
            saved = cached != null && now() - cached.checkedAt !in 0 until TvRules.DAY,
            checkedAt =
            cached?.checkedAt ?: 0,
        )
    }

    private fun catalogueKey(request: TvCatalogueRequest) =
        "catalogue:${request.country}:${request.language}:${request.category}:${request.cursor}"

    fun catalogue(sourceId: Long, request: TvCatalogueRequest): Flow<TvResult<TvCatalogue>> = flow {
        val registration = registry.state.value[sourceId]
        if (registration == null) {
            emit(TvResult(error = true))
            return@flow
        }
        val key = catalogueKey(request)
        val cached = withContext(io) { readCatalogue(registration.cache, key, sourceId) }
        checkCurrent(sourceId, registration)
        val fresh = !request.refresh && cached != null && now() - cached.checkedAt in 0 until TvRules.DAY
        emit(
            TvResult(
                cached?.value,
                loading = !fresh,
                saved = !fresh && cached != null,
                checkedAt =
                cached?.checkedAt ?: 0,
            ),
        )
        if (fresh) return@flow
        val result = try {
            val next = withContext(io) {
                gate.run("$sourceId:$key") {
                    val latest = readCatalogue(registration.cache, key, sourceId)
                    if (latest != null && latest.revision != cached?.revision) return@run latest
                    val page = withTimeout(45_000) { registration.source.catalogue(request) }
                    validateCatalogue(page, sourceId)
                    require(TvRules.valid(registration.source.capabilities))
                    checkCurrent(sourceId, registration)
                    CachedCatalogue(page, now(), UUID.randomUUID().toString()).also {
                        registration.cache.put(key, TvCacheEntry(json.encodeToString(it), it.checkedAt))
                    }
                }
            }
            TvResult(next.value, checkedAt = next.checkedAt)
        } catch (_: TimeoutCancellationException) {
            checkCurrent(sourceId, registration)
            TvResult(cached?.value, saved = cached != null, error = true, checkedAt = cached?.checkedAt ?: 0)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            checkCurrent(sourceId, registration)
            TvResult(cached?.value, saved = cached != null, error = true, checkedAt = cached?.checkedAt ?: 0)
        }
        emit(result)
    }

    /** Cache is per channel/window, so scrolling reuses known programmes instead of starting over. */
    fun guideFlow(request: TvGuideRequest, force: Boolean = false): Flow<TvGuideResult> = flow {
        require(request.channels.size <= 64 && request.channels.all(TvRules::valid))
        require(
            request.startUtc > 0 &&
                request.endUtc > request.startUtc &&
                request.endUtc - request.startUtc <= 7 * TvRules.DAY,
        )
        val refs = request.channels.distinct()
        val sourceId = refs.map { it.sourceId }.distinct().singleOrNull()
        val registration = sourceId?.let { registry.state.value[it] }
        if (sourceId == null || registration == null) {
            emit(TvGuideResult(TvGuide(emptyList(), failed = refs.toSet())))
            return@flow
        }
        val cached = withContext(io) {
            refs.mapNotNull { ref ->
                readGuide(registration.cache, request, ref)?.let {
                    ref to
                        it
                }
            }.toMap()
        }.toMutableMap()
        checkCurrent(sourceId, registration)
        val initialRevisions = cached.mapValues { it.value.revision }
        val pending = refs.filter { force || !fresh(cached[it]) }.toMutableSet()
        val failed = mutableSetOf<TvChannelRef>()
        val refreshed = mutableSetOf<TvChannelRef>()
        fun snapshot(): TvGuideResult = TvGuideResult(
            data = TvGuide(
                programmes = cached.values.flatMap {
                    it.value.programmes
                }.distinctBy { it.channel to it.id }.sortedBy { it.startUtc },
                unavailable = cached.values.flatMap { it.value.unavailable }.toSet() - failed,
                failed = failed.toSet(),
                availableFromUtc = cached.values.mapNotNull { it.value.availableFromUtc }.minOrNull(),
                availableUntilUtc = cached.values.mapNotNull { it.value.availableUntilUtc }.maxOrNull(),
            ),
            loading = pending.toSet(),
            saved = cached.keys.filter { it !in refreshed && !fresh(cached[it]) || it in failed }.toSet(),
            checkedAt = cached.mapValues { it.value.checkedAt },
        )
        emit(snapshot())
        // Smaller batches publish visible programmes progressively; transport has its own four-call limit.
        for (batch in pending.toList().chunked(8)) {
            try {
                val updates = withContext(io) {
                    gate.run("$sourceId:guide:${request.startUtc}:${request.endUtc}") {
                        checkCurrent(sourceId, registration)
                        val latest = batch.mapNotNull { ref ->
                            readGuide(registration.cache, request, ref)?.let {
                                ref to
                                    it
                            }
                        }.toMap()
                        val targets = batch.filter { ref ->
                            val value = latest[ref]
                            if (force) value == null || value.revision == initialRevisions[ref] else !fresh(value)
                        }
                        if (targets.isEmpty()) return@run GuideBatch(latest, emptySet())
                        val result =
                            withTimeout(45_000) { registration.source.programmes(request.copy(channels = targets)) }
                        validateGuide(result, targets.toSet(), request)
                        checkCurrent(sourceId, registration)
                        val completedAt = now()
                        val successful = targets.filterNot { it in result.failed }.associateWith { ref ->
                            CachedGuide(
                                result.copy(
                                    programmes = result.programmes.filter { it.channel == ref },
                                    unavailable = result.unavailable.intersect(setOf(ref)),
                                    failed = emptySet(),
                                ),
                                completedAt,
                                UUID.randomUUID().toString(),
                            )
                        }
                        successful.forEach { (ref, value) ->
                            registration.cache.put(
                                guideKey(request, ref),
                                TvCacheEntry(json.encodeToString(value), completedAt),
                            )
                        }
                        GuideBatch(latest + successful, result.failed)
                    }
                }
                cached.putAll(updates.values)
                failed += updates.failed
                refreshed += batch - updates.failed
            } catch (_: TimeoutCancellationException) {
                checkCurrent(sourceId, registration)
                failed += batch
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                checkCurrent(sourceId, registration)
                failed += batch
            }
            pending.removeAll(batch.toSet())
            checkCurrent(sourceId, registration)
            emit(snapshot())
        }
    }

    suspend fun guide(request: TvGuideRequest, force: Boolean = false): TvGuideResult = guideFlow(request, force).last()

    /** Only explicitly requested personal channels; no stream resolution or global scanning. */
    suspend fun channels(references: List<TvChannelRef>): TvChannelLookup = withContext(io) {
        require(references.size <= 64 && references.all(TvRules::valid))
        val refs = references.distinct()
        val found = mutableMapOf<TvChannelRef, TvChannel>()
        val unavailable = mutableSetOf<TvChannelRef>()
        val failed = mutableSetOf<TvChannelRef>()
        for ((sourceId, selected) in refs.groupBy { it.sourceId }) {
            val registration = registry.state.value[sourceId]
            if (registration == null) {
                unavailable += selected
                continue
            }
            gate.run("$sourceId:personal-channels") {
                val cached = selected.mapNotNull { ref ->
                    registration.cache.get(channelKey(ref))?.let { entry ->
                        runCatching { json.decodeFromString<CachedChannel>(entry.content) }
                            .getOrNull()?.takeIf { it.value.ref == ref && TvRules.valid(it.value) && it.checkedAt > 0 }
                            ?.let { ref to it }
                    }
                }.toMap()
                found.putAll(cached.mapValues { it.value.value })
                val pending = selected.filter { ref ->
                    cached[ref]?.let { now() - it.checkedAt in 0 until TvRules.DAY } !=
                        true
                }
                if (pending.isNotEmpty() && registration.source.capabilities.channelLookup) {
                    try {
                        val lookup = withTimeout(45_000) { registration.source.channels(pending) }
                        validateLookup(lookup, pending.toSet())
                        checkCurrent(sourceId, registration)
                        val checkedAt = now()
                        lookup.channels.forEach { channel ->
                            found[channel.ref] = channel
                            registration.cache.put(
                                channelKey(channel.ref),
                                TvCacheEntry(json.encodeToString(CachedChannel(channel, checkedAt)), checkedAt),
                            )
                        }
                        unavailable += lookup.unavailable
                        failed += lookup.failed
                        lookup.unavailable.forEach(found::remove)
                    } catch (_: TimeoutCancellationException) {
                        checkCurrent(sourceId, registration)
                        failed += pending
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        checkCurrent(sourceId, registration)
                        failed += pending
                    }
                } else {
                    unavailable += pending.filterNot { it in found }
                }
                checkCurrent(sourceId, registration)
            }
        }
        TvChannelLookup(refs.mapNotNull(found::get), unavailable, failed)
    }

    private fun validateLookup(lookup: TvChannelLookup, selected: Set<TvChannelRef>) {
        require(
            lookup.channels.size <= selected.size &&
                lookup.channels.all {
                    it.ref in selected && TvRules.valid(it)
                },
        )
        val supplied = lookup.channels.map { it.ref }.toSet()
        require(supplied.size == lookup.channels.size)
        require(lookup.unavailable.all { it in selected } && lookup.failed.all { it in selected })
        require(
            lookup.unavailable.intersect(lookup.failed + supplied).isEmpty() &&
                lookup.failed.intersect(supplied).isEmpty(),
        )
        require(supplied + lookup.unavailable + lookup.failed == selected)
    }

    private fun channelKey(ref: TvChannelRef) = "channel:" + sha256("${ref.sourceId}:${ref.id}")

    private fun fresh(value: CachedGuide?): Boolean = value != null && now() - value.checkedAt in 0 until 3_600_000

    private fun guideKey(request: TvGuideRequest, ref: TvChannelRef): String =
        "guide:" + sha256("${ref.sourceId}:${ref.id}:${request.startUtc}:${request.endUtc}")

    private suspend fun readGuide(
        cache: TvSourceCache,
        request: TvGuideRequest,
        ref: TvChannelRef,
    ): CachedGuide? = cache.get(
        guideKey(request, ref),
    )?.let {
        runCatching {
            json.decodeFromString<CachedGuide>(it.content).also { value ->
                validateGuide(value.value, setOf(ref), request)
                require(value.checkedAt > 0 && value.value.failed.isEmpty())
            }
        }.getOrNull()
    }

    private fun validateGuide(result: TvGuide, selected: Set<TvChannelRef>, request: TvGuideRequest) {
        require(
            result.programmes.size <= 20_000 &&
                result.programmes.all {
                    it.channel in selected && TvRules.valid(it)
                },
        )
        require(result.programmes.all { it.startUtc < request.endUtc && it.endUtc > request.startUtc })
        require(result.unavailable.all { it in selected } && result.failed.all { it in selected })
        require(result.unavailable.intersect(result.failed).isEmpty())
        require(result.programmes.none { it.channel in result.unavailable })
        val availableFrom = result.availableFromUtc
        val availableUntil = result.availableUntilUtc
        require(availableFrom == null || availableFrom > 0)
        require(availableUntil == null || availableUntil > 0)
        require(availableFrom == null || availableUntil == null || availableUntil >= availableFrom)
    }

    private data class GuideBatch(val values: Map<TvChannelRef, CachedGuide>, val failed: Set<TvChannelRef>)

    suspend fun resolve(ref: TvChannelRef): TvPlayback = withContext(io) {
        require(TvRules.valid(ref))
        val registration = registry.state.value[ref.sourceId] ?: throw IOException("TV extension unavailable")
        withTimeout(45_000) { registration.source.resolve(ref) }.also { playback ->
            checkCurrent(ref.sourceId, registration)
            require(playback.channel.ref == ref && TvRules.valid(playback.channel))
            require(allowAdult() || !playback.channel.adult)
            require(playback.streams.size in 1..32 && playback.streams.all(TvRules::valid))
        }
    }

    private fun checkCurrent(id: Long, registration: TvRegistration) {
        if (registry.state.value[id] !== registration) throw CancellationException("TV extension changed")
    }

    private suspend fun readCatalogue(cache: TvSourceCache, key: String, sourceId: Long): CachedCatalogue? = cache.get(
        key,
    )?.let {
        runCatching {
            json.decodeFromString<CachedCatalogue>(it.content).also { value ->
                require(value.checkedAt > 0)
                validateCatalogue(value.value, sourceId)
            }
        }.getOrNull()
    }

    private fun validateCatalogue(page: TvCatalogue, sourceId: Long) {
        require(page.channels.size <= 30_000 && page.channels.all { it.ref.sourceId == sourceId && TvRules.valid(it) })
        require(page.nextCursor?.let { it.length in 1..2048 && it.none(Char::isISOControl) } != false)
        require(page.channels.map { it.ref }.distinct().size == page.channels.size)
        require(page.sections.size <= 64 && page.sections.all(TvRules::valid))
        require(page.options?.let(TvRules::valid) != false)
        val refs = page.channels.map { it.ref }.toSet()
        require(page.sections.all { it.channels.all { ref -> ref in refs } })
    }

    @Serializable
    private data class CachedCatalogue(val value: TvCatalogue, val checkedAt: Long, val revision: String = "")

    @Serializable private data class CachedGuide(val value: TvGuide, val checkedAt: Long, val revision: String = "")

    @Serializable private data class CachedChannel(val value: TvChannel, val checkedAt: Long)
}

private fun sha256(value: String): String = java.security.MessageDigest.getInstance(
    "SHA-256",
).digest(value.toByteArray())
    .joinToString("") { "%02x".format(it) }
