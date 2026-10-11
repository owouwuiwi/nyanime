package eu.kanade.tachiyomi.data.extensionstore

import android.content.Context
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.extension.ExtensionCatalogueLink
import eu.kanade.domain.extension.ImportExtensionCatalogue
import eu.kanade.domain.extension.UnifiedExtensionCatalogue
import eu.kanade.tachiyomi.data.rin.RinEntry
import eu.kanade.tachiyomi.data.updater.GITHUB_REPO
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.catalogueRequest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mihon.domain.extension.anime.repository.AnimeExtensionStoreRepository
import mihon.domain.extensionrepo.manga.repository.MangaExtensionRepoRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

private const val DESCRIPTOR_MAX_BYTES = 4096

@Serializable
internal data class DefaultCatalogueDescriptor(val url: String, val signer: String, val exploreApkSha256: String) {
    init {
        require(UnifiedExtensionCatalogue.validUrl(url))
        require(signer.matches(sha256) && exploreApkSha256.matches(sha256))
    }

    fun permits(entry: RinEntry) = entry.repository == url && entry.signer == signer &&
        entry.sha256 == exploreApkSha256 && entry.format == "apk" && !entry.requiresAndroid &&
        entry.manifest.medium == "addon" && entry.manifest.addon?.isExploreFeed == true

    companion object {
        private val sha256 = Regex("[a-f0-9]{64}")
        fun parse(text: String): DefaultCatalogueDescriptor {
            require(text.length <= DESCRIPTOR_MAX_BYTES)
            return Json.decodeFromString<DefaultCatalogueDescriptor>(text)
        }
    }
}

internal object DefaultExtensionCatalogue {
    private const val DESCRIPTOR = "https://raw.githubusercontent.com/$GITHUB_REPO/catalogue-defaults/default-catalogue-v1.json"
    private const val STATE = "default_extension_catalogue_v1"
    private const val DONE = "added"
    private const val PIN = "descriptor"
    private val lock = Mutex()

    private fun state(context: Context) = context.getSharedPreferences(STATE, Context.MODE_PRIVATE)

    fun permitsExplore(context: Context, entry: RinEntry): Boolean {
        val prefs = state(context)
        if (!prefs.getBoolean(DONE, false)) return false
        return prefs.getString(PIN, null)?.let {
            runCatching { DefaultCatalogueDescriptor.parse(it).permits(entry) }.getOrDefault(false)
        } == true
    }

    suspend fun addOnce(context: Context): Boolean = lock.withLock {
        val prefs = state(context)
        if (prefs.getBoolean(DONE, false) || Injekt.get<BasePreferences>().downloadedOnly().get()) return@withLock false

        val client = Injekt.get<NetworkHelper>().catalogueClient
        val pending = prefs.getString(PIN, null)
        val raw = pending ?: client.newCall(catalogueRequest(DESCRIPTOR)).awaitSuccess().use { response ->
            val source = response.body.source()
            require(!source.request(DESCRIPTOR_MAX_BYTES + 1L))
            source.readUtf8()
        }
        val descriptor = DefaultCatalogueDescriptor.parse(raw)
        val anime = Injekt.get<AnimeExtensionStoreRepository>()
        val manga = Injekt.get<MangaExtensionRepoRepository>()
        val configured = (anime.getAll().map { it.indexUrl } + manga.getAll().map { it.baseUrl }).any {
            (ExtensionCatalogueLink.parse(it) as? ExtensionCatalogueLink.Index)?.url == descriptor.url
        }
        if (configured && pending == null) {
            // Preserve the trust choice on a catalogue the user already configured.
            check(prefs.edit().putBoolean(DONE, true).commit())
            return@withLock false
        }

        val catalogue = client.newCall(catalogueRequest(descriptor.url)).awaitSuccess().use { response ->
            val source = response.body.source()
            require(!source.request(UnifiedExtensionCatalogue.MAX_BYTES + 1))
            UnifiedExtensionCatalogue.parse(source.readUtf8())
        }
        require(catalogue.signingKey == descriptor.signer)
        check(prefs.edit().putString(PIN, raw).commit())
        // The publisher nominates one index. Its advertised links keep the regular review flow.
        ImportExtensionCatalogue(anime, manga).await(descriptor.url, catalogue.copy(catalogues = emptyList()))
        check(prefs.edit().putBoolean(DONE, true).commit())
        true
    }
}
