package nyanime.telegram.sync

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class SyncArchiveRoot(
    val version: Int = 1,
    val id: String,
    val space: String,
    val founder: SyncDevicePublic,
    val recovery: SyncDevicePublic,
    val keys: Map<String, String>,
    val recoveryIdentity: SyncEnvelope,
)

@Serializable
data class SyncSignedRoot(val payload: String, val signature: String)

/** The recovery private identity is stored ONLY encrypted under the printed recovery code. */
object SyncArchiveBootstrap {
    fun create(account: Long, space: String, founder: SyncDeviceSecret, recoveryCode: String): SyncSignedRoot {
        requireSyncId(space)
        val recovery = SyncDeviceCrypto.generate(UUID.randomUUID().toString())
        val dataKey = SyncCipher.newKey()
        val id = UUID.randomUUID().toString()
        val root = SyncArchiveRoot(
            id = id,
            space = space,
            founder = founder.public,
            recovery = recovery.public,
            keys = listOf(founder.public, recovery.public).associate { recipient ->
                recipient.id to SyncDeviceCrypto.seal(
                    recipient,
                    dataKey,
                    binding(account, space, id, recipient.id),
                )
            },
            recoveryIdentity = SyncCipher.encrypt(
                account,
                space,
                1,
                id,
                SyncRecoveryCode.parse(recoveryCode),
                SyncDeviceCrypto.exportForLocalWrapping(recovery),
            ),
        )
        dataKey.fill(0)
        val payload = syncJson.encodeToString(root)
        return SyncSignedRoot(payload, SyncDeviceCrypto.sign(founder, signatureBinding(account, payload)))
    }

    fun verify(signed: SyncSignedRoot, account: Long): SyncArchiveRoot {
        require(account > 0 && signed.payload.length <= 64 * 1024)
        val root = syncJson.decodeFromString<SyncArchiveRoot>(signed.payload)
        require(root.version == 1)
        requireSyncId(root.id)
        requireSyncId(root.space)
        root.founder.validate()
        root.recovery.validate()
        require(root.founder.id != root.recovery.id)
        require(root.keys.keys == setOf(root.founder.id, root.recovery.id))
        root.recoveryIdentity.validate()
        require(root.recoveryIdentity.space == root.space && root.recoveryIdentity.id == root.id)
        require(SyncDeviceCrypto.verify(root.founder, signatureBinding(account, signed.payload), signed.signature))
        return root
    }

    fun recover(signed: SyncSignedRoot, account: Long, code: String): SyncDeviceSecret {
        val root = verify(signed, account)
        val codeKey = SyncRecoveryCode.parse(code)
        val clear = try {
            SyncCipher.decrypt(account, root.space, codeKey, root.recoveryIdentity)
        } finally {
            codeKey.fill(0)
        }
        return try {
            SyncDeviceCrypto.importAfterLocalUnwrapping(clear).also { require(it.public == root.recovery) }
        } finally {
            clear.fill(0)
        }
    }

    fun openKey(root: SyncArchiveRoot, account: Long, device: SyncDeviceSecret): ByteArray =
        SyncDeviceCrypto.open(
            device,
            requireNotNull(root.keys[device.public.id]),
            binding(account, root.space, root.id, device.public.id),
        ).also { require(it.size == 32) }

    fun initial(root: SyncArchiveRoot, messageId: Long): SyncMembershipState {
        require(messageId > 0)
        return SyncMembershipState(
            root.space,
            1,
            root.id,
            messageId,
            mapOf(root.founder.id to root.founder),
            root.recovery,
        )
    }

    private fun binding(account: Long, space: String, id: String, recipient: String) =
        "nyanime-sync-root-key|1|$account|$space|$id|$recipient".toByteArray(Charsets.UTF_8)

    private fun signatureBinding(account: Long, payload: String) =
        "nyanime-sync-root|1|$account|$payload".toByteArray(Charsets.UTF_8)
}
