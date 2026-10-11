package eu.kanade.tachiyomi.data.track

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.MangaCatalogIdResolver
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SMangaImpl
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.entries.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.entries.manga.repository.MangaRepository
import tachiyomi.domain.source.manga.service.MangaSourceManager
import tachiyomi.domain.track.manga.repository.MangaTrackRepository

class ContinuityResolutionTest {
    private val source = mockk<AnimeSource>()
    private val mangaSource = mockk<CatalogueSource> {
        every { id } returns 2L
        every { getFilterList() } returns eu.kanade.tachiyomi.source.model.FilterList()
    }
    private val policy = mockk<ContinuitySourcePolicy> {
        every { privateAnime(any()) } returns false
        every { privateManga(any()) } returns false
        every { manga(any()) } answers { firstArg() }
    }
    private val persisted = InMemoryPreference("cache", null, "[]")
    private val store = mockk<PreferenceStore> { every { getString(any(), "[]") } returns persisted }
    private val identities = ContinuityIdentityCache(store)
    private val manga = Manga.create().copy(id = 99, source = 2, title = "Example work", url = "/work/example")
    private val repository = mockk<MangaRepository> {
        coEvery { getMangaFavorites() } returns listOf(manga)
        coEvery { getReadMangaNotInLibrary() } returns emptyList()
        coEvery { getMangaByUrlAndSourceId(manga.url, manga.source) } returns manga
    }
    private val tracks = mockk<MangaTrackRepository> {
        every { getMangaTracksAsFlow() } returns flowOf(emptyList())
    }
    private var catalogueSources = listOf(mangaSource)
    private val manager = object : MangaSourceManager {
        override val isInitialized = MutableStateFlow(true)
        override val catalogueSources get() = flowOf(this@ContinuityResolutionTest.catalogueSources)
        override fun get(sourceKey: Long): eu.kanade.tachiyomi.source.MangaSource? = null
        override fun getOrStub(sourceKey: Long): eu.kanade.tachiyomi.source.MangaSource = error("Unused")
        override fun getOnlineSources(): List<eu.kanade.tachiyomi.source.online.HttpSource> = emptyList()
        override fun getCatalogueSources() = this@ContinuityResolutionTest.catalogueSources
        override fun getStubSources(): List<tachiyomi.domain.source.manga.model.StubMangaSource> = emptyList()
    }
    private val toLocal = mockk<NetworkToLocalManga> { coEvery { await(any()) } returns manga }
    private val anime = Anime.create().copy(
        id = 1,
        source = 3,
        title = "Example adaptation",
        url = "/animation/example",
    )

    private suspend fun catalogTest(block: suspend () -> Unit) {
        mockkObject(AniListMediaLookup, AdaptationChapterCatalog)
        try {
            coEvery { AniListMediaLookup.mangaAdaptations(10) } returns AniListMediaLookup.MangaAdaptations(
                listOf(AniListMediaLookup.MangaRelation(42, 24, listOf("Example work"), "MANGA")),
                AniListMediaLookup.AdaptationContext(1, true),
            )
            coEvery { AdaptationChapterCatalog.mangaBaka(42) } returns AdaptationChapterCatalog.Metadata()
            block()
        } finally {
            unmockkObject(AniListMediaLookup, AdaptationChapterCatalog)
        }
    }

    @Test
    fun `verified cached copy is ready while chapter API is still waiting`() = runBlocking {
        catalogTest {
            identities.remember("manga", 2, manga.url, SourceTrackingHints(42, 24), false)
            val metadata = CompletableDeferred<AdaptationChapterCatalog.Metadata>()
            coEvery { AdaptationChapterCatalog.mangaBaka(42) } coAnswers { metadata.await() }
            val ready = CompletableDeferred<AnimeMangaContinuity.Result.Found>()
            val resolver = AnimeMangaContinuity(repository, tracks, manager, toLocal, identities, policy)
            val job = async {
                resolver.resolve(anime, source, 10, null, 3.0) {
                    if (it.choices.single().matches.isNotEmpty()) ready.complete(it)
                }
            }
            val early = withTimeout(5_000) { ready.await() }
            assertEquals(listOf(99L), early.choices.single().matches.map { it.id })
            assertTrue(job.isActive)
            metadata.complete(AdaptationChapterCatalog.Metadata())
            assertEquals(
                listOf(99L),
                (job.await() as AnimeMangaContinuity.Result.Found)
                    .choices.single().matches.map { it.id },
            )
            coVerify(exactly = 0) { source.getAnimeDetails(any()) }
            coVerify(exactly = 0) { mangaSource.getSearchManga(any(), any(), any()) }
        }
    }

