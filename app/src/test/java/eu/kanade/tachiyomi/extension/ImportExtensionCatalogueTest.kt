package eu.kanade.tachiyomi.extension

import eu.kanade.domain.extension.ExtensionCatalogueTrust
import eu.kanade.domain.extension.ImportExtensionCatalogue
import eu.kanade.domain.extension.UnifiedExtensionCatalogue
import eu.kanade.domain.extension.UnifiedExtensionCatalogue.LinkedCatalogue
import eu.kanade.domain.extension.VerifiedLinkedCatalogue
import eu.kanade.tachiyomi.extension.anime.model.AnimeExtension
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import mihon.domain.extension.anime.model.AnimeExtensionStore
import mihon.domain.extension.anime.repository.AnimeExtensionStoreRepository
import mihon.domain.extensionrepo.manga.repository.MangaExtensionRepoRepository
import mihon.domain.extensionrepo.model.ExtensionRepo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore

class ImportExtensionCatalogueTest {
    private val key = "a".repeat(64)
    private val oldUrl = "https://catalogue.invalid/old/index.json"
    private val newUrl = "https://catalogue.invalid/index.json"
    private val unrelatedUrl = "https://unrelated.invalid/index.json"
    private val linkedAnime = LinkedCatalogue("https://anime.invalid/index.pb", LinkedCatalogue.Type.ANIME, "b".repeat(64))
    private val linkedManga = LinkedCatalogue("https://manga.invalid/index.pb", LinkedCatalogue.Type.MANGA, "c".repeat(64))
    private val catalogue = UnifiedExtensionCatalogue(
        "Example",
        "Example",
        key,
        UnifiedExtensionCatalogue.Contact("https://catalogue.invalid"),
        setOf("anime", "manga"),
        UnifiedExtensionCatalogue.ExtensionList(emptyList()),
        setOf(oldUrl),
    )
    private fun video(url: String, signer: String = key) = AnimeExtensionStore(
        url,
        "Example",
        "Example",
        signer,
        AnimeExtensionStore.Contact(url, null),
        false,
        null,
    )
    private fun reader(url: String, signer: String = key) = ExtensionRepo(url, "Example", "Example", url, signer)

    @Test fun addingACatalogueCannotImposeReplacementOfOtherConfiguredOrigins(): Unit = runBlocking {
        val anime = AnimeRegistry(listOf(video(oldUrl), video(unrelatedUrl, "b".repeat(64))))
        val manga = MangaRegistry(listOf(reader(oldUrl), reader(unrelatedUrl, "b".repeat(64))))
        ImportExtensionCatalogue(anime, manga).await(newUrl, catalogue)
        assertEquals(setOf(oldUrl, newUrl, unrelatedUrl), anime.values.keys)
        assertEquals(setOf(oldUrl, newUrl, unrelatedUrl), manga.values.keys)
        ImportExtensionCatalogue(anime, manga).await(newUrl, catalogue)
        assertEquals(3, anime.values.size)
        assertEquals(3, manga.values.size)
    }

    @Test fun failureInSecondRegistryRestoresFirstWithoutLosingOldCatalogues(): Unit = runBlocking {
        val anime = AnimeRegistry(listOf(video(oldUrl)))
        val manga = MangaRegistry(listOf(reader(oldUrl)), failNextReplace = true)
        assertTrue(runCatching { ImportExtensionCatalogue(anime, manga).await(newUrl, catalogue) }.isFailure)
        assertEquals(listOf(video(oldUrl)), anime.values.values.toList())
        assertEquals(listOf(reader(oldUrl)), manga.values.values.toList())
        assertFalse(newUrl in anime.values)
    }

    @Test fun aNewsOnlyCatalogueIsRetainedWithoutCreatingAMangaRepository(): Unit = runBlocking {
        val anime = AnimeRegistry(emptyList())
        val manga = MangaRegistry(emptyList())
        ImportExtensionCatalogue(anime, manga).await(newUrl, catalogue.copy(media = setOf("news")))
        assertEquals(listOf(newUrl), anime.values.keys.toList())
        assertTrue(manga.values.isEmpty())
    }

