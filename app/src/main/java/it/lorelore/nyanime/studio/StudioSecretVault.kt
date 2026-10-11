package it.lorelore.nyanime.studio

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Device-local credentials: not part of graphs, shared files, test traces or source backups. */
internal class StudioSecretVault(context: Context) {
    private val preferences = context.getSharedPreferences("source-studio-secrets", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return store.getKey(ALIAS, null) as? SecretKey
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(
                    KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(
                            KeyProperties.BLOCK_MODE_GCM,
                        ).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build(),
                )
            }.generateKey()
    }

    @Synchronized fun set(source: StudioSource, name: String, value: String) {
        val id = "${source.uuid}:$name"
        if (value.isEmpty()) {
            check(preferences.edit().remove(id).commit())
            return
        }
        require(value.toByteArray().size <= 8192)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key())
            updateAAD(studioCredentialAAD(source, name))
        }
        val encoded = Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)
        check(preferences.edit().putString(id, encoded).commit())
    }

    @Synchronized fun get(source: StudioSource, name: String): String? {
        val id = "${source.uuid}:$name"
        val encoded = preferences.getString(id, null) ?: return null
        return runCatching {
            val data = Base64.decode(encoded, Base64.NO_WRAP)
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
                updateAAD(studioCredentialAAD(source, name))
                doFinal(data.copyOfRange(12, data.size)).toString(Charsets.UTF_8)
            }
        }.getOrNull()
    }
    companion object {
        private const val ALIAS = "nyanime.source-studio.v1"
    }
}

/** An imported update cannot silently send a saved credential to newly declared origins. */
internal fun studioCredentialAAD(source: StudioSource, name: String): ByteArray {
    val origins = (source.origins + source.baseUrl).map { address ->
        val uri = URI(address)
        require(uri.scheme in setOf("https", "http") && uri.host != null && uri.userInfo == null)
        val port = if (uri.port >= 0) {
            uri.port
        } else if (uri.scheme == "https") {
            443
        } else {
            80
        }
        "${uri.scheme}://${uri.host.lowercase(java.util.Locale.ROOT)}:$port"
    }.distinct().sorted()
    return ("source-secret-v2:${source.uuid}:$name\n" + origins.joinToString("\n")).toByteArray(Charsets.UTF_8)
}
