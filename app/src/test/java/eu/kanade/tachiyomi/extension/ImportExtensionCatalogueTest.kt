package eu.kanade.tachiyomi.extension

import eu.kanade.domain.extension.ImportExtensionCatalogue
import eu.kanade.domain.extension.UnifiedExtensionCatalogue
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImportExtensionCatalogueTest {
    private val key = "a".repeat(64)
    private val oldUrl = "https://catalogue.invalid/old/index.json"
    private val newUrl = "https://catalogue.invalid/index.json"
    private val unrelatedUrl = "https://unrelated.invalid/index.json"
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

    @Test fun oneImportMigratesBothRegistriesAndPreservesOtherPublishers(): Unit = runBlocking {
        val anime = AnimeRegistry(listOf(video(oldUrl), video(unrelatedUrl, "b".repeat(64))))
        val manga = MangaRegistry(listOf(reader(oldUrl), reader(unrelatedUrl, "b".repeat(64))))
        ImportExtensionCatalogue(anime, manga).await(newUrl, catalogue)
        assertEquals(setOf(newUrl, unrelatedUrl), anime.values.keys)
        assertEquals(setOf(newUrl, unrelatedUrl), manga.values.keys)
        ImportExtensionCatalogue(anime, manga).await(newUrl, catalogue)
        assertEquals(2, anime.values.size)
        assertEquals(2, manga.values.size)
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
            values[baseUrl] = ExtensionRepo(baseUrl, name, shortName, website, signingKeyFingerprint)
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
