package eu.kanade.tachiyomi.data.telegram

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal object TelegramSessionKey {
    private const val ALIAS = "nyanime.telegram.database.v1"

    /** Called only after TDLib confirms logout and closes its database. */
    @Synchronized
    fun clear(context: Context) {
        val root = context.noBackupFilesDir.canonicalFile
        val directory = File(root, "telegram").canonicalFile
        check(directory.parentFile == root)
        if (directory.exists()) check(directory.deleteRecursively())
        check(context.getSharedPreferences("telegram.session.v1", Context.MODE_PRIVATE).edit().clear().commit())
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            deleteEntry(ALIAS)
        }
    }

    @Synchronized
    fun get(context: Context): String {
        val store = context.getSharedPreferences("telegram.session.v1", Context.MODE_PRIVATE)
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = (keyStore.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore",
        ).apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build(),
            )
        }.generateKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val saved = store.getString("wrapped", null)
        val bytes = if (saved != null) {
            val packed = Base64.decode(saved, Base64.NO_WRAP)
            require(packed.size >= 28)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, packed.copyOfRange(0, 12)))
            cipher.doFinal(packed.copyOfRange(12, packed.size))
        } else {
            val secret = ByteArray(32).also(SecureRandom()::nextBytes)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val packed = cipher.iv + cipher.doFinal(secret)
            check(store.edit().putString("wrapped", Base64.encodeToString(packed, Base64.NO_WRAP)).commit())
            secret
        }
        require(bytes.size == 32)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    @Synchronized
    fun importMigration(context: Context, encoded: String) {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size == 32)
        check(
            context.getSharedPreferences("telegram.session.v1", Context.MODE_PRIVATE).edit().remove("wrapped").commit(),
        )
        get(context) // Create the destination's own wrapping key.
        val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(ALIAS, null) as SecretKey
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        val packed = cipher.iv + cipher.doFinal(bytes)
        bytes.fill(0)
        check(
            context.getSharedPreferences("telegram.session.v1", Context.MODE_PRIVATE).edit()
                .putString("wrapped", Base64.encodeToString(packed, Base64.NO_WRAP)).commit(),
        )
    }
}
