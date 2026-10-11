package eu.kanade.tachiyomi.data.cloud.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Independent of the Telegram session. Nothing in this vault participates in ordinary backups. */
internal class CloudSyncVault(context: Context, private val account: Long) {
    private val directory = File(context.noBackupFilesDir, "cloud-sync-keys/$account")
    private val alias = "nyanime.cloud.sync.$account.v1"

    init {
        require(account > 0)
    }

    @Synchronized
    fun read(slot: String): ByteArray? {
        val file = file(slot)
        if (!file.exists() && !File(file.path + ".bak").exists()) return null
        val packed = AtomicFile(file).openRead().use { input ->
            require(file.length() in 28..MAX_BYTES)
            input.readBytes()
        }
        require(packed.size.toLong() in 28..MAX_BYTES)
        val key = key(create = false)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, packed.copyOfRange(0, 12)))
            updateAAD(binding(slot))
            doFinal(packed.copyOfRange(12, packed.size))
        }
    }

    @Synchronized
    fun write(slot: String, secret: ByteArray) {
        require(secret.size <= MAX_BYTES - 28)
        check(directory.exists() || directory.mkdirs())
        val key = key(create = directory.listFiles().orEmpty().none { it.isFile })
        val packed = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, key)
            updateAAD(binding(slot))
            iv + doFinal(secret)
        }
        val atomic = AtomicFile(file(slot))
        val output = atomic.startWrite()
        try {
            output.write(packed)
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
    }

    @Synchronized
    fun remove(slot: String) {
        AtomicFile(file(slot)).delete()
    }

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create) { "Cloud key unavailable; device approval or recovery is required" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private fun file(slot: String): File {
        require(
            slot in setOf("device", "pairing", "setup") ||
                runCatching { UUID.fromString(slot).toString() == slot }.getOrDefault(false),
        )
        return File(directory, "$slot.wrapped")
    }

    private fun binding(slot: String) = "nyanime-cloud-vault|1|$account|$slot".toByteArray(Charsets.UTF_8)

    companion object {
        private const val MAX_BYTES = 1024L * 1024
    }
}
