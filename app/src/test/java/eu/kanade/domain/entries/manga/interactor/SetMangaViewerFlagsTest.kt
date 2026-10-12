package eu.kanade.domain.entries.manga.interactor

import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import nyanime.panels.GUIDED_READING_FLAG
import nyanime.panels.GuidedPanelOrder
import nyanime.panels.PanelDirection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.entries.manga.model.MangaUpdate
import tachiyomi.domain.entries.manga.repository.MangaRepository

class SetMangaViewerFlagsTest {
    @Test fun overlappingOrderScrollingAndOrientationChangesDoNotOverwriteEachOther() = runTest {
        val repository = mockk<MangaRepository>()
        var title = Manga.create().copy(id = 42, viewerFlags = GUIDED_READING_FLAG)
        coEvery { repository.getMangaById(42) } coAnswers {
            val snapshot = title
            delay(1)
            snapshot
        }
        coEvery { repository.updateManga(any()) } coAnswers {
            val update = firstArg<MangaUpdate>()
            delay(1)
            title = title.copy(viewerFlags = requireNotNull(update.viewerFlags))
            true
        }
        val setter = SetMangaViewerFlags(repository)
        listOf(
            launch { setter.awaitSetGuidedOrder(42, PanelDirection.RTL) },
            launch { setter.awaitSetReadingMode(42, ReadingMode.VERTICAL.flagValue.toLong()) },
            launch { setter.awaitSetOrientation(42, ReaderOrientation.LANDSCAPE.flagValue.toLong()) },
        ).joinAll()
        assertEquals(PanelDirection.RTL, GuidedPanelOrder.stored(title.viewerFlags))
        assertEquals(ReadingMode.VERTICAL.flagValue.toLong(), title.viewerFlags and ReadingMode.MASK.toLong())
        assertEquals(
            ReaderOrientation.LANDSCAPE.flagValue.toLong(),
            title.viewerFlags and ReaderOrientation.MASK.toLong(),
        )
        assertEquals(GUIDED_READING_FLAG, title.viewerFlags and GUIDED_READING_FLAG)
    }

    @Test fun enablingAgainAndLegacyInitializationKeepAnExplicitPanelOrder() = runTest {
        val repository = mockk<MangaRepository>()
        var title = Manga.create().copy(id = 42, viewerFlags = ReadingMode.LEFT_TO_RIGHT.flagValue.toLong())
        coEvery { repository.getMangaById(42) } coAnswers { title }
        coEvery { repository.updateManga(any()) } coAnswers {
            title = title.copy(viewerFlags = requireNotNull(firstArg<MangaUpdate>().viewerFlags))
            true
        }
        val setter = SetMangaViewerFlags(repository)
        setter.awaitSetGuided(42, true)
        assertEquals(PanelDirection.LTR, GuidedPanelOrder.stored(title.viewerFlags))
        setter.awaitSetGuidedOrder(42, PanelDirection.RTL)
        setter.awaitSetGuided(42, false)
        setter.awaitSetGuided(42, true)
        setter.awaitInitializeGuidedOrder(42)
        assertEquals(PanelDirection.RTL, GuidedPanelOrder.stored(title.viewerFlags))
        coVerify(exactly = 4) { repository.updateManga(any()) }
    }
}
