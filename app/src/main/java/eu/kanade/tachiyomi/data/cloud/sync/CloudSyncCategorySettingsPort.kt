package eu.kanade.tachiyomi.data.cloud.sync

import android.content.Context
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import nyanime.telegram.sync.SyncCheckpoints
import nyanime.telegram.sync.SyncRef
import nyanime.telegram.sync.syncJson
import tachiyomi.core.common.preference.Preference
import tachiyomi.data.handlers.anime.AnimeDatabaseHandler
import tachiyomi.data.handlers.manga.MangaDatabaseHandler
import tachiyomi.domain.library.service.LibraryPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Category selections refer to replicated category identities, never the sender's numeric row IDs. */
internal class CloudSyncCategorySettingsPort(
    private val context: Context,
    private val store: CloudSyncStore,
    private val preferences: CloudSyncPreferences,
    private val now: () -> Long,
) : CloudSyncDomainPort {
    private val library: LibraryPreferences = Injekt.get()
    private val anime: AnimeDatabaseHandler = Injekt.get()
    private val manga: MangaDatabaseHandler = Injekt.get()
    private val entries = listOf(
        Selection("anime", "default", library.defaultAnimeCategory()),
        Selection("manga", "default", library.defaultMangaCategory()),
        Selection("anime", "updates", library.animeUpdateCategories()),
        Selection("manga", "updates", library.mangaUpdateCategories()),
        Selection("anime", "excluded", library.animeUpdateCategoriesExclude()),
        Selection("manga", "excluded", library.mangaUpdateCategoriesExclude()),
    )
    private var observed = emptyMap<SyncRef, Any?>()
    private var policy: String? = null

    override suspend fun configure(enabled: Boolean, incognito: Boolean, excludedSources: Set<Long>) = Unit
    override suspend fun seed() {
        capture()
    }

    override suspend fun capture(limit: Long): Int {
        if (!preferences.sharedPreferences().get()) return 0
        if (policy != store.state("exclusions")) {
            policy = store.state("exclusions")
            observed = emptyMap()
        }
        var changes = 0
        for (entry in entries) {
            val ref = entry.ref
            val raw = entry.preference.get()
            if (observed[ref] == raw) continue
            if (!entry.preference.isSet() && store.record(ref) == null) continue
            val fields = fields(entry) ?: continue
            flush()
            val intentKey = "category-preference-intent/${ref.key()}"
            store.state(intentKey)?.takeIf { it.isNotEmpty() }?.let {
                val intent = syncJson.decodeFromString<CloudSyncStore.Projection>(it)
                val desired = intent.record.fields.mapValues { value -> value.value.resolved()?.value }
                if (fields == desired) store.applied(intent, fields)
                store.state(intentKey, "")
            }
            changes += store.capture(ref, fields, now())
            observed = observed + (ref to raw)
        }
        return changes
    }

    private suspend fun fields(entry: Selection): Map<String, JsonElement?>? {
        val raw = entry.preference.get()
        if (raw is Int) {
            val key = shared(entry.medium, raw.toLong()) ?: return null
            return mapOf("value" to JsonPrimitive(key))
        }
        val selected = (raw as Set<*>).filterIsInstance<String>().map { value ->
            shared(entry.medium, value.toLongOrNull() ?: return null) ?: return null
        }.associate { key -> "member/${SyncCheckpoints.hash(key)}" to JsonPrimitive(key) }
        return (selected.keys + store.baselineFields(entry.ref)).associateWith { selected[it] }
    }

    override suspend fun apply(projection: CloudSyncStore.Projection): Boolean {
        val entry = entries.firstOrNull { it.ref == projection.record.ref } ?: return false
        if (!preferences.sharedPreferences().get()) return false
        capture()
        if (store.record(entry.ref) != projection.record) return false
        val fields = projection.record.fields.mapValues { it.value.resolved()?.value }
        val next: Any = if (entry.preference.defaultValue() is Int) {
            val key = fields["value"]?.jsonPrimitive?.content ?: return false
            local(entry.medium, key)?.toInt() ?: return false
        } else {
            fields.filterKeys { it.startsWith("member/") }.mapNotNull { (field, value) ->
                if (value == null || value == JsonNull) return@mapNotNull null
                val key = value.jsonPrimitive.content
                require(field == "member/${SyncCheckpoints.hash(key)}")
                val id = local(entry.medium, key) ?: return false
                id.takeIf { it >= 0 }?.toString()
            }.toSet()
        }
        val before = entry.preference.get()
        val intentKey = "category-preference-intent/${entry.ref.key()}"
        store.state(intentKey, syncJson.encodeToString(projection))
        val applied = withContext(Dispatchers.Main.immediate) {
            if (entry.preference.get() != before) {
                false
            } else {
                @Suppress("UNCHECKED_CAST")
                (entry.preference as Preference<Any>).set(next)
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
        observed = observed + (entry.ref to next)
        return true
    }

    private suspend fun shared(medium: String, id: Long): String? {
        if (id <= 0) {
            require(id in -1L..0L)
            return "system:$id"
        }
        return if (medium == "anime") {
            anime.awaitOneOrNull { cloudSyncQueries.categoryKey(id) }
        } else {
            manga.awaitOneOrNull { cloudSyncQueries.categoryKey(id) }
        }
    }

    private suspend fun local(medium: String, key: String): Long? {
        if (key.startsWith("system:")) {
            return requireNotNull(key.substringAfter(':').toLongOrNull()).also { require(it in -1L..0L) }
        }
        if (store.record(SyncRef("$medium-category", title = key))?.deleted == true) return -1
        return if (medium == "anime") {
            anime.awaitOneOrNull { cloudSyncQueries.localCategory(key) }
        } else {
            manga.awaitOneOrNull { cloudSyncQueries.localCategory(key) }
        }
    }

    private suspend fun flush() = withContext(Dispatchers.IO) {
        check(PreferenceManager.getDefaultSharedPreferences(context).edit().commit())
    }

    private data class Selection(val medium: String, val name: String, val preference: Preference<*>) {
        val ref get() = SyncRef("$medium-category-preference", title = name)
    }
}
