package eu.kanade.domain.extension

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import mihon.domain.extension.anime.model.AnimeExtensionStore
import mihon.domain.extension.anime.repository.AnimeExtensionStoreRepository
import mihon.domain.extensionrepo.manga.repository.MangaExtensionRepoRepository
import mihon.domain.extensionrepo.model.ExtensionRepo

/** Store a user-approved catalogue and its verified links together, without changing linked catalogue trust. */
class ImportExtensionCatalogue(
    private val anime: AnimeExtensionStoreRepository,
    private val manga: MangaExtensionRepoRepository,
    private val trust: ExtensionCatalogueTrust? = null,
) {
    suspend fun await(
        url: String,
        catalogue: UnifiedExtensionCatalogue,
        trustExtensions: Boolean = false,
        linkedCatalogues: List<VerifiedLinkedCatalogue> = emptyList(),
    ): Unit = importLock.withLock {
        require(UnifiedExtensionCatalogue.validUrl(url))
        val expectedLinks = catalogue.catalogues.toSet()
        require(linkedCatalogues.size == expectedLinks.size && linkedCatalogues.map { it.reference }.toSet() == expectedLinks) {
            "All declared linked catalogues must be verified"
        }
        require(expectedLinks.none { it.isAt(url) }) { "A catalogue cannot link to itself" }

        val oldAnime = anime.getAll()
        val oldManga = manga.getAll()
        val animeByUrl = oldAnime.groupBy { UnifiedExtensionCatalogue.canonicalIndexUrl(it.indexUrl) }
        val mangaByUrl = oldManga.groupBy { UnifiedExtensionCatalogue.canonicalIndexUrl(it.baseUrl) }
        for (reference in expectedLinks) {
            val configuredSigners = animeByUrl[reference.canonicalUrl].orEmpty().map { it.signingKey } +
                mangaByUrl[reference.canonicalUrl].orEmpty().map { it.signingKeyFingerprint }
            require(configuredSigners.all { it.equals(reference.signingKey, ignoreCase = true) }) {
                "Linked catalogue conflicts with an already configured signing key"
            }
        }
        val hasManga = ExtensionCatalogueMedium.MANGA in catalogue.media
        val animeUpdates = buildList {
            if (catalogue.media.any { it != ExtensionCatalogueMedium.MANGA }) {
                add(
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
            for (linked in linkedCatalogues.filterIsInstance<VerifiedLinkedCatalogue.Anime>()) {
                val reference = linked.reference
                if (reference.canonicalUrl !in animeByUrl) add(linked.store.copy(signingKey = reference.signingKey))
            }
        }
        val mangaUpdates = buildList {
            if (hasManga) {
                add(
                    ExtensionRepo(
                        url,
                        catalogue.name,
                        catalogue.badgeLabel,
                        catalogue.contact.website,
                        catalogue.signingKey,
                    ),
                )
            }
            for (linked in linkedCatalogues.filterIsInstance<VerifiedLinkedCatalogue.Manga>()) {
                val reference = linked.reference
                val existing = oldManga.any { it.signingKeyFingerprint.equals(reference.signingKey, ignoreCase = true) }
                if (!existing) add(linked.repo.copy(signingKeyFingerprint = reference.signingKey))
            }
        }
        require(mangaUpdates.distinctBy { it.signingKeyFingerprint }.size == mangaUpdates.size) {
            "Manga catalogues must have distinct signing keys"
        }

        val rollback = mutableListOf<suspend () -> Unit>()
        try {
            for (store in animeUpdates) {
                val previous = oldAnime.firstOrNull { it.indexUrl == store.indexUrl }
                rollback.add { if (previous == null) anime.remove(store.indexUrl) else anime.upsert(previous) }
                anime.upsert(store)
            }
            for (repo in mangaUpdates) {
                val previous = oldManga.firstOrNull { it.baseUrl == repo.baseUrl }
                rollback.add { if (previous == null) manga.deleteRepo(repo.baseUrl) else manga.upsertRepo(previous) }
                manga.upsertRepo(repo)
            }
            trust?.let { approvals ->
                val previous = approvals.get().firstOrNull { it.url == url }
                rollback.add {
                    if (previous == null) approvals.remove(url) else approvals.set(url, previous.signer, true)
                }
                approvals.set(url, catalogue.signingKey, trustExtensions)
            }
        } catch (failure: Exception) {
            withContext(NonCancellable) {
                rollback.asReversed().forEach { action ->
                    runCatching { action() }.onFailure { if (it !== failure) failure.addSuppressed(it) }
                }
            }
            throw failure
        }
    }

    companion object {
        private val importLock = Mutex()
    }
}
