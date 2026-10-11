package eu.kanade.domain.extension

import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/** Only the import confirmation grants an authority. Refreshes cannot silently expand its powers. */
class ExtensionCatalogueAuthorities(store: PreferenceStore) {
    private val preference = store.getString(Preference.appStateKey("extension_catalogue_authorities_v1"), "[]")
    private val json = Json { ignoreUnknownKeys = true }
    val changes = preference.changes().map(::decode)

    @Serializable
    data class Approval(val url: String, val signer: String, val name: String, val media: Set<String>) {
        fun permits(repository: String?, signingKey: String?, medium: String): Boolean =
            repository == url && signingKey?.lowercase() == signer && medium in media
    }

    fun get(): List<Approval> = decode(preference.get())

    fun approve(url: String, catalogue: UnifiedExtensionCatalogue) = synchronized(writeLock) {
        val remaining = get().filterNot { it.url == url || it.url in catalogue.replaces }
        val next = if (catalogue.authority?.recommendReplacements == true) {
            remaining + Approval(url, catalogue.signingKey.lowercase(), catalogue.name, catalogue.media)
        } else {
            remaining
        }
        preference.set(json.encodeToString(next))
    }

    fun remove(url: String) = synchronized(writeLock) {
        preference.set(json.encodeToString(get().filterNot { it.url == url }))
    }

    fun clear() = synchronized(writeLock) { preference.delete() }

    private fun decode(value: String): List<Approval> = runCatching {
        require(value.length <= 65_536)
        json.decodeFromString<List<Approval>>(value).filter {
            UnifiedExtensionCatalogue.validUrl(it.url) && it.signer.matches(Regex("[a-f0-9]{64}"))
        }
    }.getOrDefault(emptyList())

    private companion object {
        // Import and catalogue settings use separate instances backed by the same preferences.
        val writeLock = Any()
    }
}
