package eu.kanade.tachiyomi.data.cloud.sync

import android.content.Context
import androidx.preference.PreferenceManager
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.data.discovery.ResumeVisibility
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import nyanime.telegram.sync.SyncRef
import nyanime.telegram.sync.syncJson
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.data.handlers.anime.AnimeDatabaseHandler
import tachiyomi.data.handlers.manga.MangaDatabaseHandler
import tachiyomi.domain.entries.anime.repository.AnimeRepository
import tachiyomi.domain.entries.manga.repository.MangaRepository
import tachiyomi.domain.items.chapter.repository.ChapterRepository
import tachiyomi.domain.items.episode.repository.EpisodeRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Preferences referring to content must be remapped; local row IDs never leave this adapter. */
internal class CloudSyncContentSettingsPort(
    private val context: Context,
    private val store: CloudSyncStore,
    private val preferences: CloudSyncPreferences,
    private val now: () -> Long,
) : CloudSyncDomainPort {
    private val local: PreferenceStore = Injekt.get()
    private val ui = UiPreferences(local)
    private val hidden = ResumeVisibility(local).hidden
    private val intros = PlayerPreferences(local).autoSkipIntroOverrides()
    private val anime: AnimeRepository = Injekt.get()
    private val manga: MangaRepository = Injekt.get()
    private val episodes: EpisodeRepository = Injekt.get()
    private val chapters: ChapterRepository = Injekt.get()
    private val animeDb: AnimeDatabaseHandler = Injekt.get()
    private val mangaDb: MangaDatabaseHandler = Injekt.get()
    private var observed = emptyMap<String, Set<String>>()
    private var policy: String? = null
    private val selections get() = mapOf(
        "hidden" to hidden,
        "intro" to intros,
        "dismissed" to ui.dismissedLibraryUpdates(),
        "viewed" to ui.viewedLibraryShelfNotices(),
    )

    override suspend fun configure(enabled: Boolean, incognito: Boolean, excludedSources: Set<Long>) = Unit
    override suspend fun seed() {
        capture()
    }

    override suspend fun capture(limit: Long): Int {
        val currentPolicy = store.state("exclusions")
        if (currentPolicy != policy) {
            observed = emptyMap()
            policy = currentPolicy
        }
        var changes = 0
        for ((kind, preference) in selections) {
            if (!enabled(kind)) continue
            val values = preference.get()
            if (observed[kind] == values) continue
            flush()
            val bindings = resolve(kind, values)
            val stateKey = "content-preference/$kind"
            val previous = store.state(stateKey)?.let {
                syncJson.decodeFromString<List<Binding>>(it)
            }.orEmpty()
            // A remote preference write may have survived an interruption before its acknowledgment.
            for ((intentKey, intent) in store.pendingIntents("content-intent/$kind/")) {
                val value = bindings.firstOrNull { it.ref == intent.record.ref }?.value
                val fields = intent.record.fields.mapValues { it.value.resolved()?.value }
                if (value == fields["value"]?.takeUnless { it == JsonNull }) store.applied(intent, fields)
                store.state(intentKey, "")
            }
            val current = bindings.associateBy { it.ref }
            for (ref in (previous.map { it.ref } + current.keys).distinct()) {
                if (store.isExcluded(ref)) continue
                changes += store.capture(ref, mapOf("value" to current[ref]?.value), now())
            }
            store.state(stateKey, syncJson.encodeToString(bindings))
            observed = observed + (kind to values)
        }
        return changes
    }

    private suspend fun resolve(kind: String, values: Set<String>): List<Binding> = when (kind) {
        "hidden" -> values.mapNotNull { raw ->
            raw.toLongOrNull()?.let { anime.getAnimeById(it) }?.let {
                Binding(SyncRef("anime-choice-hidden", it.source, it.url), JsonPrimitive(true))
            }
        }
        "intro" -> values.mapNotNull { raw ->
            val pieces = raw.split(':', limit = 4)
            if (pieces.size != 4 ||
                pieces[0] !in setOf("on", "off") ||
                pieces[2].toIntOrNull() != pieces[3].length
            ) {
                return@mapNotNull null
            }
            val source = pieces[1].toLongOrNull() ?: return@mapNotNull null
            Binding(SyncRef("anime-choice-intro", source, pieces[3]), JsonPrimitive(pieces[0] == "on"))
        }
        "viewed" -> values.mapNotNull { raw ->
            val pieces = raw.split(':')
            if (pieces.size != 3) return@mapNotNull null
            val id = pieces[1].toLongOrNull() ?: return@mapNotNull null
            val at = pieces[2].toLongOrNull() ?: return@mapNotNull null
            when (pieces[0]) {
                "anime" -> animeDb.awaitOneOrNull { cloudSyncQueries.noticeReference(id) }?.takeIf {
                    it.created_at == at
                }?.let {
                    Binding(SyncRef("anime-choice-viewed", it.source, it.title_url, it.item_url), JsonPrimitive(true))
                }
                "manga" -> mangaDb.awaitOneOrNull { cloudSyncQueries.noticeReference(id) }?.takeIf {
                    it.created_at == at
                }?.let {
                    Binding(SyncRef("manga-choice-viewed", it.source, it.title_url, it.item_url), JsonPrimitive(true))
                }
                else -> null
            }
        }
        "dismissed" -> {
            val after = values.mapNotNull { it.substringBefore('|').toLongOrNull() }.minOrNull()
            if (after == null) {
                emptyList()
            } else {
                buildList {
                    animeDb.awaitList { cloudSyncQueries.updateReferences(after) }.forEach {
                        val key = "${it.date_fetch}|anime|${it.source}|${it.title}|${it.name}"
                        if (key in values) {
                            add(
                                Binding(
                                    SyncRef("anime-choice-dismissed", it.source, it.title_url, it.item_url),
                                    JsonPrimitive(true),
                                ),
                            )
                        }
                    }
                    mangaDb.awaitList { cloudSyncQueries.updateReferences(after) }.forEach {
                        val key = "${it.date_fetch}|manga|${it.source}|${it.title}|${it.name}"
                        if (key in values) {
                            add(
                                Binding(
                                    SyncRef("manga-choice-dismissed", it.source, it.title_url, it.item_url),
                                    JsonPrimitive(true),
                                ),
                            )
                        }
                    }
                }
            }
        }
        else -> emptyList()
    }.filter { it.ref.source != 0L && !store.isExcluded(it.ref) }.distinctBy { it.ref }

    override suspend fun apply(projection: CloudSyncStore.Projection): Boolean {
        val ref = projection.record.ref
        val kind = ref.kind.substringAfter("-choice-", "")
        val preference = selections[kind] ?: return false
        if (!enabled(kind) || store.isExcluded(ref)) return false
        capture()
        if (store.record(ref) != projection.record) return false
        val fields = projection.record.fields.mapValues { it.value.resolved()?.value }
        val value = fields["value"]?.takeUnless { it == JsonNull }?.let {
            requireNotNull(it.jsonPrimitive.booleanOrNull)
        }
        val source = ref.source ?: return false
        val isAnime = ref.kind.startsWith("anime-")
        val title = if (isAnime) anime.getAnimeByUrlAndSourceId(ref.title, source) else null
        val comic = if (!isAnime) manga.getMangaByUrlAndSourceId(ref.title, source) else null
        val titleId = title?.id ?: comic?.id ?: return false
        val episode = if (isAnime &&
            ref.item.isNotEmpty()
        ) {
            episodes.getEpisodeByUrlAndAnimeId(ref.item, titleId)
        } else {
            null
        }
        val chapter = if (!isAnime &&
            ref.item.isNotEmpty()
        ) {
            chapters.getChapterByUrlAndMangaId(ref.item, titleId)
        } else {
            null
        }
        val itemId = episode?.id ?: chapter?.id
        val before = preference.get()
        val next = when (kind) {
            "hidden" -> if (value == true) before + titleId.toString() else before - titleId.toString()
            "intro" -> {
                val key = "$source:${ref.title.length}:${ref.title}"
                (before - "on:$key" - "off:$key") +
                    if (value == null) emptySet() else setOf("${if (value) "on" else "off"}:$key")
            }
            "dismissed" -> {
                if (itemId == null) return false
                val date = episode?.dateFetch ?: requireNotNull(chapter).dateFetch
                val name = episode?.name ?: requireNotNull(chapter).name
                val medium = if (isAnime) "anime" else "manga"
                val key = "$date|$medium|$source|${title?.title ?: comic?.title}|$name"
                if (value == true) before + key else before - key
            }
            "viewed" -> {
                if (itemId == null) return false
                val at = if (isAnime) {
                    animeDb.awaitOneOrNull { cloudSyncQueries.noticeReference(itemId) }?.created_at
                } else {
                    mangaDb.awaitOneOrNull { cloudSyncQueries.noticeReference(itemId) }?.created_at
                } ?: return false // Keep pending until the local release notice exists.
                val key = "${if (isAnime) "anime" else "manga"}:$itemId:$at"
                if (value == true) before + key else before - key
            }
            else -> return false
        }
        val intentKey = "content-intent/$kind/${ref.key()}"
        store.state(intentKey, syncJson.encodeToString(projection))
        val applied = withContext(Dispatchers.Main.immediate) {
            if (preference.get() != before) {
                false
            } else {
                preference.set(next)
                true
            }
        }
        if (!applied) {
            store.state(intentKey, "")
            return false
        }
        flush()
        store.applied(projection, fields)
        store.state(intentKey, "")
        // Re-resolve on the next pass so restart and normal execution share the same baseline path.
        observed = observed - kind
        return true
    }

    private fun enabled(kind: String) = if (kind == "intro") {
        preferences.sharedPlayback().get()
    } else {
        preferences.sharedPreferences().get()
    }

    private suspend fun flush() = withContext(Dispatchers.IO) {
        check(PreferenceManager.getDefaultSharedPreferences(context).edit().commit())
    }

    @Serializable
    private data class Binding(val ref: SyncRef, val value: JsonElement)
}