    @Test fun verifiedLinksAreAddedInTheSameBatchWithoutInheritedTrust(): Unit = runBlocking {
        val anime = AnimeRegistry(emptyList())
        val manga = MangaRegistry(emptyList())
        val trust = ExtensionCatalogueTrust(InMemoryPreferenceStore())
        trust.set(unrelatedUrl, key, true)
        val parent = catalogue.copy(catalogues = listOf(linkedAnime, linkedManga))
        val children = listOf(
            VerifiedLinkedCatalogue.Anime(linkedAnime, video(linkedAnime.url, linkedAnime.signingKey)),
            VerifiedLinkedCatalogue.Manga(linkedManga, reader(linkedManga.url, linkedManga.signingKey)),
        )
        val importer = ImportExtensionCatalogue(anime, manga, trust)
        repeat(2) { importer.await(newUrl, parent, trustExtensions = true, linkedCatalogues = children) }
        assertEquals(setOf(newUrl, linkedAnime.url), anime.values.keys)
        assertEquals(setOf(newUrl, linkedManga.url), manga.values.keys)
        assertTrue(trust.permits(newUrl, key))
        assertTrue(trust.permits(unrelatedUrl, key))
        assertFalse(trust.permits(linkedAnime.url, linkedAnime.signingKey))
        assertFalse(trust.permits(linkedManga.url, linkedManga.signingKey))
    }

    @Test fun linkedMangaAliasRetainsPreviouslyConfiguredRepositoryAndApproval(): Unit = runBlocking {
        val existing = reader(oldUrl, linkedManga.signingKey)
        val anime = AnimeRegistry(emptyList())
        val manga = MangaRegistry(listOf(existing))
        val trust = ExtensionCatalogueTrust(InMemoryPreferenceStore())
        trust.set(oldUrl, existing.signingKeyFingerprint, true)
        ImportExtensionCatalogue(anime, manga, trust).await(
            newUrl,
            catalogue.copy(catalogues = listOf(linkedManga)),
            linkedCatalogues = listOf(VerifiedLinkedCatalogue.Manga(linkedManga, reader(linkedManga.url, linkedManga.signingKey))),
        )
        assertEquals(existing, manga.values[oldUrl])
        assertFalse(linkedManga.url in manga.values)
        assertTrue(trust.permits(oldUrl, existing.signingKeyFingerprint))
    }

