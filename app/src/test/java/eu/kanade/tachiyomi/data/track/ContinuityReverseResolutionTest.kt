package eu.kanade.tachiyomi.data.track

import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.source.MangaSource
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.entries.anime.interactor.NetworkToLocalAnime
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.entries.anime.repository.AnimeRepository
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import tachiyomi.domain.track.anime.repository.AnimeTrackRepository

class ContinuityReverseResolutionTest {
    private val source = mockk<MangaSource>()
    private val animeSource = mockk<AnimeCatalogueSource> {
        every { id } returns 2L
        every { getFilterList() } returns AnimeFilterList()
    }
    private val policy = mockk<ContinuitySourcePolicy> {
        every { privateAnime(any()) } returns false
        every { privateManga(any()) } returns false
        every { anime(any()) } answers { firstArg() }
    }
    private val persisted = InMemoryPreference("cache", null, "[]")
    private val store = mockk<PreferenceStore> { every { getString(any(), "[]") } returns persisted }
    private val identities = ContinuityIdentityCache(store)
    private val anime = Anime.create().copy(
        id = 99,
        source = 2,
        title = "Example adaptation",
        url = "/animation/example",
    )
    private val repository = mockk<AnimeRepository> {
        coEvery { getAnimeFavorites() } returns listOf(anime)
        coEvery { getWatchedAnimeNotInLibrary() } returns emptyList()
        coEvery { getAnimeByUrlAndSourceId(anime.url, anime.source) } returns anime
    }
    private val tracks = mockk<AnimeTrackRepository> {
        every { getAnimeTracksAsFlow() } returns flowOf(emptyList())
    }
    private val manager = mockk<AnimeSourceManager> {
        every { isInitialized } returns MutableStateFlow(true)
        every { getAll() } returns listOf(animeSource)
    }
    private val toLocal = mockk<NetworkToLocalAnime> { coEvery { await(any()) } returns anime }
    private val manga = Manga.create().copy(id = 1, source = 3, title = "Example work", url = "/work/example")

    private suspend fun catalogTest(block: suspend () -> Unit) {
        mockkObject(AniListMediaLookup, AdaptationChapterCatalog)
        try {
            coEvery { AniListMediaLookup.animeAdaptations(10) } returns listOf(
                AniListMediaLookup.AnimeRelation(
                    42, 24, "Example adaptation", "TV", 12, 2025, null, false,
                    titles = listOf("Example adaptation", "Example original title"),
                ),
            )
            coEvery { AdaptationChapterCatalog.mangaBaka(10) } returns AdaptationChapterCatalog.Metadata()
            block()
        } finally {
            unmockkObject(AniListMediaLookup, AdaptationChapterCatalog)
        }
    }

    @Test
    fun `a previously watched verified anime is available before chapter metadata`() = runBlocking {
        catalogTest {
            coEvery { repository.getAnimeFavorites() } returns emptyList()
            coEvery { repository.getWatchedAnimeNotInLibrary() } returns listOf(anime)
            identities.remember("anime", 2, anime.url, SourceTrackingHints(42, 24), false)
            val metadata = CompletableDeferred<AdaptationChapterCatalog.Metadata>()
            coEvery { AdaptationChapterCatalog.mangaBaka(10) } coAnswers { metadata.await() }
            val ready = CompletableDeferred<MangaAnimeContinuity.Result.Found>()
            val job = async {
                MangaAnimeContinuity(repository, tracks, manager, toLocal, identities, policy)
                    .resolve(manga, source, 10, null) {
                        if (it.choices.single().matches.isNotEmpty()) ready.complete(it)
                    }
            }
            assertEquals(listOf(99L), withTimeout(5_000) { ready.await() }.choices.single().matches.map { it.id })
            assertTrue(job.isActive)
            metadata.complete(AdaptationChapterCatalog.Metadata())
            assertTrue((job.await() as MangaAnimeContinuity.Result.Found).resolvingIds.isEmpty())
            coVerify(exactly = 0) { source.getMangaDetails(any()) }
            coVerify(exactly = 0) { animeSource.getSearchAnime(any(), any(), any()) }
        }
    }

    @Test
    fun `an original title alias recovers a source copy without accepting a different season`() = runBlocking {
        catalogTest {
            coEvery { repository.getAnimeFavorites() } returns emptyList()
            val wrong = SAnime.create().apply {
                title = "Example adaptation"
                url = "/animation/wrong-season"
                memo = Json.parseToJsonElement("""{"nyanime.tracking.v1":{"ids":{"anilist":43}}}""").jsonObject
            }
            val correct = SAnime.create().apply {
                title = "Example original title"
                url = anime.url
                memo = Json.parseToJsonElement("""{"nyanime.tracking.v1":{"ids":{"anilist":42}}}""").jsonObject
            }
            coEvery { animeSource.getSearchAnime(1, "Example adaptation", any()) } returns
                AnimesPage(listOf(wrong), false)
            coEvery { animeSource.getSearchAnime(1, "Example original title", any()) } returns
                AnimesPage(listOf(correct), false)
            val result = MangaAnimeContinuity(repository, tracks, manager, toLocal, identities, policy)
                .resolve(manga, source, 10, null) as MangaAnimeContinuity.Result.Found
            assertEquals(listOf(99L), result.choices.single().matches.map { it.id })
            coVerify(exactly = 1) { toLocal.await(match { it.url == anime.url }) }
            coVerify(exactly = 0) { toLocal.await(match { it.url == wrong.url }) }
            coVerify(exactly = 0) { animeSource.getAnimeDetails(any()) }
        }
    }

    @Test
    fun `removing an extension prevents opening its cached copy`() = runBlocking {
        catalogTest {
            identities.remember("anime", 2, anime.url, SourceTrackingHints(42, 24), false)
            every { manager.getAll() } returns emptyList()
            val result = MangaAnimeContinuity(repository, tracks, manager, toLocal, identities, policy)
                .resolve(manga, source, 10, null) as MangaAnimeContinuity.Result.Found
            assertTrue(result.choices.single().matches.isEmpty())
            assertTrue(result.resolvingIds.isEmpty())
            coVerify(exactly = 0) { toLocal.await(any()) }
        }
    }
}
