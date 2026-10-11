package it.lorelore.nyanime.backup.remote

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nyanime.telegram.sync.SyncBackupCipher
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal val remoteBackupJson = Json { ignoreUnknownKeys = true }
internal const val MAX_REMOTE_BYTES = SyncBackupCipher.MAX_BYTES + 1024 * 1024

/** A commit is visible only after the encrypted payload has been downloaded and verified. */
@Serializable
internal data class RemoteBackupCommit(
    val version: Int = 1,
    val id: String,
    val device: String,
    val label: String,
    val created: Long,
    val bytes: Long,
    val sha256: String,
    val payload: String,
    val authentication: String = "",
) {
    fun validate() {
        require(version == 1 && UUID.fromString(id).toString() == id)
        require(UUID.fromString(device).toString() == device)
        require(label.length in 1..100 && label.none(Char::isISOControl))
        require(created > 0 && bytes in 1..MAX_REMOTE_BYTES)
        require(sha256.matches(Regex("[0-9a-f]{64}")))
        require(payload.length in 1..240 && payload.none(Char::isISOControl))
    }

    fun signed(key: ByteArray): RemoteBackupCommit = copy(authentication = mac(key))

    fun verify(key: ByteArray): Boolean = runCatching {
        validate()
        MessageDigest.isEqual(authentication.toByteArray(), mac(key).toByteArray())
    }.getOrDefault(false)

    private fun mac(key: ByteArray): String {
        require(key.size == 32)
        val canonical = remoteBackupJson.encodeToString(copy(authentication = "")).toByteArray()
        return Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal("nyanime-remote-commit-v1\n".toByteArray() + canonical).hex()
        }
    }
}

internal object RemoteRecoveryKey {
    fun create(): String = encode(ByteArray(32).also(SecureRandom()::nextBytes))
    fun encode(key: ByteArray): String = "NYK1-" + Base64.getUrlEncoder().withoutPadding().encodeToString(key)
    fun decode(value: String): ByteArray {
        val compact = value.trim()
        require(compact.startsWith("NYK1-") && compact.length == 48)
        return Base64.getUrlDecoder().decode(compact.removePrefix("NYK1-")).also { require(it.size == 32) }
    }
}

internal suspend fun File.remoteSha256(): String = inputStream().use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    digest.digest().hex()
}

internal fun boundedRemoteJson(text: String): kotlinx.serialization.json.JsonElement {
    require(text.length <= 512 * 1024)
    var depth = 0
    var quoted = false
    var escape = false
    text.forEach { character ->
        if (quoted) {
            if (escape) {
                escape = false
            } else if (character == '\\') {
                escape = true
            } else if (character == '"') {
                quoted = false
            }
        } else {
            when (character) {
                '"' -> quoted = true
                '{', '[' -> {
                    depth++
                    require(depth <= 12)
                }
                '}', ']' -> {
                    depth--
                    require(depth >= 0)
                }
            }
        }
    }
    return remoteBackupJson.parseToJsonElement(text)
}

private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
