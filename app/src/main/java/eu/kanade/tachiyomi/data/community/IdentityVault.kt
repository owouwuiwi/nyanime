package eu.kanade.tachiyomi.data.community

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** No secret enters SharedPreferences, Android backup, URLs, logging or saved activity state. */
internal class IdentityVault(
    context: Context,
    fileName: String = "community.identity",
    private val alias: String = "nyanime.community.local.v1",
) {
    private val file = AtomicFile(File(context.noBackupFilesDir, fileName))
    private val wrappingKey: SecretKey by lazy { createKey() }
    private fun key() = wrappingKey
    private fun createKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(
                        KeyProperties.BLOCK_MODE_GCM,
                    ).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build(),
            )
        }.generateKey()
    }
    fun exists() = file.baseFile.exists()
    fun load(): ByteArray? = if (!exists()) null else open(file.readFully())
    fun save(secret: ByteArray) {
        require(secret.size == 32)
        val output = file.startWrite()
        try {
            output.write(seal(secret))
            file.finishWrite(output)
        } catch (
            error: Throwable,
        ) {
            file.failWrite(output)
            throw error
        }
    }
    fun seal(bytes: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.ENCRYPT_MODE, key())
        iv + doFinal(bytes)
    }
    fun open(bytes: ByteArray): ByteArray {
        require(bytes.size >= 28)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOf(12)))
            doFinal(bytes.copyOfRange(12, bytes.size))
        }
    }
}

/** Versioned recovery file: PBKDF2-HMAC-SHA256 + AES-256-GCM, not an unencrypted nsec export. */
internal object IdentityRecovery {
    private const val PREFIX = "NYR1."
    private fun key(password: CharArray, salt: ByteArray): SecretKeySpec {
        require(password.size in 10..256) { "Usa una password di almeno 10 caratteri" }
        val spec = PBEKeySpec(password, salt, 600_000, 256)
        return try {
            SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }
    fun export(secret: ByteArray, password: CharArray): String {
        require(secret.size == 32)
        val salt = randomBytes(16)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key(password, salt))
            updateAAD(PREFIX.toByteArray())
        }
        return PREFIX + Base64.getEncoder().encodeToString(salt + cipher.iv + cipher.doFinal(secret))
    }
    fun restore(encoded: String, password: CharArray): ByteArray {
        require(encoded.startsWith(PREFIX) && encoded.length <= 200) { "File di recupero non valido" }
        val bytes = Base64.getDecoder().decode(encoded.removePrefix(PREFIX))
        require(bytes.size == 76)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(password, bytes.copyOf(16)), GCMParameterSpec(128, bytes.copyOfRange(16, 28)))
            updateAAD(PREFIX.toByteArray())
            doFinal(bytes.copyOfRange(28, bytes.size))
        }
    }
}
