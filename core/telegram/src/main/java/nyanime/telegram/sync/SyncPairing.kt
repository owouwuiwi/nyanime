package nyanime.telegram.sync

import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Displayed ONLY in the expiring device-association QR. Its random secret is never sent over
 * Telegram. HPKE alone does not authenticate the approving phone; the QR secret binds the grant
 * to the physical approval, and to the new phone's exact public keys.
 */
@Serializable
data class SyncPairingRequest(
    val version: Int = 1,
    val id: String,
    val account: Long,
    val recipient: SyncDevicePublic,
    val secret: String,
    val expiresAt: Long,
) {
    fun validate(now: Long, account: Long) {
        require(version == 1 && this.account == account && account > 0)
        requireSyncId(id)
        recipient.validate()
        require(SyncCipher.decode(secret).size == 32)
        require(expiresAt > now && expiresAt <= now + 5 * 60_000)
    }

    override fun toString(): String = "SyncPairingRequest(redacted)"
}

@Serializable
data class SyncPairingGrant(
    val version: Int = 1,
    val request: String,
    val account: Long,
    val recipient: String,
    val expiresAt: Long,
    val ciphertext: String,
    val proof: String,
)

object SyncPairing {
    fun request(account: Long, recipient: SyncDevicePublic, now: Long): SyncPairingRequest =
        SyncPairingRequest(
            id = UUID.randomUUID().toString(),
            account = account,
            recipient = recipient,
            secret = SyncCipher.encode(SyncCipher.newKey()),
            expiresAt = now + 5 * 60_000,
        ).also { it.validate(now, account) }

    /** Called only after explicit confirmation on the already authorized device. */
    fun approve(request: SyncPairingRequest, account: Long, now: Long, bundle: ByteArray): SyncPairingGrant {
        request.validate(now, account)
        val context = binding(request)
        val cipher = SyncDeviceCrypto.seal(request.recipient, bundle, context)
        return SyncPairingGrant(
            request = request.id,
            account = account,
            recipient = request.recipient.id,
            expiresAt = request.expiresAt,
            ciphertext = cipher,
            proof = mac(request.secret, context + cipher.toByteArray(Charsets.UTF_8)),
        )
    }

    /**
     * Persist consumption of request.id in the same transaction that stores the wrapped bundle.
     * The caller must reject already consumed requests even after a process restart.
     */
    fun accept(
        request: SyncPairingRequest,
        grant: SyncPairingGrant,
        device: SyncDeviceSecret,
        account: Long,
        now: Long,
    ): ByteArray {
        request.validate(now, account)
        require(grant.version == 1 && grant.account == account && grant.request == request.id)
        require(grant.recipient == device.public.id && device.public == request.recipient)
        require(grant.expiresAt == request.expiresAt && grant.ciphertext.length <= 2 * 1024 * 1024)
        val context = binding(request)
        val expected = mac(request.secret, context + grant.ciphertext.toByteArray(Charsets.UTF_8))
        require(MessageDigest.isEqual(SyncCipher.decode(expected), SyncCipher.decode(grant.proof)))
        return SyncDeviceCrypto.open(device, grant.ciphertext, context)
    }

    private fun binding(request: SyncPairingRequest): ByteArray =
        syncJson.encodeToString(request.copy(secret = "")).toByteArray(Charsets.UTF_8)

    private fun mac(key: String, data: ByteArray): String = SyncCipher.encode(
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(SyncCipher.decode(key), "HmacSHA256"))
            doFinal(data)
        },
    )
}
