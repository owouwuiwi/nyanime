package eu.kanade.tachiyomi.data.cloud.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import nyanime.telegram.sync.SyncCheckpoints
import nyanime.telegram.sync.SyncRef
import tachiyomi.core.common.preference.PreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

internal class CloudSyncSettingsPort(
    private val context: android.content.Context,
    private val store: CloudSyncStore,
    private val preferences: CloudSyncPreferences,
    private val now: () -> Long,
    private val local: PreferenceStore = Injekt.get(),
) : CloudSyncDomainPort {
    private val settings = PortableSettings(local)
    private var previous: Map<String, Any?> = emptyMap()
    private var initialized = false
    private var enabledGroups: Set<SyncSettingsGroup> = emptySet()

    override suspend fun configure(enabled: Boolean, incognito: Boolean, excludedSources: Set<Long>) = Unit
    override suspend fun seed() {
        capture()
    }

    override suspend fun capture(limit: Long): Int {
        var count = 0
        val snapshot = local.getAll()
        val groups = SyncSettingsGroup.entries.filter { enabled(it) }.toSet()
        if (settings.entries.values.any {
                it.group in groups &&
                    (!initialized || it.group !in enabledGroups || previous[it.key] != snapshot[it.key])
            }
        ) {
            flush()
        }
        for (setting in settings.entries.values) {
            if (!enabled(setting.group)) continue
            val raw = snapshot[setting.key]
            if (initialized && setting.group in enabledGroups && previous[setting.key] == raw) continue
            val ref = SyncRef("preference", title = setting.key)
            val intentKey = "preference-intent/${ref.key()}"
            store.state(intentKey)?.takeIf { it.isNotEmpty() }?.let {
                val intent = nyanime.telegram.sync.syncJson.decodeFromString<CloudSyncStore.Projection>(it)
                val values = intent.record.fields.mapValues { field -> field.value.resolved()?.value }
                if (raw == desired(setting, values)) store.applied(intent, values)
                store.state(intentKey, "")
            }
            // Untouched defaults on a new installation do not replace deliberate choices elsewhere.
            if (raw == null && store.record(ref) == null) continue
            count += store.capture(ref, fields(setting, raw), now())
        }
        previous = snapshot.filterKeys { it in settings.entries }.mapValues { it.value }
        initialized = true
        enabledGroups = groups
        return count
    }

    override suspend fun apply(projection: CloudSyncStore.Projection): Boolean {
        if (projection.record.ref.kind != "preference") return false
        val setting = settings.entries[projection.record.ref.title] ?: return false
        if (!enabled(setting.group)) return false
        capture()
        val ref = projection.record.ref
        if (store.record(ref) != projection.record) return false
        val values = projection.record.fields.mapValues { it.value.resolved()?.value }
        val value = desired(setting, values)
        val observed = local.getAll()[setting.key]
        val intentKey = "preference-intent/${ref.key()}"
        store.state(intentKey, nyanime.telegram.sync.syncJson.encodeToString(projection))
        // Main-thread preference UI cannot interleave an edit between this comparison and the write.
        val applied = withContext(Dispatchers.Main.immediate) {
            if (local.getAll()[setting.key] != observed) {
                false
            } else {
                write(setting, value)
                true
            }
        }
        if (!applied) {
            store.state(intentKey, "")
            return false
        }
        flush()
        store.applied(projection, values)
        store.state(intentKey, "")
        previous = previous + (setting.key to value)
        return true
    }

    private fun desired(setting: PortableSetting, values: Map<String, JsonElement?>): Any? =
        if (setting.default is Set<*>) {
            values.filterKeys { it.startsWith("member/") }.mapNotNull { (field, value) ->
                if (value == null || value == JsonNull) return@mapNotNull null
                val member = value.jsonPrimitive.content
                require(field == "member/${SyncCheckpoints.hash(member)}" && member.length <= 8192)
                member
            }.toSet()
        } else {
            decode(setting, values["value"])
        }

    private fun fields(setting: PortableSetting, raw: Any?): Map<String, JsonElement?> {
        if (setting.default is Set<*>) {
            val values = (raw as? Set<*>)?.filterIsInstance<String>().orEmpty()
            val entries = values.associate { "member/${SyncCheckpoints.hash(it)}" to JsonPrimitive(it) }
            val ref = SyncRef("preference", title = setting.key)
            return (entries.keys + store.baselineFields(ref).filter { it.startsWith("member/") })
                .associateWith { entries[it] }
        }
        val value = when (raw) {
            null -> null
            is String -> JsonPrimitive(raw)
            is Boolean -> JsonPrimitive(raw)
            is Number -> JsonPrimitive(raw)
            else -> error("Unsupported portable preference")
        }
        return mapOf("value" to value)
    }

    private fun decode(setting: PortableSetting, value: JsonElement?): Any? {
        if (value == null || value == JsonNull) return null
        val primitive = value.jsonPrimitive
        return when (val default = setting.default) {
            is Boolean -> requireNotNull(primitive.booleanOrNull)
            is Int -> requireNotNull(primitive.intOrNull)
            is Long -> requireNotNull(primitive.longOrNull)
            is Float -> requireNotNull(primitive.floatOrNull).also { require(it.isFinite()) }
            is Enum<*> -> primitive.content.also { name ->
                require(default.javaClass.enumConstants.any { it.name == name })
            }
            else -> primitive.content.also { require(it.length <= 64 * 1024) }
        }
    }

    private fun write(setting: PortableSetting, value: Any?) {
        when (value) {
            null -> setting.preference.delete()
            is Boolean -> local.getBoolean(setting.key).set(value)
            is Int -> local.getInt(setting.key).set(value)
            is Long -> local.getLong(setting.key).set(value)
            is Float -> local.getFloat(setting.key).set(value)
            is String -> local.getString(setting.key).set(value)
            is Set<*> -> local.getStringSet(setting.key).set(value.filterIsInstance<String>().toSet())
            else -> error("Unsupported portable preference")
        }
    }

    /** Wait for asynchronous preference writes off the UI thread before acknowledging them. */
    private suspend fun flush() = withContext(Dispatchers.IO) {
        check(androidx.preference.PreferenceManager.getDefaultSharedPreferences(context).edit().commit())
    }

    private fun enabled(group: SyncSettingsGroup): Boolean = when (group) {
        SyncSettingsGroup.APPEARANCE -> preferences.sharedAppearance().get()
        SyncSettingsGroup.PLAYBACK -> preferences.sharedPlayback().get()
        SyncSettingsGroup.READER -> preferences.sharedReader().get()
        SyncSettingsGroup.CATALOGUES -> preferences.sharedCatalogues().get()
        SyncSettingsGroup.GENERAL -> preferences.sharedPreferences().get()
    }
}
