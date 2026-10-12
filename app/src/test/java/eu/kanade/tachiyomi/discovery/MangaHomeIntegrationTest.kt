package eu.kanade.tachiyomi.discovery

import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.manga.interactor.GetMangaIncognitoState
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.data.discovery.ExtensionHomeManifest
import eu.kanade.tachiyomi.data.discovery.MangaHomeFilters
import eu.kanade.tachiyomi.data.discovery.MangaHomeManifestReader
import eu.kanade.tachiyomi.data.discovery.MangaHomePresentation
import eu.kanade.tachiyomi.data.discovery.MangaHomeRegistry
import eu.kanade.tachiyomi.data.discovery.MangaHomeService
import eu.kanade.tachiyomi.extension.manga.MangaExtensionManager
import eu.kanade.tachiyomi.extension.manga.model.MangaExtension
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaHomeMetadata
import eu.kanade.tachiyomi.source.model.SMangaTrackingMetadata
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.discovery.SourceHomeRequest
import tachiyomi.domain.entries.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.source.manga.service.MangaSourceManager

class MangaHomeIntegrationTest {
    private val manager = mockk<MangaSourceManager>()
    private val extensions = mockk<MangaExtensionManager>()
    private val preferences = mockk<SourcePreferences>()
    private val uiPreferences = mockk<UiPreferences>()
    private val base = mockk<BasePreferences>()
    private val incognito = mockk<GetMangaIncognitoState>()
    private val reader = mockk<MangaHomeManifestReader>()
    private val engine = mockk<CatalogueSource>()
    private val extension = mockk<MangaExtension.Installed>()
    private val installed = MutableStateFlow(listOf(extension))
    private val toLocal = mockk<NetworkToLocalManga>()
    private val registry = MangaHomeRegistry(manager, extensions, preferences, base, incognito, reader, uiPreferences)
    private val service = MangaHomeService(registry, manager, toLocal)
    private val key = "test.extension:manga:42"

    @BeforeEach
    fun prepare() {
        every { manager.isInitialized } returns MutableStateFlow(true)
        every { extensions.installedExtensionsFlow } returns installed
        every { extension.pkgName } returns "test.extension"
        every { extension.versionCode } returns 1L
        every { extension.versionName } returns "1.4.1"
        every { extension.isNsfw } returns false
        every { extension.sources } returns listOf(engine)
        every { engine.id } returns 42L
        every { engine.lang } returns "it"
        every { engine.name } returns "TestSource"
        every { manager.get(42) } returns engine
        every { preferences.disabledMangaSources().get() } returns emptySet()
        every { preferences.enabledLanguages().get() } returns setOf("it")
        every { preferences.showNsfwSource().get() } returns true
        every { uiPreferences.showMangaInOtherLanguages().get() } returns false
        every { base.downloadedOnly().get() } returns false
        every { incognito.await(any()) } returns false
        every { reader.read(extension) } returns listOf(manifest())
        every { engine.getFilterList() } answers { filters() }
    }

    @Test
    fun sameMangaKeepsDistinctChapterCardsAndLibraryFlags() = runBlocking {
        val local = Manga.create().copy(id = 7, source = 42, url = "/series", title = "Personal title", favorite = true)
        coEvery { toLocal.await(any()) } returns local
        coEvery { engine.getSearchManga(1, "", any()) } returns MangasPage(
            listOf(remote("chapter-2"), remote("chapter-1"), remote("chapter-2")),
            false,
        )
        val page = service.fetch(key, SourceHomeRequest("trending"))
        assertEquals(listOf("chapter-2", "chapter-1"), page.items.map { it.presentation?.id })
        assertTrue(page.items.all { it.manga.id == 7L && it.manga.favorite && it.manga.title == "Personal title" })
        assertTrue(page.items.all { it.manga.thumbnailUrl == "https://images.test/volume.jpg" })
    }

    @Test
    fun trackingIdsArePreservedEvenWithoutHomePresentation() = runBlocking {
        val local = Manga.create().copy(id = 7, source = 42, url = "/series", title = "Personal title")
        coEvery { toLocal.await(any()) } returns local
        val remote = SManga.create().apply {
            url = "/series"
            title = "Source title"
            (this as SMangaTrackingMetadata).trackingMetadata = """{"ids":{"anilist":176740}}"""
        }
        coEvery { engine.getSearchManga(1, "", any()) } returns MangasPage(listOf(remote), false)

        val page = service.fetch(key, SourceHomeRequest("trending"))

        assertEquals(mapOf("anilist" to 176740L), page.items.single().presentation?.catalogIds)
    }

    @Test
    fun searchQueriesTheSourceCatalogueBeyondHomeRows() = runBlocking {
        val local = Manga.create().copy(id = 7, source = 42, url = "/series", title = "Search result")
        coEvery { toLocal.await(any()) } returns local
        coEvery { engine.getSearchManga(1, "search term", any()) } returns MangasPage(listOf(remote("found")), false)

        val page = service.fetch(key, SourceHomeRequest(SourceHomeRequest.SEARCH, query = "search term"))

        assertEquals(1, page.items.size)
        coVerify(exactly = 1) { engine.getSearchManga(1, "search term", any()) }
    }