    @Test
    fun `native search opens matching source IDs and rejects an identically named different work`() = runBlocking {
        catalogTest {
            coEvery { repository.getMangaFavorites() } returns emptyList()
            val wrong = SMangaImpl().apply {
                title = "Example work"
                url = "/work/wrong"
                trackingMetadata = """{"ids":{"anilist":43,"myanimelist":24}}"""
            }
            val correct = SMangaImpl().apply {
                title = "Different translated title"
                url = manga.url
                trackingMetadata = """{"ids":{"anilist":42,"myanimelist":24}}"""
            }
            coEvery { mangaSource.getSearchManga(1, "Example work", any()) } returns
                MangasPage(listOf(wrong, correct), false)
            val resolver = AnimeMangaContinuity(repository, tracks, manager, toLocal, identities, policy)
            val result = resolver.resolve(anime, source, 10, null, null) as AnimeMangaContinuity.Result.Found
            assertEquals(listOf(99L), result.choices.single().matches.map { it.id })
            coVerify(exactly = 1) { toLocal.await(match { it.url == manga.url }) }
            coVerify(exactly = 0) { toLocal.await(match { it.url == "/work/wrong" }) }
            coVerify(exactly = 0) { mangaSource.getMangaDetails(any()) }
        }
    }

    @Test
    fun `an identical title without source IDs is never opened automatically`() = runBlocking {
        catalogTest {
            coEvery { repository.getMangaFavorites() } returns emptyList()
            val unverified = SMangaImpl().apply {
                title = "Example work"
                url = "/work/unverified"
            }
            coEvery { mangaSource.getSearchManga(any(), any(), any()) } returns MangasPage(listOf(unverified), false)
            coEvery { mangaSource.getMangaUpdate(any(), any(), true, false) } returns
                eu.kanade.tachiyomi.source.model.SMangaUpdate(unverified, emptyList())
            val result = AnimeMangaContinuity(repository, tracks, manager, toLocal, identities, policy)
                .resolve(anime, source, 10, null, null) as AnimeMangaContinuity.Result.Found
            assertTrue(result.choices.single().matches.isEmpty())
            assertTrue(result.resolvingIds.isEmpty())
            coVerify(exactly = 0) { toLocal.await(any()) }
        }
    }

    @Test
    fun `MAL lookup works when the extension cannot resolve AniList`() = runBlocking {
        catalogTest {
            coEvery { repository.getMangaFavorites() } returns emptyList()
            val resolver = mockk<CatalogueSource>(moreInterfaces = arrayOf(MangaCatalogIdResolver::class))
            every { resolver.id } returns 2L
            catalogueSources = listOf(resolver)
            val correct = SMangaImpl().apply {
                title = "Example work"
                url = manga.url
                trackingMetadata = """{"ids":{"myanimelist":24}}"""
            }
            coEvery { (resolver as MangaCatalogIdResolver).findMangaByCatalogId("anilist", 42) } returns null
            coEvery { (resolver as MangaCatalogIdResolver).findMangaByCatalogId("myanimelist", 24) } returns correct
            val result = AnimeMangaContinuity(repository, tracks, manager, toLocal, identities, policy)
                .resolve(anime, source, 10, null, null) as AnimeMangaContinuity.Result.Found
            assertEquals(listOf(99L), result.choices.single().matches.map { it.id })
            coVerify(exactly = 0) { resolver.getMangaDetails(any()) }
        }
    }

    @Test
    fun `an offline source does not discard the verified copy from another source`() = runBlocking {
        catalogTest {
            coEvery { repository.getMangaFavorites() } returns emptyList()
            val offline = mockk<CatalogueSource> {
                every { id } returns 3L
                every { getFilterList() } returns eu.kanade.tachiyomi.source.model.FilterList()
                coEvery { getSearchManga(any(), any(), any()) } throws java.io.IOException("Offline")
            }
            catalogueSources = listOf(offline, mangaSource)
            val correct = SMangaImpl().apply {
                title = "Example work"
                url = manga.url
                trackingMetadata = """{"ids":{"anilist":42}}"""
            }
            coEvery { mangaSource.getSearchManga(any(), any(), any()) } returns MangasPage(listOf(correct), false)
            val result = AnimeMangaContinuity(repository, tracks, manager, toLocal, identities, policy)
                .resolve(anime, source, 10, null, null) as AnimeMangaContinuity.Result.Found
            assertEquals(listOf(99L), result.choices.single().matches.map { it.id })
        }
    }
}
