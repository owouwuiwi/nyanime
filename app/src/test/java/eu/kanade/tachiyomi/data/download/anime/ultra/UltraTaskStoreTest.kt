package eu.kanade.tachiyomi.data.download.anime.ultra

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class UltraTaskStoreTest {
    @TempDir lateinit var directory: File
    private val task =
        UltraTask("content://downloads/episode", "Series · Episode", 10, 11, UltraPhase.RUNNING, workId = "new")

    @Test
    fun `old worker cannot overwrite a newer attempt or a user pause`() = runBlocking {
        val store = UltraTaskStore(File(directory, "tasks.json"))
        store.change(task.key) { task }
        store.updateWorker(task.key, "old") { it.copy(phase = UltraPhase.READY) }
        assertEquals(UltraPhase.RUNNING, store.tasks.value.getValue(task.key).phase)
        store.change(task.key) { it!!.copy(phase = UltraPhase.PAUSED, progress = 42) }
        store.updateWorker(task.key, "new") { it.copy(phase = UltraPhase.RUNNING, progress = 0) }
        assertEquals(UltraPhase.PAUSED, store.tasks.value.getValue(task.key).phase)
        assertEquals(42, store.tasks.value.getValue(task.key).progress)
    }

    @Test
    fun `verified publication wins a late pause but never overwrites a newer attempt`() = runBlocking {
        val store = UltraTaskStore(File(directory, "tasks.json"))
        store.change(task.key) { task.copy(phase = UltraPhase.PAUSED) }
        store.recordPublished(task.key, "old")
        assertEquals(UltraPhase.PAUSED, store.tasks.value.getValue(task.key).phase)
        store.recordPublished(task.key, "new")
        assertEquals(UltraPhase.READY, store.tasks.value.getValue(task.key).phase)
        assertEquals(100, store.tasks.value.getValue(task.key).progress)
    }

    @Test
    fun `progress and removals survive a new process without an unfinished write`() = runBlocking {
        val file = File(directory, "tasks.json")
        val store = UltraTaskStore(file)
        store.change(task.key) { task.copy(progress = 52, coolingRequired = true, coolingUntil = 123_000) }
        File(directory, "tasks.json.new").writeText("incomplete")
        val restored = UltraTaskStore(file)
        restored.load()
        assertEquals(52, restored.tasks.value.getValue(task.key).progress)
        assertTrue(restored.tasks.value.getValue(task.key).coolingRequired)
        assertEquals(123_000L, restored.tasks.value.getValue(task.key).coolingUntil)
        restored.change(task.key) { null }
        val empty = UltraTaskStore(file)
        empty.load()
        assertTrue(empty.tasks.value.isEmpty())
    }

    @Test
    fun `concurrent episode updates do not erase each other`() = runBlocking {
        val store = UltraTaskStore(File(directory, "tasks.json"))
        (0 until 16).map { index ->
            async {
                val next = task.copy(folder = "content://downloads/$index", episodeId = index.toLong())
                store.change(next.key) { next }
            }
        }.awaitAll()
        val restored = UltraTaskStore(File(directory, "tasks.json"))
        restored.load()
        assertEquals(16, restored.tasks.value.size)
    }
}
