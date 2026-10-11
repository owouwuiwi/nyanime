package eu.kanade.tachiyomi.data.watch

import kotlinx.serialization.encodeToString
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Pairwise ECDH, RFC 5869 HKDF-SHA256 and AES-GCM. The outer signed room event binds
 * the sender; this inner envelope prevents an expelled holder of the old room invitation
 * from reading messages addressed to remaining participants. No public room-wide chat key.
 */
class WatchInteractionCrypto(private val invite: WatchInvite, private val identity: WatchIdentity) {
    val publicKey: String get() = identity.publicKey

    fun seal(peer: String, membership: String, value: WatchInteractionPayload): WatchInteractionSealed {
        require(value.valid())
        val material = key(peer, membership)
        return try {
            val nonce = watchRandom(12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(material, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad(publicKey, peer, membership))
            WatchInteractionSealed(
                membership,
                watchBase64(nonce + cipher.doFinal(watchJson.encodeToString(value).toByteArray())),
            ).also { require(it.valid()) }
        } finally {
            material.fill(0)
        }
    }

    fun open(peer: String, membership: String, value: WatchInteractionSealed): WatchInteractionPayload? = runCatching {
        require(value.valid() && value.membership == membership)
        val material = key(peer, membership)
        try {
            val bytes = Base64.getUrlDecoder().decode(value.content)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(material, "AES"), GCMParameterSpec(128, bytes.copyOf(12)))
            cipher.updateAAD(aad(peer, publicKey, membership))
            watchJson.decodeFromString<WatchInteractionPayload>(
                cipher.doFinal(bytes.copyOfRange(12, bytes.size)).decodeToString(),
            ).also { require(it.valid()) }
        } finally {
            material.fill(0)
        }
    }.getOrNull()

    private fun aad(sender: String, recipient: String, membership: String): ByteArray =
        "nyanime-room-interactions-v1:${invite.topic}:$membership:$sender:$recipient".toByteArray()

    private fun key(peer: String, membership: String): ByteArray {
        require(peer.matches(Regex("[0-9a-f]{64}")) && peer != publicKey)
        require(membership.matches(Regex("[0-9a-f]{64}")))
        val shared = identity.interactionSharedSecret(peer)
        return try {
            val salt = watchHash((invite.topic + ":" + membership).toByteArray())
            val info = ("nyanime-room-interactions-v1:" + listOf(publicKey, peer).sorted().joinToString(":"))
                .toByteArray()
            hkdf(shared, salt, info)
        } finally {
            shared.fill(0)
        }
    }

    companion object {
        /** Single-block RFC 5869 output: a 256-bit AES key. */
        internal fun hkdf(input: ByteArray, salt: ByteArray, info: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(salt, "HmacSHA256"))
            val extracted = mac.doFinal(input)
            return try {
                mac.init(SecretKeySpec(extracted, "HmacSHA256"))
                mac.doFinal(info + byteArrayOf(1))
            } finally {
                extracted.fill(0)
            }
        }
    }
}
