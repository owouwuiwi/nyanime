package nyanime.telegram.sync

import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The visible Telegram payload contains no title, source, progress or preference values. */
@Serializable
data class SyncEnvelope(
    val version: Int = 1,
    val space: String,
    val epoch: Long,
    val id: String,
    val nonce: String,
    val ciphertext: String,
) {
    fun validate() {
        require(version == 1 && epoch > 0)
        requireSyncId(space)
        requireSyncId(id)
        require(nonce.length == 16)
        require(ciphertext.length in 24..MAX_ENCODED_SIZE)
    }

    companion object {
        const val MARKER = "NYANIME_SYNC_V1:"
        const val ARCHIVE_MARKER = "Nyanime private synchronization archive / v1"
        const val MAX_CLEAR_SIZE = 8 * 1024 * 1024
        const val MAX_ENCODED_SIZE = 12 * 1024 * 1024
    }
}

/** AES-256-GCM with random 96-bit nonces; account and envelope identity are authenticated. */
object SyncCipher {
    private val random = SecureRandom()
    fun newKey(): ByteArray = ByteArray(32).also(random::nextBytes)

    fun encrypt(
        account: Long,
        space: String,
        epoch: Long,
        id: String,
        key: ByteArray,
        clear: ByteArray,
    ): SyncEnvelope {
        require(key.size == 32 && clear.size <= SyncEnvelope.MAX_CLEAR_SIZE)
        val nonce = ByteArray(12).also(random::nextBytes)
        val result = SyncEnvelope(space = space, epoch = epoch, id = id, nonce = encode(nonce), ciphertext = "")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(binding(account, result))
        val compressed = ByteArrayOutputStream().use { output ->
            GZIPOutputStream(output).use { it.write(clear) }
            output.toByteArray()
        }
        return result.copy(ciphertext = encode(cipher.doFinal(compressed))).also(SyncEnvelope::validate)
    }

    fun decrypt(account: Long, expectedSpace: String, key: ByteArray, envelope: SyncEnvelope): ByteArray {
        envelope.validate()
        require(key.size == 32 && envelope.space == expectedSpace)
        val nonce = decode(envelope.nonce)
        require(nonce.size == 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(binding(account, envelope))
        val compressed = cipher.doFinal(decode(envelope.ciphertext))
        return GZIPInputStream(compressed.inputStream()).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= SyncEnvelope.MAX_CLEAR_SIZE) { "Sync document too large" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    private fun binding(account: Long, envelope: SyncEnvelope): ByteArray {
        require(account > 0)
        requireSyncId(envelope.space)
        requireSyncId(envelope.id)
        require(envelope.version == 1 && envelope.epoch > 0)
        return "nyanime-sync|1|$account|${envelope.space}|${envelope.epoch}|${envelope.id}"
            .toByteArray(Charsets.UTF_8)
    }

    fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    fun decode(text: String): ByteArray = Base64.getUrlDecoder().decode(text)
}

/** High-entropy recovery material, not a user password or a code derived from a phone number. */
object SyncRecoveryCode {
    fun create(): String = format(SyncCipher.newKey())

    fun format(key: ByteArray): String {
        require(key.size == 32)
        val checksum = MessageDigest.getInstance("SHA-256").digest(key).copyOf(4)
        return "NS1-" + (key + checksum).joinToString("") { "%02X".format(it) }.chunked(6).joinToString("-")
    }

    fun parse(value: String): ByteArray {
        val compact = value.trim().uppercase(java.util.Locale.ROOT).replace(Regex("[\\s-]"), "")
        require(compact.startsWith("NS1"))
        val hex = compact.removePrefix("NS1")
        require(hex.length == 72 && hex.all { it in '0'..'9' || it in 'A'..'F' })
        val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val key = bytes.copyOf(32)
        require(
            MessageDigest.isEqual(
                MessageDigest.getInstance("SHA-256").digest(key).copyOf(4),
                bytes.copyOfRange(32, 36),
            ),
        ) { "Invalid recovery code" }
        return key
    }
}
