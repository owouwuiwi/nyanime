package nyanime.telegram.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.security.MessageDigest
import java.util.UUID

/** Independent of database row IDs, extension implementations and the Telegram transport. */
@Serializable
data class SyncRef(
    val kind: String,
    val source: Long? = null,
    val title: String = "",
    val item: String = "",
) {
    fun validate() {
        require(kind.matches(Regex("[a-z][a-z0-9_-]{0,39}")))
        require(title.length <= 8192 && item.length <= 8192)
        require(source != null || (title.length <= 256 && item.length <= 256))
    }

    fun key(): String = MessageDigest.getInstance("SHA-256")
        .digest(syncJson.encodeToString(this).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

val syncJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = true
}

@Serializable
data class SyncVector(val counters: Map<String, Long> = emptyMap()) {
    operator fun get(device: String): Long = counters[device] ?: 0
    fun includes(dot: SyncDot): Boolean = get(dot.device) >= dot.sequence
    fun including(dot: SyncDot): SyncVector =
        copy(counters = counters + (dot.device to maxOf(get(dot.device), dot.sequence)))

    fun merged(other: SyncVector): SyncVector {
        val result = counters.toMutableMap()
        other.counters.forEach { (device, sequence) -> result[device] = maxOf(result[device] ?: 0, sequence) }
        return SyncVector(result)
    }

    fun validate() {
        require(counters.size <= 128)
        counters.forEach { (device, sequence) ->
            requireSyncId(device)
            require(sequence >= 0)
        }
    }
}

@Serializable
data class SyncDot(val device: String, val sequence: Long) {
    fun validate() {
        requireSyncId(device)
        require(sequence in 1 until Long.MAX_VALUE)
    }
}

@Serializable
data class SyncChange(
    val dot: SyncDot,
    val context: SyncVector,
    val ref: SyncRef,
    val field: String,
    val value: JsonElement?,
    /** Calibrated time is for presentation only, never a last-writer-wins clock. */
    val timeMillis: Long,
) {
    fun validate() {
        dot.validate()
        context.validate()
        ref.validate()
        require(field.length in 1..256 && field.none { it.isISOControl() })
        require(context[dot.device] == dot.sequence - 1)
        require(timeMillis >= 0)
        require(value?.toString()?.length ?: 0 <= 512 * 1024)
    }
}

@Serializable
data class SyncBatch(
    val version: Int = 1,
    val id: String,
    val space: String,
    val device: String,
    val changes: List<SyncChange>,
) {
    fun validate() {
        require(version == 1) { "Unsupported synchronization version" }
        requireSyncId(id)
        requireSyncId(space)
        requireSyncId(device)
        require(changes.size in 1..1024)
        changes.forEach {
            it.validate()
            require(it.dot.device == device)
        }
        require(changes.map { it.dot }.distinct().size == changes.size)
    }
}

internal fun requireSyncId(id: String) {
    require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false))
}
