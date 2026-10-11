package nyanime.telegram.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

/**
 * Causal multi-value register. Delivery order, clock skew and duplicate delivery do not affect
 * convergence. Concurrent alternatives remain available to the UI instead of being discarded.
 */
@Serializable
data class SyncRegister(val versions: List<SyncChange> = emptyList()) {
    fun merge(incoming: SyncChange): SyncRegister {
        incoming.validate()
        versions.firstOrNull { it.dot == incoming.dot }?.let {
            require(it == incoming) { "Device revision reused for different data" }
            return this
        }
        if (versions.any { it.context.includes(incoming.dot) }) return this
        val retained = versions.filterNot { incoming.context.includes(it.dot) } + incoming
        return SyncRegister(retained.sortedWith(order))
    }

    /** Deletion wins a concurrent explicit addition; a later observed re-add is still allowed. */
    fun resolved(): SyncChange? = versions
        .filter {
            (
                (it.field == EXISTS || it.field == "favorite" || it.field.startsWith("category/")) &&
                    it.value == JsonPrimitive(false)
                ) ||
                (
                    it.field.startsWith("member/") &&
                        (it.value == null || it.value == kotlinx.serialization.json.JsonNull)
                    )
        }
        .maxWithOrNull(order) ?: versions.maxWithOrNull(order)

    companion object {
        const val EXISTS = "_exists"
        private val order = compareBy<SyncChange> { it.dot.sequence }.thenBy { it.dot.device }
    }
}

@Serializable
data class SyncRecord(
    val ref: SyncRef,
    val fields: Map<String, SyncRegister> = emptyMap(),
) {
    val deleted: Boolean
        get() = fields[SyncRegister.EXISTS]?.resolved()?.value == JsonPrimitive(false)

    fun merge(change: SyncChange): SyncRecord {
        require(change.ref == ref)
        return copy(fields = fields + (change.field to (fields[change.field] ?: SyncRegister()).merge(change)))
    }

    /** Tombstones are retained in checkpoints. Ordinary field edits cannot resurrect a record. */
    fun values() = if (deleted) emptyMap() else fields.mapValues { it.value.resolved()?.value }
}

/**
 * A session owns its own position. Rewinds and rereads are edits, not monotone counters.
 * Keeping this registry separate also prevents network work from touching the native player.
 */
class SyncActiveContent {
    private val active = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    fun enter(ref: SyncRef) {
        active.add(ref.key())
    }
    fun leave(ref: SyncRef) {
        active.remove(ref.key())
    }
    fun contains(ref: SyncRef): Boolean = ref.key() in active
}
