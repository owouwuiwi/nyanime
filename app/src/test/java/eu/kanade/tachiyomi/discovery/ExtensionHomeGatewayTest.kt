package eu.kanade.tachiyomi.discovery

import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.anime.interactor.GetAnimeIncognitoState
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.data.discovery.DiscoverySourceService
import eu.kanade.tachiyomi.data.discovery.ExtensionHomeGateway
import eu.kanade.tachiyomi.data.discovery.ExtensionHomeManifestReader
import eu.kanade.tachiyomi.data.discovery.ExtensionHomeRegistry
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import eu.kanade.tachiyomi.extension.anime.model.AnimeExtension
import eu.kanade.tachiyomi.ui.discovery.DiscoveryHomeAvailability
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.discovery.SourceHomePresentation
import tachiyomi.domain.discovery.SourceHomeRequest
import tachiyomi.domain.discovery.homePresentation
import tachiyomi.domain.entries.anime.interactor.NetworkToLocalAnime
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.source.anime.service.AnimeSourceManager

class ExtensionHomeGatewayTest {
    @Test
    fun freshHomeCoverReplacesMissingOrStaleLocalArtworkWithoutChangingLibraryData() = runBlocking {
        val remote = SAnime.create().apply {
            title = "Source title"
            url = "/series"
            thumbnail_url = "https://images.test/current.jpg"
        }
        for (oldCover in listOf(null, "", "https://old-images.test/old.jpg")) {
            val local = Anime.create().copy(
                id = 123,
                source = 42,
                url = remote.url,
                title = "Custom title",
                favorite = true,
                thumbnailUrl = oldCover,
                coverLastModified = 456,
                episodeFlags = 81,
            )
            coEvery { toLocal.await(any()) } returns local
            coEvery { engine.getSearchAnime(1, "", any()) } returns AnimesPage(listOf(remote), false)
            val actual = gateway.fetch(gateway.currentAccess(), SourceHomeRequest("popular")).items.single()
            assertEquals(local.copy(thumbnailUrl = remote.thumbnail_url), actual)
        }
    }

    @Test
    fun homeRowsWithoutArtworkKeepTheExistingCoverAndBackground() = runBlocking {
        val local = Anime.create().copy(
            id = 123,
            source = 42,
            url = "/series",
            title = "Local title",
            thumbnailUrl = "https://images.test/saved.jpg",
            backgroundUrl = "https://images.test/background.jpg",
        )
        for (missing in listOf(null, "", "  ")) {
            val remote = SAnime.create().apply {
                title = "Source title"
                url = local.url
                thumbnail_url = missing
                background_url = missing
            }
            coEvery { toLocal.await(any()) } returns local
            coEvery { engine.getSearchAnime(1, "", any()) } returns AnimesPage(listOf(remote), false)
            assertEquals(local, gateway.fetch(gateway.currentAccess(), SourceHomeRequest("popular")).items.single())
        }
    }

    @Test fun calendarRequestPassesTheDeclaredPublicDateWithoutChangingTheQuery() = runBlocking {
        val manifest = ExtensionHomeFiltersTest.manifest()
        every { reader.read(extension) } returns
            listOf(manifest.copy(sections = manifest.sections.map { it.copy(dateFilter = "Date") }))
        every { engine.getFilterList() } answers {
            eu.kanade.tachiyomi.animesource.model.AnimeFilterList(
                ExtensionHomeFiltersTest.filters() +
                    object : eu.kanade.tachiyomi.animesource.model.AnimeFilter.Text("Date") {},
            )
        }
        coEvery { engine.getSearchAnime(1, "", any()) } answers {
            val filters = thirdArg<eu.kanade.tachiyomi.animesource.model.AnimeFilterList>()
            assertEquals(
                "2026-09-12",
                filters.filterIsInstance<eu.kanade.tachiyomi.animesource.model.AnimeFilter.Text>().single().state,
            )
            AnimesPage(emptyList(), false)
        }
        val result = gateway.fetch(gateway.currentAccess(), SourceHomeRequest("popular", date = "2026-09-12"))
        assertTrue(result.items.isEmpty())
        coVerify(exactly = 1) { engine.getSearchAnime(1, "", any()) }
    }

    @Test
    fun episodeCardsKeepOneLibraryIdentityAndNeverPersistPresentationInTheLibrary() = runBlocking {
        val local = Anime.create().copy(
            id = 123,
            source = 42,
            url = "/series",
            title = "Custom",
            favorite = true,
            episodeFlags = 81,
        )
        fun remote(episode: String) = SAnime.create().apply {
            title = "Source title"
            url = "/series"
            memo =
                SourceHomePresentation(
                    id = episode,
                    badges = listOf(episode),
                    sectionTitle = "Recent episodes",
                ).attachTo(memo)
        }
        coEvery { toLocal.await(any()) } answers {
            assertNull(firstArg<Anime>().homePresentation)
            local
        }
        coEvery { engine.getSearchAnime(1, "", any()) } returns
            AnimesPage(listOf(remote("ep-9"), remote("ep-8"), remote("ep-9")), false)
        val page = gateway.fetch(gateway.currentAccess(), SourceHomeRequest("popular"))
        assertEquals(listOf("ep-9", "ep-8"), page.items.map { it.homePresentation?.id })
        assertTrue(page.items.all { it.id == 123L && it.favorite && it.episodeFlags == 81L && it.title == "Custom" })
        assertEquals("Recent episodes", page.title)
    }

