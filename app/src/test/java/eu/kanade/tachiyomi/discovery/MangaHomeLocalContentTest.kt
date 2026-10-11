package eu.kanade.tachiyomi.discovery

import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.manga.interactor.GetMangaIncognitoState
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.data.discovery.MangaHomeLocalContent
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.online.HttpSource
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.entries.manga.model.MangaCover
import tachiyomi.domain.history.manga.model.MangaHistoryWithRelations
import tachiyomi.domain.source.manga.model.StubMangaSource
import tachiyomi.domain.source.manga.service.MangaSourceManager
import tachiyomi.domain.updates.manga.model.MangaUpdatesWithRelations
import java.util.Date

@OptIn(ExperimentalCoroutinesApi::class)
class MangaHomeLocalContentTest {
    private val ready = MutableStateFlow(false)
    private val sources = MutableStateFlow(emptyList<CatalogueSource>())
    private val disabled = MutableStateFlow(emptySet<String>())
    private val languages = MutableStateFlow(setOf("it"))
    private val privateMode = MutableStateFlow(false)
    private val privateExtensions = MutableStateFlow(emptySet<String>())
    private val otherLanguages = MutableStateFlow(false)
    private val dismissed = MutableStateFlow(emptySet<String>())
    private val source = mockk<CatalogueSource> {
        every { id } returns 42L
        every { lang } returns "it"
    }
    private val manager = object : MangaSourceManager {
        override val isInitialized = ready
        override val catalogueSources: Flow<List<CatalogueSource>> = sources
        override fun get(sourceKey: Long) = sources.value.firstOrNull { it.id == sourceKey }
        override fun getOrStub(sourceKey: Long) = checkNotNull(get(sourceKey))
        override fun getOnlineSources(): List<HttpSource> = emptyList()
        override fun getCatalogueSources(): List<CatalogueSource> = sources.value
        override fun getStubSources(): List<StubMangaSource> = emptyList()
    }
    private val preferences = mockk<SourcePreferences> {
        every { disabledMangaSources() } returns preference(disabled)
        every { enabledLanguages() } returns preference(languages)
        every { incognitoMangaExtensions() } returns preference(privateExtensions)
    }
    private val base = mockk<BasePreferences> {
        every { incognitoMode() } returns preference(privateMode)
    }
    private val incognito = mockk<GetMangaIncognitoState> {
        every { await(any()) } answers { firstArg<Long?>().toString() in privateExtensions.value }
    }
    private val ui = mockk<UiPreferences> {
        every { showMangaInOtherLanguages() } returns preference(otherLanguages)
        every { dismissedLibraryUpdates() } returns preference(dismissed)
    }
    private val content = MangaHomeLocalContent(manager, preferences, base, incognito, ui)
    private val cover = MangaCover(7, 42, true, null, 0)
    private val storedHistory = listOf(MangaHistoryWithRelations(1, 9, 7, "Test title", 3.0, Date(1234), 90, cover))
    private val history = MutableStateFlow(storedHistory)

    @Test
    fun historyLoadedBeforeExtensionsReappearsWithoutReadingAgain() = runTest {
        val visible = mutableListOf<List<MangaHistoryWithRelations>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { content.history(history).toList(visible) }
        runCurrent()
        assertTrue(visible.last().isEmpty())

        sources.value = listOf(source)
        ready.value = true
        runCurrent()

        assertEquals(storedHistory, visible.last())
        assertEquals(storedHistory, history.value)
    }

    @Test
    fun lateExtensionRegistrationAfterInitializationRestoresExistingHistory() = runTest {
        ready.value = true
        val visible = mutableListOf<List<MangaHistoryWithRelations>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { content.history(history).toList(visible) }
        runCurrent()
        assertTrue(visible.last().isEmpty())

        sources.value = listOf(source)
        runCurrent()
        assertEquals(storedHistory, visible.last())
    }

    @Test
    fun extensionReplacementDoesNotEraseHistoryAndRestoresResume() = runTest {
        sources.value = listOf(source)
        ready.value = true
        val visible = mutableListOf<List<MangaHistoryWithRelations>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { content.history(history).toList(visible) }
        runCurrent()
        assertEquals(storedHistory, visible.last())

        sources.value = emptyList()
        runCurrent()
        assertTrue(visible.last().isEmpty())
        sources.value = listOf(source)
        runCurrent()
        assertEquals(storedHistory, visible.last())
        assertEquals(storedHistory, history.value)
    }

