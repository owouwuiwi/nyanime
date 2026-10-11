package eu.kanade.tachiyomi.data.workinfo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.IOException
import java.time.Clock

data class WorkResolution(
    val reference: WorkReference? = null,
    val candidates: List<WorkSummary> = emptyList(),
    val partialFailure: Boolean = false,
    val excluded: Boolean = false,
)

/** No trackers, source clients, episode repositories or player dependencies enter this service. */
class WorkInfoRepository(
    providers: List<WorkInformationProvider>,
    private val cache: WorkInfoCache,
    private val json: Json,
    private val enabled: () -> Boolean,
    private val clock: Clock = Clock.systemUTC(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val providers = providers.associateBy { it.provider }
    private val gate = Mutex()
    private data class Flight(val result: Deferred<Result<String>>, var readers: Int)
    private val inFlight = mutableMapOf<String, Flight>()

    suspend fun resolve(
        identity: WorkIdentity,
        binding: WorkBinding?,
        language: String,
        network: Boolean,
        persist: Boolean,
        forceSearch: Boolean = false,
    ): WorkResolution = withContext(workDispatcher) {
        if (!enabled()) return@withContext WorkResolution()
        if (!forceSearch && binding != null) {
            val reference = binding.reference?.takeIf {
                it.isValid() && (it.kind == WorkKind.MANGA) == (identity.kind == WorkKind.MANGA)
            }
            return@withContext WorkResolution(reference, excluded = reference == null)
        }
        val references = identity.references.filter {
            it.isValid() && (it.kind == WorkKind.MANGA) == (identity.kind == WorkKind.MANGA)
        }.distinct()
        if (!forceSearch && references.size == 1) return@withContext WorkResolution(references.single())
        if (!forceSearch && references.isEmpty()) {
            val saved = cache.read(associationKey(identity))
            if (saved != null && (!network || saved.fresh(clock.millis()))) {
                val work = runCatching { json.decodeFromString<WorkSummary>(saved.payload) }.getOrNull()
                if (work != null &&
                    WorkIdentityMatcher.compatible(identity, work)
                ) {
                    return@withContext WorkResolution(work.reference)
                }
            }
        }
        if (!network) return@withContext WorkResolution()
        val result =
            search(if (forceSearch) identity.copy(malId = null, imdbId = null) else identity, language, persist)
        val resolvedExternal =
            !forceSearch && references.isEmpty() && (identity.malId != null || identity.imdbId != null)
        val match = if (references.size > 1) {
            null // Conflicting verified references require a deliberate choice, even if search returns only one.
        } else if (resolvedExternal) {
            result.candidates.filter { WorkIdentityMatcher.compatible(identity, it) }.singleOrNull()
        } else {
            WorkIdentityMatcher.match(identity, result.candidates)
        }
        result.copy(reference = match?.reference)
    }

    suspend fun rememberResolution(identity: WorkIdentity, information: WorkInformation, persist: Boolean) {
        if (persist && enabled() && WorkIdentityMatcher.compatible(identity, information.work)) {
            cache.write(associationKey(identity), WorkCacheEntry(clock.millis(), json.encodeToString(information.work)))
        }
    }

    private fun associationKey(identity: WorkIdentity) = "association:${WorkInfoPreferences.digest(identity.localKey)}"

    private suspend fun search(
        identity: WorkIdentity,
        language: String,
        persist: Boolean,
    ): WorkResolution = coroutineScope {
        val selected = when {
            identity.malId != null -> listOf(WorkProvider.ANILIST)
            identity.imdbId != null -> listOf(WorkProvider.TVMAZE, WorkProvider.WIKIDATA)
            identity.kind in setOf(WorkKind.MANGA, WorkKind.ANIME) -> listOf(WorkProvider.ANILIST)
            identity.kind == WorkKind.FILM -> listOf(WorkProvider.WIKIDATA)
            identity.kind == WorkKind.SERIES -> listOf(WorkProvider.TVMAZE)
            else -> WorkProvider.entries
        }
        val outcomes = selected.mapNotNull(providers::get).map { provider ->
            async {
                try {
                    val queries = if (identity.malId != null || identity.imdbId != null) {
                        listOf(identity.title)
                    } else {
                        (listOf(identity.title) + identity.aliases).filter(String::isNotBlank).distinct().take(2)
                    }
                    val candidates = mutableListOf<WorkSummary>()
                    var failed = false
                    for (title in queries) {
                        val key = "search:${provider.provider}:${identity.kind}:$language:" +
                            "$title:${identity.malId}:${identity.imdbId}"
                        try {
                            val encoded = load(key, true, persist, { decodable<List<WorkSummary>>(it) }) {
                                json.encodeToString(
                                    ListSerializer(WorkSummary.serializer()),
                                    provider.search(identity.copy(title = title), language),
                                )
                            }
                            candidates += json.decodeFromString<List<WorkSummary>>(encoded)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            failed = true
                            break // Respect provider backoff and keep already available candidates.
                        }
                        if (WorkIdentityMatcher.match(identity, candidates) != null) break
                    }
                    candidates.toList() to failed
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    emptyList<WorkSummary>() to true
                }
            }
        }.awaitAll()
        WorkResolution(
            candidates = outcomes.flatMap { it.first }.filter { it.reference.isValid() }.distinctBy { it.reference },
            partialFailure = outcomes.any { it.second },
        )
    }

    suspend fun cachedInformation(reference: WorkReference, language: String): WorkInformation? = withContext(
        workDispatcher,
    ) {
        if (!enabled() || !reference.isValid()) return@withContext null
        cache.read(infoKey(reference, language))?.let {
            runCatching { json.decodeFromString<WorkInformation>(it.payload) }.getOrNull()
                ?.takeIf { result -> result.work.reference == reference }
        }
    }

    suspend fun information(
        reference: WorkReference,
        language: String,
        network: Boolean,
        persist: Boolean,
    ): WorkInformation = withContext(
        workDispatcher,
    ) {
        require(reference.isValid())
        json.decodeFromString<WorkInformation>(
            load(infoKey(reference, language), network, persist, {
                runCatching {
                    json.decodeFromString<WorkInformation>(it).work.reference == reference
                }.getOrDefault(false)
            }) {
                val information = providers.getValue(reference.provider).information(reference, language)
                if (information.work.reference != reference) throw IOException("Mismatched catalogue identity")
                json.encodeToString(information)
            },
        ).also { if (it.work.reference != reference) throw IOException("Mismatched cached identity") }
    }

    suspend fun person(
        reference: WorkPersonReference,
        language: String,
        network: Boolean,
        persist: Boolean,
    ): WorkPerson = withContext(
        workDispatcher,
    ) {
        require(reference.isValid())
        json.decodeFromString<WorkPerson>(
            load("person:$reference:$language", network, persist, {
                runCatching { json.decodeFromString<WorkPerson>(it).reference == reference }.getOrDefault(false)
            }) {
                json.encodeToString(providers.getValue(reference.provider).person(reference, language))
            },
        )
    }

    suspend fun cachedPerson(reference: WorkPersonReference, language: String): WorkPerson? = withContext(
        workDispatcher,
    ) {
        if (!enabled() || !reference.isValid()) return@withContext null
        cache.read("person:$reference:$language")?.let {
            runCatching { json.decodeFromString<WorkPerson>(it.payload) }.getOrNull()
                ?.takeIf { result -> result.reference == reference }
        }
    }

    suspend fun works(
        reference: WorkPersonReference,
        page: Int,
        language: String,
        network: Boolean,
        persist: Boolean,
    ): WorkPage = withContext(
        workDispatcher,
    ) {
        require(reference.isValid() && page in 1..500)
        json.decodeFromString<WorkPage>(
            load("works:$reference:$page:$language", network, persist, { decodable<WorkPage>(it) }) {
                json.encodeToString(providers.getValue(reference.provider).works(reference, page, language))
            },
        )
    }

    suspend fun credits(
        reference: WorkReference,
        cast: Boolean,
        page: Int,
        language: String,
        network: Boolean,
        persist: Boolean,
    ): WorkCreditsPage = withContext(
        workDispatcher,
    ) {
        require(reference.isValid() && page in 1..500)
        val cached = cache.read(infoKey(reference, language))
            ?.takeIf { !network || it.fresh(clock.millis()) }
            ?.let { runCatching { json.decodeFromString<WorkInformation>(it.payload) }.getOrNull() }
            ?.takeIf { enabled() && it.work.reference == reference }
        if (cached != null && !(if (cast) cached.moreCast else cached.moreCreators)) {
            val complete = if (cast) cached.cast else cached.creators
            return@withContext WorkCreditsPage(complete.drop((page - 1) * 20).take(20), complete.size > page * 20)
        }
        json.decodeFromString<WorkCreditsPage>(
            load("credits:$reference:$cast:$page:$language", network, persist, { decodable<WorkCreditsPage>(it) }) {
                json.encodeToString(providers.getValue(reference.provider).credits(reference, cast, page, language))
            },
        )
    }

    private fun infoKey(reference: WorkReference, language: String) = "info:$reference:$language"

    private inline fun <reified T> decodable(value: String) = runCatching { json.decodeFromString<T>(value) }.isSuccess

    private suspend fun load(
        key: String,
        network: Boolean,
        persist: Boolean,
        valid: (String) -> Boolean,
        fetch: suspend () -> String,
    ): String {
        if (!enabled()) throw IOException("Work information disabled")
        val cached = cache.read(key)?.takeIf { valid(it.payload) }
        if (cached != null && (!network || cached.fresh(clock.millis()))) return cached.payload
        if (!network) throw IOException("No cached information")
        // Incognito readers never share a flight that writes a persistent cache entry.
        val flightKey = "$key:$persist"
        val flight = gate.withLock {
            inFlight.getOrPut(flightKey) {
                Flight(
                    scope.async(start = CoroutineStart.LAZY) {
                        try {
                            if (!enabled()) throw IOException("Work information disabled")
                            val value = withTimeoutOrNull(20_000) { fetch() } ?: throw IOException("Catalogue timeout")
                            if (!enabled()) throw IOException("Work information disabled")
                            if (persist && enabled()) cache.write(key, WorkCacheEntry(clock.millis(), value))
                            Result.success(value)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            // A catalogue outage must not cancel the caller's parent/sibling jobs.
                            Result.failure(error)
                        }
                    },
                    0,
                )
            }.also { it.readers++ }
        }
        try {
            return flight.result.await().getOrThrow()
        } finally {
            // NonCancellable is necessary when the last screen leaves during an HTTP request.
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                gate.withLock {
                    flight.readers--
                    if (flight.readers == 0) {
                        if (inFlight[flightKey] === flight) inFlight.remove(flightKey)
                        flight.result.cancel()
                    }
                }
            }
        }
    }
}
