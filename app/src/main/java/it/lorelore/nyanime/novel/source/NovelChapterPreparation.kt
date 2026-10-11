package it.lorelore.nyanime.novel.source

import it.lorelore.nyanime.source.novel.NovelChapter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Host capability; ordinary extension interfaces and their timeout policy remain unchanged. */
internal interface NovelChapterPreparation {
    fun observePreparation(reference: String): Flow<NovelPreparationProgress>

    suspend fun preparedChapters(reference: String, force: Boolean = false): List<NovelChapter>

    suspend fun pausePreparation(reference: String)
}

internal enum class NovelPreparationPhase {
    WAITING,
    RUNNING,
    PAUSED,
    READY,
    FAILED,
}

internal data class NovelPreparationProgress(
    val phase: NovelPreparationPhase = NovelPreparationPhase.WAITING,
    val pages: Int = 0,
    val found: Int = 0,
    val readyAvailable: Boolean = false,
) {
    init {
        require(pages >= 0 && found >= 0)
    }
}

/** A slow title never owns a global network mutex; leases leave no permanent map entry. */
internal class NovelPreparationLocks {
    private class Entry(val mutex: Mutex = Mutex(), var references: Int = 0)

    private val entries = mutableMapOf<String, Entry>()

    suspend fun <T> withKey(key: String, block: suspend () -> T): T {
        val entry =
            synchronized(entries) { entries.getOrPut(key) { Entry() }.also { it.references++ } }
        return try {
            entry.mutex.withLock { block() }
        } finally {
            synchronized(entries) {
                if (--entry.references == 0) check(entries.remove(key) === entry)
            }
        }
    }

    internal fun retainedKeys(): Int = synchronized(entries) { entries.size }
}