    @Test fun missingOrConflictingLinkedCatalogueLeavesBothRegistriesUnchanged(): Unit = runBlocking {
        val existing = video(linkedAnime.url)
        val anime = AnimeRegistry(listOf(existing))
        val manga = MangaRegistry(emptyList())
        val importer = ImportExtensionCatalogue(anime, manga)
        val parent = catalogue.copy(catalogues = listOf(linkedAnime))
        assertTrue(runCatching { importer.await(newUrl, parent) }.exceptionOrNull() is IllegalArgumentException)
        val child = VerifiedLinkedCatalogue.Anime(linkedAnime, video(linkedAnime.url, linkedAnime.signingKey))
        assertTrue(runCatching {
            importer.await(newUrl, parent, linkedCatalogues = listOf(child))
        }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(listOf(existing), anime.values.values.toList())
        assertTrue(manga.values.isEmpty())
    }

    @Test fun failureAfterWritingLastLinkedCatalogueRollsBackTheBatch(): Unit = runBlocking {
        val anime = AnimeRegistry(listOf(video(oldUrl)))
        val manga = MangaRegistry(listOf(reader(oldUrl)), failAfterWriting = linkedManga.url)
        val trust = ExtensionCatalogueTrust(InMemoryPreferenceStore())
        trust.set(newUrl, key, true)
        val children = listOf(
            VerifiedLinkedCatalogue.Anime(linkedAnime, video(linkedAnime.url, linkedAnime.signingKey)),
            VerifiedLinkedCatalogue.Manga(linkedManga, reader(linkedManga.url, linkedManga.signingKey)),
        )
        assertTrue(runCatching {
            ImportExtensionCatalogue(anime, manga, trust).await(
                newUrl,
                catalogue.copy(catalogues = children.map { it.reference }),
                linkedCatalogues = children,
            )
        }.isFailure)
        assertEquals(listOf(video(oldUrl)), anime.values.values.toList())
        assertEquals(listOf(reader(oldUrl)), manga.values.values.toList())
        assertTrue(trust.permits(newUrl, key))
    }

    @Test fun linkedMetadataMustMatchTheDeclaredTypeUrlAndSigner() {
        assertThrows(IllegalArgumentException::class.java) {
            VerifiedLinkedCatalogue.Anime(linkedManga, video(linkedManga.url, linkedManga.signingKey))
        }
        assertThrows(IllegalArgumentException::class.java) {
            VerifiedLinkedCatalogue.Anime(linkedAnime, video(oldUrl, linkedAnime.signingKey))
        }
        assertThrows(IllegalArgumentException::class.java) {
            VerifiedLinkedCatalogue.Manga(linkedManga, reader(linkedManga.url))
        }
    }

    private class AnimeRegistry(initial: List<AnimeExtensionStore>) : AnimeExtensionStoreRepository {
        val values = initial.associateBy { it.indexUrl }.toMutableMap()
        override suspend fun insert(indexUrl: String): Result<Unit> = error("Must use the verified metadata")
        override suspend fun upsert(store: AnimeExtensionStore) {
            values[store.indexUrl] = store
        }
        override suspend fun insertFromPreference(indexUrl: String, name: String) = Unit
        override suspend fun refreshAll() = Unit
        override suspend fun fetchExtensions(): List<AnimeExtension.Available> = emptyList()
        override suspend fun getAll(): List<AnimeExtensionStore> = values.values.toList()
        override fun getAllAsFlow(): Flow<List<AnimeExtensionStore>> = flowOf(values.values.toList())
        override fun getCountAsFlow(): Flow<Long> = flowOf(values.size.toLong())
        override suspend fun remove(indexUrl: String) {
            values.remove(indexUrl)
        }
    }

    private class MangaRegistry(
        initial: List<ExtensionRepo>,
        var failNextReplace: Boolean = false,
        val failAfterWriting: String? = null,
    ) : MangaExtensionRepoRepository {
        val values = initial.associateBy { it.baseUrl }.toMutableMap()
        override fun subscribeAll(): Flow<List<ExtensionRepo>> = flowOf(values.values.toList())
        override suspend fun getAll(): List<ExtensionRepo> = values.values.toList()
        override suspend fun getRepo(baseUrl: String): ExtensionRepo? = values[baseUrl]
        override suspend fun getRepoBySigningKeyFingerprint(
            fingerprint: String,
        ): ExtensionRepo? = values.values.firstOrNull {
            it.signingKeyFingerprint ==
                fingerprint
        }
        override fun getCount(): Flow<Int> = flowOf(values.size)
        override suspend fun insertRepo(
            baseUrl: String,
            name: String,
            shortName: String?,
            website: String,
            signingKeyFingerprint: String,
        ) =
            upsertRepo(baseUrl, name, shortName, website, signingKeyFingerprint)
        override suspend fun upsertRepo(
            baseUrl: String,
            name: String,
            shortName: String?,
            website: String,
            signingKeyFingerprint: String,
        ) {
            if (failNextReplace) {
                failNextReplace = false
                error("Simulated storage error")
            }
            values[baseUrl] = ExtensionRepo(baseUrl, name, shortName, website, signingKeyFingerprint)
            if (baseUrl == failAfterWriting) error("Simulated failure after writing")
        }
        override suspend fun replaceRepo(newRepo: ExtensionRepo) {
            if (failNextReplace) {
                failNextReplace = false
                error("Simulated storage error")
            }
            values.entries.removeAll { it.value.signingKeyFingerprint == newRepo.signingKeyFingerprint }
            values[newRepo.baseUrl] = newRepo
        }
        override suspend fun deleteRepo(baseUrl: String) {
            values.remove(baseUrl)
        }
    }
}
