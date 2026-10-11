package eu.kanade.domain.entries.manga.interactor

import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nyanime.panels.GuidedPanelOrder
import nyanime.panels.PanelDirection
import tachiyomi.domain.entries.manga.model.MangaUpdate
import tachiyomi.domain.entries.manga.repository.MangaRepository

class SetMangaViewerFlags(
    private val mangaRepository: MangaRepository,
) {
    private val changes = Mutex()

    suspend fun awaitSetReadingMode(id: Long, flag: Long) =
        updateFlags(id) { it.setFlag(flag, ReadingMode.MASK.toLong()) }

    suspend fun awaitSetOrientation(id: Long, flag: Long) =
        updateFlags(id) { it.setFlag(flag, ReaderOrientation.MASK.toLong()) }

    private fun Long.setFlag(flag: Long, mask: Long): Long {
        return this and mask.inv() or (flag and mask)
    }

    suspend fun awaitSetGuided(id: Long, enabled: Boolean) = updateFlags(id) { current ->
        val mask = nyanime.panels.GUIDED_READING_FLAG
        val flags = if (enabled) initializeOrder(current) else current
        flags.setFlag(if (enabled) mask else 0, mask)
    }

    suspend fun awaitInitializeGuidedOrder(id: Long) = updateFlags(id, ::initializeOrder)

    suspend fun awaitSetGuidedOrder(id: Long, direction: PanelDirection) =
        updateFlags(id) { GuidedPanelOrder.set(it, direction) }

    private fun initializeOrder(flags: Long) = GuidedPanelOrder.initialize(
        flags,
        flags and ReadingMode.MASK.toLong() == ReadingMode.LEFT_TO_RIGHT.flagValue.toLong(),
    )

    private suspend fun updateFlags(id: Long, transform: (Long) -> Long) {
        changes.withLock {
            val manga = mangaRepository.getMangaById(id)
            val flags = transform(manga.viewerFlags)
            if (flags != manga.viewerFlags) mangaRepository.updateManga(MangaUpdate(id = id, viewerFlags = flags))
        }
    }
}
