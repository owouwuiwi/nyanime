package eu.kanade.tachiyomi.data.community

import fr.acinq.secp256k1.Secp256k1
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.bouncycastle.crypto.engines.ChaCha7539Engine
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal val communityJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
internal fun randomBytes(count: Int) = ByteArray(count).also(SecureRandom()::nextBytes)
internal fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
internal fun String.hexBytes(): ByteArray {
    require(length % 2 == 0 && all { it in "0123456789abcdef" })
    return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
internal fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
internal fun validKey(key: String) = key.length == 64 && key.all { it in "0123456789abcdef" }

/** A persistent social identity is never passed to the ephemeral watch-room transport. */
internal class CommunityIdentity(secret: ByteArray = newSecret()) : AutoCloseable {
    private val secret = secret.copyOf().also { require(Secp256k1.secKeyVerify(it)) }
    val publicKey = Secp256k1.pubkeyCreate(this.secret).copyOfRange(1, 33).hex()
    fun exportSecret() = secret.copyOf()
    fun sign(hash: ByteArray) = Secp256k1.signSchnorr(hash, secret, randomBytes(32)).hex()
    fun conversationKey(peer: String): ByteArray {
        require(validKey(peer))
        val point = Secp256k1.pubKeyTweakMul(byteArrayOf(2) + peer.hexBytes(), secret)
        return Nip44.hmac("nip44-v2".toByteArray(), point.copyOfRange(1, 33))
    }
    override fun close() = secret.fill(0)

    companion object {
        private fun newSecret(): ByteArray {
            while (true) randomBytes(32).let { if (Secp256k1.secKeyVerify(it)) return it }
        }
    }
}

@Serializable
internal data class NostrEvent(
    val id: String = "",
    val pubkey: String,
    val created_at: Long,
    val kind: Int,
    val tags: List<List<String>> = emptyList(),
    val content: String,
    val sig: String = "",
) {
    fun hash(): ByteArray = sha256(
        JsonArray(
            listOf(
                JsonPrimitive(0),
                JsonPrimitive(pubkey),
                JsonPrimitive(created_at),
                JsonPrimitive(kind),
                JsonArray(tags.map { JsonArray(it.map(::JsonPrimitive)) }),
                JsonPrimitive(content),
            ),
        ).toString().toByteArray(),
    )
    fun tag(name: String) = tags.firstOrNull { it.firstOrNull() == name }?.getOrNull(1)
    fun values(name: String) = tags.filter { it.firstOrNull() == name }.mapNotNull { it.getOrNull(1) }
    fun valid(now: Long = System.currentTimeMillis() / 1000): Boolean = runCatching {
        require(validKey(pubkey) && validKey(id) && sig.length == 128)
        require(created_at in 0..(now + 300) && kind in 0..65535)
        require(tags.size <= 128 && tags.all { it.size <= 8 && it.all { v -> v.length <= 8192 } })
        require(content.length <= 200_000)
        val hash = hash()
        hash.hex() == id && Secp256k1.verifySchnorr(sig.hexBytes(), hash, pubkey.hexBytes())
    }.getOrDefault(false)

    companion object {
        fun create(
            identity: CommunityIdentity,
            kind: Int,
            content: String,
            tags: List<List<String>> = emptyList(),
            at: Long = System.currentTimeMillis() /
                1000,
        ): NostrEvent {
            val event =
                NostrEvent(pubkey = identity.publicKey, created_at = at, kind = kind, tags = tags, content = content)
            val hash = event.hash()
            return event.copy(id = hash.hex(), sig = identity.sign(hash))
        }
    }
}

/** NIP-44 v2, bounded to 64 KiB per plaintext before allocating/decoding. */
internal object Nip44 {
    const val MAX_BYTES = 65535
    fun hmac(key: ByteArray, bytes: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(bytes)
    }
    private fun messageKeys(key: ByteArray, nonce: ByteArray): ByteArray {
        require(key.size == 32 && nonce.size == 32)
        var previous = ByteArray(0)
        var result = ByteArray(0)
        for (i in 1..3) {
            previous = hmac(key, previous + nonce + byteArrayOf(i.toByte()))
            result += previous
        }
        return result.copyOf(76)
    }
    internal fun paddedLength(size: Int): Int {
        require(size in 1..MAX_BYTES)
        if (size <= 32) return 32
        val nextPower = Integer.highestOneBit(size - 1) shl 1
        val chunk = if (nextPower <= 256) 32 else nextPower / 8
        return ((size - 1) / chunk + 1) * chunk
    }
    private fun crypt(keys: ByteArray, data: ByteArray): ByteArray = ByteArray(data.size).also { result ->
        ChaCha7539Engine().apply {
            init(true, ParametersWithIV(KeyParameter(keys.copyOfRange(0, 32)), keys.copyOfRange(32, 44)))
            processBytes(data, 0, data.size, result, 0)
        }
    }
    fun encrypt(key: ByteArray, text: String, nonce: ByteArray = randomBytes(32)): String {
        val bytes = text.toByteArray()
        val padded = ByteBuffer.allocate(2 + paddedLength(bytes.size)).putShort(bytes.size.toShort()).put(bytes).array()
        val keys = messageKeys(key, nonce)
        val encrypted = crypt(keys, padded)
        val mac = hmac(keys.copyOfRange(44, 76), nonce + encrypted)
        keys.fill(0)
        return Base64.getEncoder().encodeToString(byteArrayOf(2) + nonce + encrypted + mac)
    }
    fun decrypt(key: ByteArray, content: String): String {
        require(content.length in 132..90_000)
        val bytes = Base64.getDecoder().decode(content)
        require(bytes.size in 99..65_603 && bytes[0] == 2.toByte())
        val nonce = bytes.copyOfRange(1, 33)
        val ciphertext = bytes.copyOfRange(33, bytes.size - 32)
        val keys = messageKeys(key, nonce)
        require(
            MessageDigest.isEqual(hmac(keys.copyOfRange(44, 76), nonce + ciphertext), bytes.takeLast(32).toByteArray()),
        )
        val padded = crypt(keys, ciphertext)
        keys.fill(0)
        val length = ByteBuffer.wrap(padded).short.toInt() and 65535
        require(length > 0 && padded.size == paddedLength(length) + 2)
        return padded.copyOfRange(2, 2 + length).decodeToString(throwOnInvalidSequence = true)
    }
}

internal object GiftWrap {
    /** Rumors are unsigned. Only their seal is signed by the real author. */
    fun wrap(author: CommunityIdentity, recipient: String, rumor: NostrEvent, expires: Long = 0): NostrEvent {
        require(rumor.pubkey == author.publicKey && rumor.sig.isEmpty() && rumor.id == rumor.hash().hex())
        val at = System.currentTimeMillis() / 1000 - SecureRandom().nextInt(172800)
        val seal = NostrEvent.create(
            author,
            13,
            Nip44.encrypt(author.conversationKey(recipient), communityJson.encodeToString(rumor)),
            at = at,
        )
        return CommunityIdentity().use { temporary ->
            NostrEvent.create(
                temporary,
                1059,
                Nip44.encrypt(temporary.conversationKey(recipient), communityJson.encodeToString(seal)),
                buildList {
                    add(listOf("p", recipient))
                    if (expires >
                        0
                    ) {
                        add(listOf("expiration", (expires / 1000).toString()))
                    }
                },
                at,
            )
        }
    }
    fun rumor(
        author: String,
        kind: Int,
        content: String,
        tags: List<List<String>> = emptyList(),
        at: Long = System.currentTimeMillis() /
            1000,
    ): NostrEvent {
        val event = NostrEvent(pubkey = author, created_at = at, kind = kind, content = content, tags = tags)
        return event.copy(id = event.hash().hex())
    }
    fun open(identity: CommunityIdentity, event: NostrEvent): NostrEvent {
        require(event.kind == 1059 && event.values("p") == listOf(identity.publicKey) && event.valid())
        val seal = communityJson.decodeFromString<NostrEvent>(
            Nip44.decrypt(identity.conversationKey(event.pubkey), event.content),
        )
        require(seal.kind == 13 && seal.tags.isEmpty() && seal.valid())
        val rumor = communityJson.decodeFromString<NostrEvent>(
            Nip44.decrypt(identity.conversationKey(seal.pubkey), seal.content),
        )
        require(rumor.sig.isEmpty() && rumor.pubkey == seal.pubkey && rumor.id == rumor.hash().hex())
        require(rumor.created_at <= System.currentTimeMillis() / 1000 + 300)
        return rumor
    }
}

/** Stable, checksummed public contact code. Secret material has a separate codec and route. */
internal object ProfileCode {
    fun encode(key: String): String {
        require(validKey(key))
        val bytes = key.hexBytes()
        return "NYU1." + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes + sha256(bytes).copyOf(4))
    }
    fun decode(text: String): String {
        require(text.length <= 2200) { "Codice profilo non valido" }
        val value = text.trim().removePrefix("nyanime://profile/").substringBefore('?')
        if (validKey(value)) return value
        require(value.startsWith("NYU1.") && value.length <= 60) { "Codice profilo non valido" }
        val bytes = Base64.getUrlDecoder().decode(value.removePrefix("NYU1."))
        require(
            bytes.size == 36 && MessageDigest.isEqual(bytes.copyOfRange(32, 36), sha256(bytes.copyOf(32)).copyOf(4)),
        ) {
            "Codice profilo non valido"
        }
        return bytes.copyOf(32).hex()
    }
    fun link(key: String, relays: List<String>): String =
        "nyanime://profile/" +
            encode(key) +
            relays.filter(CommunityRelays::validRelay).distinct().take(5)
                .joinToString("&", prefix = if (relays.isEmpty()) "" else "?") {
                    "relay=" + java.net.URLEncoder.encode(it, "UTF-8")
                }

    fun relayHints(text: String): List<String> {
        if (!text.startsWith("nyanime://profile/") || text.length > 2200) return emptyList()
        return runCatching {
            java.net.URI(text).rawQuery.orEmpty().split('&').filter { it.startsWith("relay=") }
                .map { java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }
                .filter(CommunityRelays::validRelay).distinct().take(5)
        }.getOrDefault(emptyList())
    }
}
