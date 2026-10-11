package eu.kanade.tachiyomi.discovery

import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.anime.interactor.GetAnimeIncognitoState
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.data.discovery.DiscoverySourceService
import eu.kanade.tachiyomi.data.discovery.EpisodeEndingCueStore
import eu.kanade.tachiyomi.data.discovery.LocalHomeItem
import eu.kanade.tachiyomi.data.discovery.LocalHomeSectionProvider
import eu.kanade.tachiyomi.data.discovery.ResumeEpisodeSelection
import eu.kanade.tachiyomi.data.discovery.ResumeEpisodeSelector
import eu.kanade.tachiyomi.data.discovery.ResumeVisibility
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.entries.anime.interactor.GetAnime
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.entries.anime.model.asAnimeCover
import tachiyomi.domain.history.anime.interactor.GetAnimeHistory
import tachiyomi.domain.history.anime.model.AnimeHistoryWithRelations
import tachiyomi.domain.items.episode.model.Episode
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import java.util.Date

class LocalHomeResumeStartupTest {
    private val anime = Anime.create().copy(id = 7, source = 42, title = "Test title")
    private val episode = Episode.create().copy(id = 9, animeId = 7, lastSecondSeen = 24_000, totalSeconds = 1_200_000)
    private val storedHistory =
        listOf(AnimeHistoryWithRelations(1, 9, 7, anime.title, 3.0, Date(1234), anime.asAnimeCover()))
    private val history = MutableStateFlow(storedHistory)
    private val sources = MutableStateFlow(emptyList<AnimeSource>())
    private val hidden = MutableStateFlow(emptySet<String>())
    private val source = mockk<AnimeSource>()
    private val provider = LocalHomeSectionProvider(
        history = mockk<GetAnimeHistory> { every { subscribe("") } returns history },
        resumeSelector = mockk<ResumeEpisodeSelector> {
            coEvery { select(7, 9, any()) } returns ResumeEpisodeSelection(episode)
        },
        updates = mockk(),
        getAnime = mockk<GetAnime> { coEvery { await(7) } returns anime },
        getEpisode = mockk(),
        base = mockk<BasePreferences> {
            every { incognitoMode() } returns preference(false)
            every { downloadedOnly() } returns preference(false)
        },
        uiPreferences = mockk<UiPreferences>(),
        incognito = mockk<GetAnimeIncognitoState> { every { await(42) } returns false },
        preferences = mockk<SourcePreferences> {
            every { disabledAnimeSources() } returns preference(emptySet<String>())
            every { enabledLanguages() } returns preference(setOf("it"))
            every { incognitoAnimeExtensions() } returns preference(emptySet<String>())
            every { showNsfwSource() } returns preference(true)
        },
        sources = mockk<AnimeSourceManager> {
            every { this@mockk.sources } returns this@LocalHomeResumeStartupTest.sources
            every { this@mockk.get(42L) } answers { this@LocalHomeResumeStartupTest.sources.value.firstOrNull() }
        },
        downloads = mockk(),
        sourceService = mockk<DiscoverySourceService> { every { isEnabled(source) } returns true },
        endingCues = mockk<EpisodeEndingCueStore> { every { observeChanges() } returns flowOf(0L) },
        resume = true,
        visibility = mockk<ResumeVisibility> {
            every { this@mockk.hidden } returns preference(this@LocalHomeResumeStartupTest.hidden)
        },
    )

    @Test
    fun animeResumeRecoversAfterLateRegistrationAndExtensionReplacement() = runBlocking {
        val changes = Channel<List<LocalHomeItem>>(Channel.UNLIMITED)
        val collection = launch { provider.observe().collect { changes.send(it.data.orEmpty()) } }
        try {
            withTimeout(5_000) {
                assertTrue(changes.receive().isEmpty())
                sources.value = listOf(source)
                assertEquals(episode, changes.receive().single().episode)
                sources.value = emptyList()
                assertTrue(changes.receive().isEmpty())
                sources.value = listOf(source)
                assertEquals(24_000L, changes.receive().single().episode.lastSecondSeen)
                assertEquals(storedHistory, history.value)
            }
        } finally {
            collection.cancel()
        }
    }

    @Test
    fun manuallyHiddenAnimeRemainsHiddenWhenExtensionsLoad() = runBlocking {
        hidden.value = setOf("7")
        val changes = Channel<List<LocalHomeItem>>(Channel.UNLIMITED)
        val collection = launch { provider.observe().collect { changes.send(it.data.orEmpty()) } }
        try {
            withTimeout(5_000) {
                assertTrue(changes.receive().isEmpty())
                sources.value = listOf(source)
                assertTrue(changes.receive().isEmpty())
                hidden.value = emptySet()
                assertEquals(episode, changes.receive().single().episode)
                assertEquals(storedHistory, history.value)
            }
        } finally {
            collection.cancel()
        }
    }

    private fun <T> preference(value: T): Preference<T> = preference(MutableStateFlow(value))

    private fun <T> preference(state: MutableStateFlow<T>): Preference<T> = mockk {
        every { changes() } returns state
        every { get() } answers { state.value }
    }
}
