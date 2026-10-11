package eu.kanade.tachiyomi.data.community

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** Encrypted, content-addressed index. A relay's history-page limit cannot truncate library recovery. */
@Serializable
internal data class SyncCheckpoint(
    val version: Int = 1,
    val leaf: Boolean,
    val entries: List<String>,
) {
    fun valid() = version == 1 &&
        entries.size in 1..200 &&
        entries.distinct().size == entries.size &&
        entries.all { if (leaf) RECORD.matches(it) else NODE.matches(it) }

    fun address(key: ByteArray): String =
        "nyanime.sync.index.v1:" + Nip44.hmac(key, communityJson.encodeToString(this).toByteArray()).hex()

    companion object {
        private val RECORD = Regex("nyanime\\.sync\\.v1:[0-9a-f]{32}:[0-9a-f]{64}")
        private val NODE = Regex("nyanime\\.sync\\.index\\.v1:[0-9a-f]{64}")

        fun build(addresses: List<String>, key: ByteArray, persist: (String, SyncCheckpoint) -> Unit): SyncCheckpoint? {
            if (addresses.isEmpty()) return null
            var nodes = addresses.distinct().chunked(200).map { SyncCheckpoint(leaf = true, entries = it) }
            while (nodes.size > 1) {
                val references = nodes.map { node ->
                    require(node.valid())
                    node.address(key).also { persist(it, node) }
                }
                nodes = references.chunked(200).map { SyncCheckpoint(leaf = false, entries = it) }
            }
            return nodes.single().also { require(it.valid()) }
        }
    }
}