    @Test
    fun newProcessRestoresSameChapterEvenIfItsExtensionsInitializeLater() = runTest {
        sources.value = listOf(source)
        ready.value = true
        val before = mutableListOf<List<MangaHistoryWithRelations>>()
        val firstProcess = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            content.history(history).toList(before)
        }
        runCurrent()
        firstProcess.cancel()

        ready.value = false
        sources.value = emptyList()
        val after = mutableListOf<List<MangaHistoryWithRelations>>()
        val restarted = MangaHomeLocalContent(manager, preferences, base, incognito, ui)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { restarted.history(history).toList(after) }
        runCurrent()
        sources.value = listOf(source)
        ready.value = true
        runCurrent()

        assertEquals(before.last(), after.last())
        assertEquals(9L, after.last().single().chapterId)
    }

    @Test
    fun userVisibilityAndIncognitoChoicesStillApplyWithoutDeletingHistory() = runTest {
        sources.value = listOf(source)
        ready.value = true
        val visible = mutableListOf<List<MangaHistoryWithRelations>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { content.history(history).toList(visible) }
        runCurrent()
        disabled.value = setOf("42")
        runCurrent()
        assertTrue(visible.last().isEmpty())
        disabled.value = emptySet()
        languages.value = setOf("en")
        runCurrent()
        assertTrue(visible.last().isEmpty())
        languages.value = setOf("it")
        privateMode.value = true
        runCurrent()
        assertTrue(visible.last().isEmpty())
        privateMode.value = false
        privateExtensions.value = setOf("42")
        runCurrent()
        assertTrue(visible.last().isEmpty())
        privateExtensions.value = emptySet()
        runCurrent()
        assertEquals(storedHistory, visible.last())
        assertEquals(storedHistory, history.value)
    }

    @Test
    fun sourceReplacementReevaluatesLanguageWithoutADatabaseChange() = runTest {
        ready.value = true
        sources.value = listOf(source)
        val visible = mutableListOf<List<MangaHistoryWithRelations>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { content.history(history).toList(visible) }
        runCurrent()
        val english = mockk<CatalogueSource> {
            every { id } returns 42L
            every { lang } returns "en"
        }
        sources.value = listOf(english)
        runCurrent()
        assertTrue(visible.last().isEmpty())
        languages.value = setOf("it", "en")
        otherLanguages.value = true
        runCurrent()
        assertEquals(storedHistory, visible.last())
    }

    @Test
    fun existingUpdatesAlsoRecoverAfterDelayedExtensionLoading() = runTest {
        val stored = MangaUpdatesWithRelations(7, "Test title", 9, "Chapter 3", null, false, false, 8, 42, 1234, cover)
        val updates = MutableStateFlow(listOf(stored))
        val visible = mutableListOf<List<MangaUpdatesWithRelations>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { content.updates(updates).toList(visible) }
        runCurrent()
        assertTrue(visible.last().isEmpty())
        sources.value = listOf(source)
        ready.value = true
        runCurrent()
        assertEquals(listOf(stored), visible.last())
        assertEquals(8L, visible.last().single().lastPageRead)
    }

    @Test
    fun categorySelectionCanPrecedeTheRowLimitWithoutLosingOlderReading() = runTest {
        val second = mockk<CatalogueSource> {
            every { id } returns 43L
            every { lang } returns "it"
        }
        sources.value = listOf(source, second)
        ready.value = true
        history.value = (1L..25L).map { id ->
            storedHistory.single().copy(
                id = id,
                mangaId = id,
                coverData = cover.copy(
                    sourceId = if (id >
                        20
                    ) {
                        43
                    } else {
                        42
                    },
                ),
            )
        }
        val standard = mutableListOf<List<MangaHistoryWithRelations>>()
        val scoped = mutableListOf<List<MangaHistoryWithRelations>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { content.history(history).toList(standard) }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            content.history(history, limit = Int.MAX_VALUE)
                .map { rows -> rows.filter { it.coverData.sourceId == 43L }.take(20) }.toList(scoped)
        }
        runCurrent()
        assertEquals(20, standard.last().size)
        assertEquals((21L..25L).toList(), scoped.last().map { it.mangaId })
        assertEquals(25, history.value.size)
    }

    private fun <T> preference(state: MutableStateFlow<T>): Preference<T> = mockk {
        every { changes() } returns state as Flow<T>
        every { get() } answers { state.value }
    }
}
