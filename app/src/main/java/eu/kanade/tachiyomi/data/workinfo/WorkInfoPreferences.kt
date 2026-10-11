package eu.kanade.tachiyomi.data.workinfo

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tachiyomi.core.common.preference.PreferenceStore
import java.security.MessageDigest

class WorkInfoPreferences(private val store: PreferenceStore, private val json: Json) {
    // Ordinary portable preferences: consent and manual choices travel with a settings backup.
    fun consent() = store.getInt("work_info_consent", UNKNOWN)

    fun binding(identity: WorkIdentity): WorkBinding? {
        val raw = store.getString(bindingKey(identity.localKey)).get().takeIf(String::isNotEmpty) ?: return null
        // Preserve a future/unsupported manual choice instead of silently attaching a different work.
        return runCatching { json.decodeFromString<WorkBinding>(raw) }.getOrNull()
            ?.takeIf { it.reference == null || it.reference.isValid() } ?: WorkBinding(null)
    }

    fun associate(identity: WorkIdentity, reference: WorkReference?) {
        require(reference == null || reference.isValid())
        store.getString(bindingKey(identity.localKey)).set(json.encodeToString(WorkBinding(reference)))
    }

    companion object {
        const val UNKNOWN = 0
        const val ENABLED = 1
        const val DISABLED = 2

        fun localKey(manga: Boolean, sourceId: Long, url: String): String =
            "${if (manga) "manga" else "video"}:$sourceId:${url.length}:$url"

        fun bindingKey(localKey: String) = "work_info_binding_v1_${digest(localKey)}"

        internal fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

/** null is an explicit 'no association', not permission to silently identify the work again. */
@Serializable
data class WorkBinding(val reference: WorkReference? = null)
