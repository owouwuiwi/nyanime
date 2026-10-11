package eu.kanade.tachiyomi.data.watch

import eu.kanade.tachiyomi.R
import fr.acinq.secp256k1.Secp256k1
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal val watchJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
internal fun ByteArray.watchHex(): String = joinToString("") { "%02x".format(it) }
internal fun String.watchBytes(): ByteArray {
    require(length % 2 == 0 && matches(Regex("[0-9a-f]+")))
    return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
internal fun watchHash(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
internal fun watchRandom(size: Int): ByteArray = ByteArray(size).also { SecureRandom().nextBytes(it) }
internal fun watchBase64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

@Serializable
data class WatchInvite(
    val version: Int = 1,
    val secret: String,
    val owner: String,
    val expires: Long,
    val relays: List<String>,
) {
    val topic: String get() = watchHash(("nyanime-watch-topic-v1:" + secret).toByteArray()).watchHex()
    fun owns(publicKey: String): Boolean = publicKey.length == 64 &&
        watchHash(publicKey.watchBytes()).copyOf(12).watchHex() == owner

    fun encode(): String = if (relays == defaultRelays) {
        "NY1." + watchBase64(secret.watchBytes() + owner.watchBytes())
    } else {
        "nyanime://watch/v1#" + watchBase64(watchJson.encodeToString(this).toByteArray())
    }

    fun link(): String = encode().let { if (it.startsWith("NY1.")) "nyanime://watch/v1#" + it else it }

    fun validate(now: Long): WatchInvite {
        roomRequire(version == 1, R.string.room_invite_version_error)
        roomRequire(
            secret.matches(Regex("[0-9a-f]{32}")) && owner.matches(Regex("[0-9a-f]{24}")),
            R.string.room_invite_invalid,
        )
        roomRequire(expires > now && expires - now <= 86_400_000L, R.string.room_invite_expired)
        roomRequire(relays.size in 1..3 && relays.distinct().size == relays.size, R.string.room_relays_invalid)
        relays.forEach {
            val uri = URI(it)
            roomRequire(
                it.length <= 200 &&
                    uri.scheme == "wss" &&
                    !uri.host.isNullOrBlank() &&
                    uri.rawUserInfo == null &&
                    uri.rawQuery == null &&
                    uri.rawFragment == null &&
                    (uri.port == -1 || uri.port == 443),
                R.string.room_relay_secure,
            )
        }
        return this
    }

    companion object {
        val defaultRelays = listOf("wss://relay.primal.net", "wss://relay.snort.social")
        fun codeFromLink(text: String, now: Long): String {
            roomRequire(text.length <= 5000, R.string.room_invite_too_long)
            val uri = URI(text)
            roomRequire(
                uri.scheme == "nyanime" &&
                    uri.host == "watch" &&
                    uri.path == "/v1" &&
                    uri.rawUserInfo == null &&
                    uri.port == -1 &&
                    uri.rawQuery == null &&
                    !uri.rawFragment.isNullOrBlank(),
                R.string.room_invite_link_invalid,
            )
            val shortCode = uri.rawFragment.orEmpty()
            return if (shortCode.matches(Regex("[0-9]{8}"))) shortCode else parse(text, now).encode()
        }
        fun create(owner: String, now: Long, relays: List<String> = defaultRelays): WatchInvite =
            WatchInvite(
                secret = watchRandom(16).watchHex(),
                owner = watchHash(owner.watchBytes()).copyOf(12).watchHex(),
                expires = now + 86_400_000L,
                relays = relays,
            )
                .validate(now)

        fun parse(text: String, now: Long): WatchInvite {
            roomRequire(text.length <= 5000, R.string.room_invite_too_long)
            Regex("NY1\\.([A-Za-z0-9_-]{38})(?![A-Za-z0-9_-])").find(text)?.let {
                val data = Base64.getUrlDecoder().decode(it.groupValues[1])
                roomRequire(data.size == 28 && watchBase64(data) == it.groupValues[1], R.string.room_code_invalid)
                return WatchInvite(
                    secret = data.copyOfRange(0, 16).watchHex(),
                    owner = data.copyOfRange(16, 28).watchHex(),
                    expires = now + 86_400_000L,
                    relays = defaultRelays,
                ).validate(now)
            }
            val encoded = Regex("nyanime://watch/v1#([A-Za-z0-9_-]+)").find(text.trim())?.groupValues?.get(1)
                ?: throw RoomValidationException(R.string.room_paste_invite)
            return watchJson.decodeFromString<WatchInvite>(
                Base64.getUrlDecoder().decode(encoded).decodeToString(),
            ).validate(now)
        }
    }
}

/** A fresh ephemeral identity per room. Bitcoin Core's implementation handles Schnorr; no custom signing primitives. */
class WatchIdentity(private val secret: ByteArray = generateSecret()) {
    val publicKey: String = Secp256k1.pubkeyCreate(secret).copyOfRange(1, 33).watchHex()
    fun sign(hash: ByteArray): String = Secp256k1.signSchnorr(hash, secret, watchRandom(32)).watchHex()
    internal fun sharedKey(peer: String, code: String): ByteArray {
        require(peer.matches(Regex("[0-9a-f]{64}")) && code.matches(Regex("[0-9]{8}")))
        val point = Secp256k1.pubKeyTweakMul(byteArrayOf(2) + peer.watchBytes(), secret)
        return watchHash("nyanime-short-ecdh-v1:".toByteArray() + point.copyOfRange(1, 33) + code.toByteArray())
    }
    fun clear() = secret.fill(0)
    internal fun sessionSecret(): ByteArray = secret.copyOf()

    companion object {
        private fun generateSecret(): ByteArray {
            while (true) {
                val key = watchRandom(32)
                if (Secp256k1.secKeyVerify(key)) return key
            }
        }
    }
}

@Serializable
internal data class WatchEvent(
    val id: String,
    val pubkey: String,
    val created_at: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String,
)

/** NIP-01 ephemeral events carry an application-specific AES-256-GCM envelope, not NIP-44 direct messages. */
class WatchCrypto(private val invite: WatchInvite, private val identity: WatchIdentity) {
    private val key = SecretKeySpec(watchHash(("nyanime-watch-key-v1:" + invite.secret).toByteArray()), "AES")
    private val topic = invite.topic

    internal fun seal(message: WatchMessage, now: Long): WatchEvent {
        require(message.valid())
        val at = now / 1000
        val tags = listOf(listOf("x", topic), listOf("expiration", (at + 60).toString()))
        val nonce = watchRandom(12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad(identity.publicKey, at))
        val content = watchBase64(nonce + cipher.doFinal(watchJson.encodeToString(message).toByteArray()))
        val id = eventHash(identity.publicKey, at, tags, content)
        return WatchEvent(id.watchHex(), identity.publicKey, at, KIND, tags, content, identity.sign(id))
    }

    internal fun open(event: WatchEvent, now: Long): WatchMessage? = runCatching {
        require(
            event.kind == KIND &&
                event.tags.size <= 8 &&
                event.tags.all { it.size <= 4 && it.all { v -> v.length <= 128 } },
        )
        require(event.tags.count { it == listOf("x", topic) } == 1)
        require(event.created_at in (now / 1000 - 60)..(now / 1000 + 30))
        require(
            event.content.length in 38..32_000 &&
                event.id.length == 64 &&
                event.pubkey.length == 64 &&
                event.sig.length == 128,
        )
        val hash = eventHash(event.pubkey, event.created_at, event.tags, event.content)
        require(hash.watchHex() == event.id)
        require(Secp256k1.verifySchnorr(event.sig.watchBytes(), hash, event.pubkey.watchBytes()))
        val encrypted = Base64.getUrlDecoder().decode(event.content)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, encrypted.copyOfRange(0, 12)))
        cipher.updateAAD(aad(event.pubkey, event.created_at))
        watchJson.decodeFromString<WatchMessage>(
            cipher.doFinal(encrypted.copyOfRange(12, encrypted.size)).decodeToString(),
        )
            .also { require(it.valid()) }
    }.getOrNull()

    private fun aad(publicKey: String, at: Long): ByteArray =
        ("nyanime-watch-v1:" + topic + ":" + publicKey + ":" + at).toByteArray()

    private fun eventHash(publicKey: String, at: Long, tags: List<List<String>>, content: String): ByteArray =
        watchHash(
            JsonArray(
                listOf(
                    JsonPrimitive(0),
                    JsonPrimitive(publicKey),
                    JsonPrimitive(at),
                    JsonPrimitive(KIND),
                    JsonArray(tags.map { tag -> JsonArray(tag.map(::JsonPrimitive)) }),
                    JsonPrimitive(content),
                ),
            ).toString().toByteArray(),
        )

    companion object {
        internal const val KIND = 20971
    }
}
