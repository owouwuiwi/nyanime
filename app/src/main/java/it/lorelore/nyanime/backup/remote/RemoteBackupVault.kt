package it.lorelore.nyanime.backup.remote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.serialization.Serializable
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal enum class RemoteBackupProvider { WebDAV, Drive }

@Serializable
internal data class RemoteBackupConfiguration(
    val provider: RemoteBackupProvider,
    val endpoint: String = "",
    val username: String = "",
    val password: String = "",
    val recoveryKey: String,
    val account: String = "",
    val intervalHours: Int = 0,
    val wifiOnly: Boolean = true,
    val retention: Int = 7,
    val generation: String = UUID.randomUUID().toString(),
) {
    fun validate() {
        require(UUID.fromString(generation).toString() == generation)
        RemoteRecoveryKey.decode(recoveryKey).fill(0)
        require(intervalHours in setOf(0, 6, 12, 24, 48, 168) && retention in 3..30)
        require(account.length <= 320 && endpoint.length <= 2048 && username.length <= 512 && password.length <= 4096)
        if (provider == RemoteBackupProvider.WebDAV) WebDavBackupStore(endpoint, username, password)
    }
}

/** Credentials/recovery keys never enter portable preferences or Android Auto Backup. */
internal class RemoteBackupVault(private val context: Context) {
    private val directory = File(context.noBackupFilesDir, "remote-backup-v1").apply { mkdirs() }
    fun device(): String = synchronized(lock) {
        val file = AtomicFile(File(directory, "device"))
        if (file.baseFile.exists()) {
            file.openRead().use { it.readBytes().toString(Charsets.US_ASCII) }
        } else {
            UUID.randomUUID().toString().also { value -> write(file, value.toByteArray()) }
        }
    }
    fun get(provider: RemoteBackupProvider): RemoteBackupConfiguration? = synchronized(lock) {
        val file = AtomicFile(File(directory, provider.name))
        if (!file.baseFile.exists()) return@synchronized null
        val bytes = file.openRead().use { it.readBytes() }
        require(bytes.size in 29..64 * 1024)
        val clear = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            updateAAD(provider.name.toByteArray())
            doFinal(bytes.copyOfRange(12, bytes.size))
        }
        try {
            remoteBackupJson.decodeFromString<RemoteBackupConfiguration>(clear.toString(Charsets.UTF_8)).also {
                it.validate()
            }
        } finally {
            clear.fill(0)
        }
    }
    fun set(value: RemoteBackupConfiguration) = synchronized(lock) {
        value.validate()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key())
            updateAAD(value.provider.name.toByteArray())
        }
        val clear = remoteBackupJson.encodeToString(value).toByteArray()
        try {
            write(AtomicFile(File(directory, value.provider.name)), cipher.iv + cipher.doFinal(clear))
        } finally {
            clear.fill(0)
        }
    }
    fun remove(provider: RemoteBackupProvider) = synchronized(lock) {
        AtomicFile(File(directory, provider.name)).delete()
    }
    fun setupAcknowledged(): Boolean = File(directory, "drive-configured").exists()
    fun acknowledgeSetup() = write(AtomicFile(File(directory, "drive-configured")), byteArrayOf(1))

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return store.getKey(ALIAS, null) as? SecretKey ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(
                        KeyProperties.BLOCK_MODE_GCM,
                    ).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build(),
            )
        }.generateKey()
    }
    private fun write(file: AtomicFile, bytes: ByteArray) {
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (
            failure: Exception,
        ) {
            file.failWrite(output)
            throw failure
        }
    }
    companion object {
        private val lock = Any()
        private const val ALIAS = "nyanime.remote-backup.v1"
    }
}
