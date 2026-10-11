package eu.kanade.domain.extension

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import mihon.domain.extension.anime.model.AnimeExtensionStore
import mihon.domain.extension.anime.repository.AnimeExtensionStoreRepository
import mihon.domain.extensionrepo.manga.repository.MangaExtensionRepoRepository
import mihon.domain.extensionrepo.model.ExtensionRepo

/** Both registries receive the same user-approved catalogue. Failed imports restore the previous configuration. */
class ImportExtensionCatalogue(
    private val anime: AnimeExtensionStoreRepository,
    private val manga: MangaExtensionRepoRepository,
    private val authorities: ExtensionCatalogueAuthorities? = null,
) {
    suspend fun await(url: String, catalogue: UnifiedExtensionCatalogue) {
        require(UnifiedExtensionCatalogue.validUrl(url))
        val oldAnime = anime.getAll().filter { it.indexUrl == url || it.indexUrl in catalogue.replaces }
        val oldManga = manga.getAll().filter {
            it.baseUrl == url || it.baseUrl in catalogue.replaces || it.signingKeyFingerprint == catalogue.signingKey
        }
        val hasAnime =
            ExtensionCatalogueMedium.ANIME in catalogue.media || ExtensionCatalogueMedium.NEWS in catalogue.media
        val hasManga = ExtensionCatalogueMedium.MANGA in catalogue.media
        try {
            if (hasAnime) {
                anime.upsert(
                    AnimeExtensionStore(
                        url,
                        catalogue.name,
                        catalogue.badgeLabel,
                        catalogue.signingKey,
                        AnimeExtensionStore.Contact(catalogue.contact.website, catalogue.contact.discord),
                        false,
                        null,
                    ),
                )
            }
            if (hasManga) {
                manga.replaceRepo(
                    ExtensionRepo(
                        url,
                        catalogue.name,
                        catalogue.badgeLabel,
                        catalogue.contact.website,
                        catalogue.signingKey,
                    ),
                )
            }
            if (hasAnime) oldAnime.filter { it.indexUrl != url }.forEach { anime.remove(it.indexUrl) }
            if (hasManga) oldManga.filter { it.baseUrl != url }.forEach { manga.deleteRepo(it.baseUrl) }
            authorities?.approve(url, catalogue)
        } catch (failure: Exception) {
            withContext(NonCancellable) {
                suspend fun restore(action: suspend () -> Unit) {
                    runCatching { action() }.onFailure(failure::addSuppressed)
                }
                if (hasAnime) {
                    restore { anime.remove(url) }
                    oldAnime.forEach { store -> restore { anime.upsert(store) } }
                }
                if (hasManga) {
                    restore { manga.deleteRepo(url) }
                    oldManga.forEach { repo -> restore { manga.upsertRepo(repo) } }
                }
            }
            throw failure
        }
    }
}