    private val manager = mockk<AnimeSourceManager>()
    private val extensions = mockk<AnimeExtensionManager>()
    private val visibility = mockk<DiscoverySourceService>()
    private val preferences = mockk<SourcePreferences>()
    private val base = mockk<BasePreferences>()
    private val incognito = mockk<GetAnimeIncognitoState>()
    private val toLocal = mockk<NetworkToLocalAnime>()
    private val engine = mockk<AnimeSource>()
    private val extension = mockk<AnimeExtension.Installed>()
    private val installed = MutableStateFlow(listOf(extension))
    private val reader = mockk<ExtensionHomeManifestReader>()
    private val registry = ExtensionHomeRegistry(manager, extensions, visibility, preferences, base, incognito, reader)
    private val homeKey = "test.extension:cartoons:42"
    private val gateway = ExtensionHomeGateway(homeKey, registry, manager, toLocal, Semaphore(3))

    @BeforeEach
    fun prepare() {
        every { manager.isInitialized } returns MutableStateFlow(true)
        every { extensions.installedExtensionsFlow } returns installed
        every { extension.pkgName } returns "test.extension"
        every { extension.versionCode } returns 1L
        every { extension.versionName } returns "16.1"
        every { extension.sources } returns listOf(engine)
        every { engine.id } returns 42L
        every { engine.lang } returns "it"
        every { engine.name } returns "TestSource"
        every { reader.read(extension) } returns listOf(ExtensionHomeFiltersTest.manifest())
        every { engine.getFilterList() } answers { ExtensionHomeFiltersTest.filters() }
        every { manager.get(42) } returns engine
        every { visibility.isEnabled(engine) } returns true
        every { base.downloadedOnly().get() } returns false
        every { incognito.await(any()) } returns false
    }

    @Test
    fun matchingUsesInstalledPackageNotDisplayNameOrUntrustedEntries() {
        assertEquals(42L, gateway.currentAccess().source?.id)
        every { extension.pkgName } returns "another.extension"
        assertNull(gateway.currentAccess().source)
        installed.value = emptyList()
        assertNull(gateway.currentAccess().source)
    }

    @Test
    fun installationAndRemovalUpdateHomeAvailabilityWithoutFetchingTheCatalogue() = runBlocking {
        every { manager.sources } returns flowOf(listOf(engine))
        every { preferences.disabledAnimeSources().changes() } returns flowOf(emptySet())
        every { preferences.enabledLanguages().changes() } returns flowOf(setOf("it"))
        every { preferences.showNsfwSource().changes() } returns flowOf(true)
        every { base.downloadedOnly().changes() } returns flowOf(false)
        every { base.incognitoMode().changes() } returns flowOf(false)
        every { preferences.incognitoAnimeExtensions().changes() } returns flowOf(emptySet())
        installed.value = emptyList()
        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        val observed = mutableListOf<DiscoveryHomeAvailability>()
        withTimeout(5_000) {
            val job = launch {
                gateway.observeAccess().map { DiscoveryHomeAvailability.from(it) }.take(3).collect {
                    observed += it
                    if (observed.size == 1) first.complete(Unit)
                    if (observed.size == 2) second.complete(Unit)
                }
            }
            first.await()
            installed.value = listOf(extension)
            second.await()
            installed.value = emptyList()
            job.join()
        }
        assertEquals(listOf(false, true, false), observed.map { it.homes.isNotEmpty() })
        coVerify(exactly = 0) { engine.getSearchAnime(any(), any(), any()) }
    }

    @Test
    fun pendingInitializationDoesNotAdvertiseARegisteredButNotReadySource() {
        every { manager.isInitialized } returns MutableStateFlow(false)
        val availability = DiscoveryHomeAvailability.from(gateway.currentAccess())
        assertTrue(availability.loading)
        assertTrue(availability.homes.isEmpty())
    }

    @Test
    fun hiddenSourceOrDisabledLanguageCannotAppear() {
        every { visibility.isEnabled(engine) } returns false
        assertNull(gateway.currentAccess().source)
    }

    @Test
    fun ambiguousSourceIdentityIsNeverGuessed() {
        every { extension.sources } returns listOf(engine, engine)
        assertNull(gateway.currentAccess().source)
    }

    @Test
    fun accessReflectsIncognitoAndDownloadOnly() {
        every { incognito.await(42L) } returns true
        every { base.downloadedOnly().get() } returns true
        assertTrue(gateway.currentAccess().isPrivate)
        assertTrue(gateway.currentAccess().offline)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { gateway.fetch(gateway.currentAccess(), SourceHomeRequest("popular")) }
        }
        coVerify(exactly = 0) { engine.getSearchAnime(any(), any(), any()) }
    }

    @Test
    fun categoriesPaginationAndSearchUseExtensionApiAndPreserveLocalIdentity() = runBlocking {
        val sourceAnime = SAnime.create().apply {
            title = "Cartone"
            url = "/cartoni/index.php?cartone=test"
        }
        val local = Anime.create().copy(id = 123, source = 42, url = sourceAnime.url, title = sourceAnime.title)
        coEvery { toLocal.await(any()) } returns local
        coEvery { engine.getSearchAnime(2, "test", any()) } answers {
            assertEquals("Categoria A", ExtensionHomeFiltersTest.values(thirdArg())["Categoria"])
            AnimesPage(listOf(sourceAnime), false)
        }
        val page = gateway.fetch(gateway.currentAccess(), SourceHomeRequest("category:Categoria A", 2, " test "))
        assertEquals(local, page.items.single())
        assertEquals(123L, page.items.single().id)
        assertEquals(false, page.hasNextPage)
    }
}
