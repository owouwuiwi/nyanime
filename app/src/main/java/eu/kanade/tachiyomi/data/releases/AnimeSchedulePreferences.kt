package eu.kanade.tachiyomi.data.releases

import android.app.Application
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AnimeSchedulePreferences(store: PreferenceStore = Injekt.get()) {
    val enabled = store.getBoolean("anime_schedule_enabled", false)
    val preferredType = store.getString("anime_schedule_air_type", "SUB")
    val connected = store.getBoolean(Preference.appStateKey("anime_schedule_connected"), false)
    val state = store.getString(Preference.appStateKey("anime_schedule_state"), "")
    val retryAt = store.getLong(Preference.appStateKey("anime_schedule_retry_at"), 0)
    val type: ScheduleAirType get() = ScheduleAirType.entries.firstOrNull { it.name == preferredType.get() }
        ?: ScheduleAirType.SUB
}

/** Device-bound ciphertext is deliberately outside portable app preferences. */
internal class AnimeScheduleTokenStore(private val app: Application = Injekt.get()) {
    private val storage = app.getSharedPreferences("anime_schedule_credentials", 0)
    fun read(): String? = try {
        val encoded = storage.getString("token", null)
        if (encoded == null) {
            null
        } else {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            require(bytes.size > 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
        }
    } catch (_: Exception) {
        null
    }

    fun save(token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        check(
            storage.edit().putString(
                "token",
                Base64.encodeToString(cipher.iv + cipher.doFinal(token.toByteArray()), Base64.NO_WRAP),
            ).commit(),
        )
    }
    fun clear() {
        check(storage.edit().clear().commit())
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            deleteEntry(ALIAS)
        }
    }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(
                        KeyProperties.BLOCK_MODE_GCM,
                    ).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build(),
            )
        }.generateKey()
    }
    companion object {
        private const val ALIAS = "nyanime.animeschedule.token.v1"
    }
}
