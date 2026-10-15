package eu.kanade.domain.extension

import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/** Optional execution consent. It cannot replace a package publisher or install new extensions. */
class ExtensionCatalogueTrust(store: PreferenceStore) {
    private val preference = store.getString(Preference.appStateKey("extension_catalogue_execution_v1"), "[]")
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable data class Approval(val url: String, val signer: String)
    val changes = preference.changes().map(::decode)
    fun get(): List<Approval> = decode(preference.get())
    fun permits(
        url: String?,
        signer: String?,
    ): Boolean = get().any { it.url == url && it.signer == signer?.lowercase() }
    fun set(url: String, signer: String, approved: Boolean) = synchronized(lock) {
        require(validRepository(url) && signer.matches(Regex("[a-f0-9]{64}")))
        val next = get().filterNot { it.url == url } + if (approved) listOf(Approval(url, signer)) else emptyList()
        preference.set(json.encodeToString(next))
    }
    fun remove(
        url: String,
    ) = synchronized(lock) { preference.set(json.encodeToString(get().filterNot { it.url == url })) }
    private fun decode(raw: String) = runCatching {
        require(raw.length <= 65_536)
        json.decodeFromString<List<Approval>>(raw).filter {
            validRepository(it.url) && it.signer.matches(Regex("[a-f0-9]{64}"))
        }
    }.getOrDefault(emptyList())
    companion object {
        private val lock = Any()
        fun validRepository(value: String): Boolean = runCatching {
            val uri = java.net.URI(value)
            value.length <= 4096 &&
                uri.scheme == "https" &&
                !uri.host.isNullOrBlank() &&
                uri.rawUserInfo == null &&
                uri.rawQuery == null &&
                uri.rawFragment == null
        }.getOrDefault(false)
    }
}
