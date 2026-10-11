package eu.kanade.tachiyomi.data.cloud.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import nyanime.telegram.sync.SyncRecord
import nyanime.telegram.sync.SyncRef
import nyanime.telegram.sync.SyncRegister
import nyanime.telegram.sync.syncJson
import java.util.UUID

internal interface CloudSyncDomainPort {
    suspend fun configure(enabled: Boolean, incognito: Boolean, excludedSources: Set<Long>)
    suspend fun capture(limit: Long = 50): Int
    suspend fun seed()
    suspend fun apply(projection: CloudSyncStore.Projection): Boolean
    suspend fun pendingCount(): Long = 0
    fun blockedCount(): Int = 0
}

internal data class SyncDirty(
    val revision: Long,
    val kind: String,
    val source: Long,
    val title: String,
    val item: String,
    val removed: Long,
    val changedAt: Long,
)

internal val syncDirtyMapper: (Long, String, Long, String, String, Long, Long) -> SyncDirty = ::SyncDirty

internal val present = mapOf(SyncRegister.EXISTS to JsonPrimitive(true))
internal val removed = mapOf(SyncRegister.EXISTS to JsonPrimitive(false))

internal fun SyncRecord.objectValues(): JsonObject =
    JsonObject(fields.mapValues { it.value.resolved()?.value ?: JsonNull })

/** Category labels may be matched during initial association; content titles never are. */
internal fun initialCategoryKey(media: String, name: String): String = UUID.nameUUIDFromBytes(
    "nyanime-category|1|$media|${name.trim().lowercase(java.util.Locale.ROOT)}".toByteArray(Charsets.UTF_8),
).toString()

@Serializable
internal data class PortableCategory(val name: String, val order: Long, val flags: Long, val hidden: Boolean)

internal class SyncPositionCapture(
    private val store: CloudSyncStore,
    private val deviceName: () -> String,
    private val now: () -> Long,
) {
    private val session = UUID.randomUUID().toString()
    fun projectedBaseline(
        ref: SyncRef,
        fields: Map<String, JsonElement?>,
        position: Long,
        duration: Long,
    ): Map<String, JsonElement?> {
        val device = store.device()
        val field = "position/$device"
        val previous = store.baseline(ref, field)?.let {
            runCatching { syncJson.decodeFromJsonElement(PortablePosition.serializer(), it) }.getOrNull()
        }
        // Importing another phone's point is not activity on THIS phone.
        val baseline = PortablePosition(
            position,
            duration,
            device,
            deviceName().take(80).ifBlank { "Android" },
            previous?.session ?: session,
            previous?.at ?: 0,
        )
        return fields + (field to syncJson.encodeToJsonElement(baseline))
    }

    fun fields(ref: SyncRef, position: Long, duration: Long): Map<String, JsonElement> {
        val device = store.device()
        val field = "position/$device"
        val previous = store.baseline(ref, field)
            ?.let { runCatching { syncJson.decodeFromJsonElement(PortablePosition.serializer(), it) }.getOrNull() }
        // A bookmark or metadata update is not a new reading/watching session.
        if (previous?.position == position && previous.duration == duration) {
            return mapOf(field to syncJson.encodeToJsonElement(previous))
        }
        val current = PortablePosition(
            position.coerceAtLeast(0),
            duration.coerceAtLeast(0),
            device,
            deviceName().take(80).ifBlank { "Android" },
            session,
            now(),
        )
        current.validate()
        val value = syncJson.encodeToJsonElement(current)
        return mapOf(field to value, "current-position" to value)
    }
}

internal fun SyncRecord.positions(): List<PortablePosition> {
    val current = fields["current-position"]?.versions.orEmpty().mapNotNull { version ->
        version.value?.let {
            runCatching {
                syncJson.decodeFromJsonElement(PortablePosition.serializer(), it).also { value ->
                    value.validate()
                    require(value.device == version.dot.device)
                }
            }.getOrNull()
        }
    }.sortedWith(compareByDescending<PortablePosition> { it.at }.thenBy { it.device })
    val previous = fields.asSequence()
        .filter { it.key.startsWith("position/") }
        .flatMap { it.value.versions.asSequence() }
        .mapNotNull { version ->
            version.value?.let {
                runCatching {
                    syncJson.decodeFromJsonElement(PortablePosition.serializer(), it).also { value ->
                        value.validate()
                        require(value.device == version.dot.device && version.field == "position/${value.device}")
                    }
                }.getOrNull()
            }
        }.distinctBy { it.device to it.session }.sortedWith(
            compareByDescending<PortablePosition> { it.at }.thenBy { it.device },
        ).toList()
    return (current + previous).distinctBy { it.device to it.session }
}
