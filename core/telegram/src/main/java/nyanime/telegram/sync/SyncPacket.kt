package nyanime.telegram.sync

import kotlinx.serialization.Serializable

/** Only protocol metadata is visible; application data is always inside an encrypted envelope. */
@Serializable
data class SyncPacket(val version: Int = 1, val kind: String, val id: String, val space: String, val payload: String) {
    fun validate() {
        require(version == 1)
        require(
            kind in setOf(
                "root",
                "control",
                "data",
                "checkpoint",
                "checkpoint-pack",
                "commit",
                "capability",
                "pairing",
                "recovery",
            ),
        )
        requireSyncId(id)
        requireSyncId(space)
        require(payload.length in 1..SyncEnvelope.MAX_ENCODED_SIZE)
    }

    companion object {
        fun data(envelope: SyncEnvelope) = SyncPacket(
            kind = "data",
            id = envelope.id,
            space = envelope.space,
            payload = syncJson.encodeToString(envelope),
        )
    }
}