    @Test
    fun removedDisabledUntrustedAndNotInitializedSourcesHaveNoHome() {
        assertEquals(1, registry.current().homes.size)
        every { preferences.disabledMangaSources().get() } returns setOf("42")
        assertTrue(registry.current().homes.isEmpty())
        every { preferences.disabledMangaSources().get() } returns emptySet()
        every { manager.get(42) } returns mockk()
        assertTrue(registry.current().homes.isEmpty())
        every { manager.get(42) } returns engine
        installed.value = emptyList()
        assertTrue(registry.current().homes.isEmpty())
        every { manager.isInitialized } returns MutableStateFlow(false)
        assertTrue(registry.current().loading)
        assertTrue(registry.current().homes.isEmpty())
    }

    @Test
    fun downloadedOnlyNeverFetchesTheNetwork() = runBlocking {
        every { base.downloadedOnly().get() } returns true
        assertTrue(runCatching { service.fetch(key, SourceHomeRequest("trending")) }.isFailure)
        coVerify(exactly = 0) { engine.getSearchManga(any(), any(), any()) }
    }

    @Test
    fun hiddenHomeDoesNotReclassifyStoredReadingAsTheDefaultCategory() {
        every { reader.read(extension) } returns listOf(manifest().copy(id = "sequential", title = "Sequential"))
        every { extension.isNsfw } returns true
        every { preferences.showNsfwSource().get() } returns false
        assertTrue(registry.current().homes.isEmpty())
        assertEquals(setOf("sequential"), registry.declaredHomeIds(42))
        installed.value = emptyList()
        assertTrue(registry.declaredHomeIds(42).isEmpty())
    }

    @Test
    fun disablingTheSourceDiscardsInFlightResultsBeforePersisting() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        coEvery { engine.getSearchManga(any(), any(), any()) } coAnswers {
            started.complete(Unit)
            resume.await()
            MangasPage(listOf(remote("chapter-1")), false)
        }
        val result = async { runCatching { service.fetch(key, SourceHomeRequest("trending")) } }
        started.await()
        installed.value = emptyList()
        resume.complete(Unit)
        assertTrue(result.await().isFailure)
        coVerify(exactly = 0) { toLocal.await(any()) }
    }

    @Test
    fun publicFiltersPreserveOrderLayoutsAndArchiveDestinations() {
        val choices = filters()
        val sections = MangaHomeFilters.sections(manifest(), choices)
        assertEquals(listOf("chapters", "updates", "ranking", "updates"), sections.map { it.layout })
        assertEquals(mapOf("Home" to "Archive"), sections[2].moreSelections)
        val fresh = filters()
        MangaHomeFilters.apply(fresh, sections[1])
        assertEquals("Updates", (fresh.first() as Filter.Select<*>).values[(fresh.first() as Filter.Select<*>).state])
        assertEquals(0, (choices.first() as Filter.Select<*>).state)
    }

    @Test
    fun presentationIsOptionalVersionedAndRejectsExternalChapterTargets() {
        assertNull(MangaHomePresentation.parse("""{"version":2}"""))
        assertNull(MangaHomePresentation.parse(" ".repeat(8193)))
        assertNull(MangaHomePresentation.from(object : SManga by SManga.create() {}))
        val metadata = MangaHomePresentation.parse(
            """{"chapters":[{"url":"https://other.test/read","label":"1"},
                {"url":"//other.test/read","label":"2"},{"url":"/read/3","label":"3"}],"rank":-1}""",
        )!!
        assertNull(metadata.rank)
        assertEquals(listOf("/read/3"), metadata.chapters.map { it.url })
    }

    private fun remote(id: String) = SManga.create().apply {
        url = "/series"
        title = "Source title"
        thumbnail_url = "https://images.test/volume.jpg"
        (this as SMangaHomeMetadata).homePresentation = """{"id":"$id","chapters":[{"url":"/$id","label":"$id"}]}"""
    }

    companion object {
        private fun filters() = FilterList(
            object : Filter.Select<String>("Home", arrayOf("Archive", "Trending", "Updates", "Monthly", "New")) {},
        )
        private fun manifest() = ExtensionHomeManifest(
            "manga",
            "Manga",
            ExtensionHomeManifest.Source("TestSource", "it"),
            sections = listOf(
                ExtensionHomeManifest.Section("trending", "Trending", mapOf("Home" to "Trending"), "chapters"),
                ExtensionHomeManifest.Section("updates", "Updates", mapOf("Home" to "Updates"), "updates"),
                ExtensionHomeManifest.Section(
                    "monthly",
                    "Monthly",
                    mapOf("Home" to "Monthly"),
                    "ranking",
                    moreFilters = mapOf("Home" to "Archive"),
                ),
                ExtensionHomeManifest.Section("new", "New", mapOf("Home" to "New"), "updates"),
            ),
            search = ExtensionHomeManifest.Section("search", "Archive", mapOf("Home" to "Archive")),
        )
    }
}
