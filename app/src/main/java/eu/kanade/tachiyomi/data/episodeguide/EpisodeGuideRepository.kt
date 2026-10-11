package eu.kanade.tachiyomi.data.episodeguide

import android.content.Context
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.data.rin.RinGuideRuntime
import eu.kanade.tachiyomi.data.rin.RinRepository
import eu.kanade.tachiyomi.data.track.SourceTrackingHints
import eu.kanade.tachiyomi.data.workinfo.FileWorkInfoCache
import eu.kanade.tachiyomi.data.workinfo.SourceWorkIdentity
import eu.kanade.tachiyomi.data.workinfo.WorkCacheEntry
import eu.kanade.tachiyomi.data.workinfo.WorkIdentity
import eu.kanade.tachiyomi.data.workinfo.WorkInfoPreferences
import eu.kanade.tachiyomi.data.workinfo.WorkKind
import eu.kanade.tachiyomi.util.system.isOnline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import nyanime.addon.api.RinEpisodeGuideProvider
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.entries.anime.model.Anime
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Host understands only the signed capability and generic guide schema. */
class EpisodeGuideRepository private constructor(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    val preferences = EpisodeGuidePreferences(Injekt.get<PreferenceStore>(), json)
    private val base = Injekt.get<BasePreferences>()
    private val cache = FileWorkInfoCache(File(context.cacheDir, "episode-guides-v1"), json)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutable = MutableStateFlow<Map<String, GuideSnapshot>>(emptyMap())
    val states = mutable.asStateFlow()
    private val revisions = ConcurrentHashMap<String, Long>()
    private val sessionBindings = ConcurrentHashMap<String, GuideBinding>()
    private val sessionOverrides = ConcurrentHashMap<String, Int>()
    private val sessionHide = ConcurrentHashMap<String, Boolean>()
    private val sessionSkip = ConcurrentHashMap<String, Int>()
    private val transport = EpisodeGuideCache(cache, scope, json)

    @Volatile private var activeProviders: Set<String> = emptySet()
    init {
        scope.launch {
            RinRepository.get(context).installed.collect { records ->
                activeProviders = records.filter {
                    it.enabled &&
                        it.trusted &&
                        RinRepository.compatible(it.manifest) &&
                        "episode-guides.v1" in it.manifest.guide?.capabilities.orEmpty()
                }.mapTo(mutableSetOf()) { it.manifest.packageName + ":" + it.signer }
                synchronized(mutable) {
                    mutable.value = mutable.value.mapValues { (key, state) ->
                        val revision = revisions.merge(key, 1L, Long::plus) ?: 0L
                        state.copy(revision = revision, loading = false)
                    }
                }
            }
        }
        scope.launch {
            base.incognitoMode().changes().collect { privateMode ->
                synchronized(mutable) { mutable.value = emptyMap() }
                if (!privateMode) {
                    sessionBindings.clear()
                    sessionOverrides.clear()
                    sessionHide.clear()
                    sessionSkip.clear()
                    transport.clearPrivate()
                }
            }
        }
    }

    fun key(anime: Anime) = WorkInfoPreferences.localKey(false, anime.source, anime.url)
    fun snapshot(anime: Anime) = states.value[key(anime)] ?: GuideSnapshot()
    fun available() = RinRepository.get(context).installed.value.any {
        it.enabled &&
            it.trusted &&
            RinRepository.compatible(it.manifest) &&
            "episode-guides.v1" in it.manifest.guide?.capabilities.orEmpty()
    }
    fun hidden(anime: Anime) = if (base.incognitoMode().get()) {
        sessionHide[key(anime)] ?: preferences.hide(anime).get()
    } else {
        preferences.hide(anime).get()
    }
    fun setHidden(anime: Anime, value: Boolean) {
        if (base.incognitoMode().get()) sessionHide[key(anime)] = value else preferences.hide(anime).set(value)
        changed(anime)
    }
    fun skipOverride(anime: Anime) = if (base.incognitoMode().get()) {
        sessionSkip[key(anime)] ?: preferences.skipOverride(anime).get()
    } else {
        preferences.skipOverride(anime).get()
    }
    fun setSkipOverride(anime: Anime, value: Int) {
        if (base.incognitoMode().get()) sessionSkip[key(anime)] = value else preferences.skipOverride(anime).set(value)
        changed(anime)
    }
    fun shouldSkip(anime: Anime) = preferences.enabled().get() &&
        when (skipOverride(anime)) {
            1 -> true
            0 -> false
            else -> preferences.skip().get()
        }
    private fun binding(anime: Anime) = if (base.incognitoMode().get()) {
        sessionBindings[key(anime)] ?: preferences.binding(anime)
    } else {
        preferences.binding(anime)
    }

    fun identity(anime: Anime): WorkIdentity {
        val hints = SourceTrackingHints.from(anime)
        return SourceWorkIdentity.apply(
            WorkIdentity(key(anime), anime.title, WorkKind.VIDEO, hints?.titles.orEmpty(), malId = hints?.malId),
            anime.memo["nyanime.work.v1"] as? JsonObject,
        )
    }

    fun classification(anime: Anime, url: String, number: Double, nativeFiller: Boolean): EpisodeClassification {
        val own = if (base.incognitoMode().get()) sessionOverrides[key(anime) + url] else null
        val override = if (own != null) {
            when (own) {
                1 -> true
                0 -> false
                else -> null
            }
        } else {
            preferences.override(anime, url)
        }
        val current = snapshot(anime)
        val providerActive = current.binding?.provider in activeProviders
        val automatic = if (preferences.enabled().get() && providerActive) {
            EpisodeGuidePolicy.classification(current.guide, number, current.binding?.offset ?: 0)
        } else {
            EpisodeClassification.UNKNOWN
        }
        return EpisodeGuidePolicy.effective(override, nativeFiller, automatic)
    }

    fun setOverride(anime: Anime, url: String, value: Boolean?) {
        if (base.incognitoMode().get()) {
            sessionOverrides[key(anime) + url] = value?.let { if (it) 1 else 0 } ?: -1
        } else {
            preferences.setOverride(anime, url, value)
        }
        changed(anime)
    }

    fun associate(anime: Anime, value: GuideBinding) {
        if (base.incognitoMode().get()) sessionBindings[key(anime)] = value else preferences.associate(anime, value)
        changed(anime)
        update(anime, GuideSnapshot(binding = value, excluded = value.reference == null))
    }
    fun changed(anime: Anime) {
        val revision = revisions.merge(key(anime), 1L, Long::plus) ?: 0
        update(anime, snapshot(anime).copy(revision = revision))
    }
    private fun update(anime: Anime, value: GuideSnapshot) {
        synchronized(mutable) {
            val values = LinkedHashMap(mutable.value)
            values.remove(key(anime))
            values[key(anime)] = value
            while (values.size > 256 || values.values.sumOf { it.estimatedBytes } > 32 * 1024 * 1024) {
                values.remove(values.keys.first())
            }
            mutable.value = values
        }
    }

    suspend fun refresh(anime: Anime, work: WorkIdentity = identity(anime), search: Boolean = false) {
        if (!preferences.enabled().get()) return
        val revision = revisions.merge(key(anime), 1L, Long::plus) ?: 0
        val bound = binding(anime)
        if (!search && bound != null && bound.reference == null) {
            update(anime, GuideSnapshot(binding = bound, excluded = true))
            return
        }
        val providers = withContext(Dispatchers.IO) {
            RinRepository.get(context).installed.value.filter {
                it.enabled &&
                    it.trusted &&
                    "episode-guides.v1" in it.manifest.guide?.capabilities.orEmpty() &&
                    RinRepository.compatible(it.manifest)
            }.mapNotNull {
                try {
                    val loaded = RinGuideRuntime.load(context, it.manifest.packageName)
                    val provider = loaded.entry as? RinEpisodeGuideProvider ?: return@mapNotNull null
                    Triple(it.manifest.packageName + ":" + it.signer, provider, loaded.context)
                } catch (_: Exception) {
                    null
                }
            }
        }
        if (providers.isEmpty()) {
            update(anime, GuideSnapshot())
            return
        }
        val persist = !base.incognitoMode().get()
        val network = !base.downloadedOnly().get() && context.isOnline()
        var result = snapshot(anime).copy(loading = true, unavailable = false)
        if (bound?.provider != null && bound.reference != null && !search) {
            val saved = transport.saved("guide:${bound.provider}:${bound.reference}", persist)
            saved?.let {
                runCatching { json.decodeFromString<EpisodeGuide>(it.payload) }.getOrNull()
                    ?.takeIf { it.valid() }?.let { result = result.copy(guide = it, binding = bound) }
            }
        }
        update(anime, result)
        try {
            val candidates = mutableListOf<Pair<String, GuideCandidate>>()
            var selected = bound.takeUnless { search }
            if (selected == null) {
                val request = json.encodeToString(
                    GuideQuery(work.title, work.aliases, work.year, work.format, work.season, work.part, work.malId),
                )
                for ((id, provider, isolated) in providers) {
                    try {
                        val raw = transport.load(
                            "search:$id:${WorkInfoPreferences.digest(request)}",
                            network,
                            persist,
                        ) { requestId ->
                            call(provider, requestId) { provider.search(isolated, requestId, request) }
                        }
                        val found = json.decodeFromString<List<GuideCandidate>>(raw).take(30)
                            .filter { it.valid() }
                        candidates += found.map { id to it }
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        result = result.copy(unavailable = true)
                    }
                }
                // Never choose between providers or editions silently.
                val automatic = candidates.groupBy { it.first }.values.singleOrNull()?.let {
                    EpisodeGuidePolicy.automatic(work, it.map { item -> item.second })
                }
                if (!search && automatic != null) {
                    selected = GuideBinding(candidates.first { it.second == automatic }.first, automatic.reference)
                }
            }
            result = result.copy(candidates = candidates)
            if (selected?.reference != null) {
                val (id, provider, isolated) = providers.firstOrNull { it.first == selected.provider }
                    ?: error("Guide provider unavailable")
                val reference = requireNotNull(selected.reference)
                val raw = transport.load("guide:$id:$reference", network, persist) { requestId ->
                    call(provider, requestId) {
                        provider.guide(isolated, requestId, reference)
                    }.also { validateGuide(it, reference) }
                }
                val guide = json.decodeFromString<EpisodeGuide>(raw)
                require(guide.valid() && guide.work.reference == reference)
                result = result.copy(guide = guide, binding = selected)
                if (bound == null &&
                    (revisions[key(anime)] ?: 0L) == revision &&
                    persist != base.incognitoMode().get()
                ) {
                    if (persist) preferences.associate(anime, selected) else sessionBindings[key(anime)] = selected
                }
            }
            if ((revisions[key(anime)] ?: 0L) == revision && persist != base.incognitoMode().get()) {
                update(anime, result.copy(loading = false))
            }
        } catch (cancel: CancellationException) {
            if ((revisions[key(anime)] ?: 0L) == revision) update(anime, snapshot(anime).copy(loading = false))
            throw cancel
        } catch (_: Exception) {
            if ((revisions[key(anime)] ?: 0L) ==
                revision
            ) {
                update(anime, result.copy(loading = false, unavailable = true))
            }
        }
    }

    suspend fun preview(providerId: String, reference: String): EpisodeGuide = withContext(Dispatchers.IO) {
        val record = RinRepository.get(context).installed.value.first {
            it.manifest.packageName + ":" + it.signer == providerId
        }
        val loaded = RinGuideRuntime.load(context, record.manifest.packageName)
        val provider = loaded.entry as RinEpisodeGuideProvider
        val raw = transport.load(
            "guide:$providerId:$reference",
            !base.downloadedOnly().get() && context.isOnline(),
            !base.incognitoMode().get(),
        ) { id ->
            call(provider, id) { provider.guide(loaded.context, id, reference) }.also { validateGuide(it, reference) }
        }
        validateGuide(raw, reference)
    }

    private fun validateGuide(raw: String, reference: String) = json.decodeFromString<EpisodeGuide>(raw)
        .also { require(it.valid() && it.work.reference == reference) }

    private suspend fun call(provider: RinEpisodeGuideProvider, id: String, operation: () -> String): String =
        suspendCancellableCoroutine { continuation ->
            val worker = scope.launch {
                try {
                    if (!continuation.isActive) return@launch
                    val value = operation()
                    if (continuation.isActive) continuation.resume(value)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
            continuation.invokeOnCancellation {
                provider.cancel(id)
                worker.cancel()
            }
        }

    companion object {
        @Volatile private var instance: EpisodeGuideRepository? = null
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: EpisodeGuideRepository(context.applicationContext).also { instance = it }
        }
    }
}

@Serializable
internal data class GuideQuery(
    val title: String,
    val aliases: List<String>,
    val year: Int?,
    val format: String?,
    val season: Int?,
    val part: Int?,
    val malId: Long?,
)
