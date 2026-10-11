package eu.kanade.tachiyomi.data.download.anime.ultra

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.download.service.DownloadPreferences
import java.io.File
import java.io.IOException

class UltraSchedulingTest {
    @TempDir lateinit var directory: File
    private val task = UltraTask(
        "content://downloads/episode",
        "Episode",
        phase = UltraPhase.WAITING,
        workId = "old",
        progress = 42,
        coolingRequired = true,
        message = UltraProcessingPolicy.COOLING,
    )

    @Test
    fun `defaults allow screen on and battery while explicit restrictions remain selectable`() {
        val store = InMemoryPreferenceStore()
        val preferences = DownloadPreferences(store)
        assertFalse(preferences.ultraOnlyWhileCharging().get())
        assertFalse(preferences.ultraOnlyWhileScreenOff().get())
        val saved = InMemoryPreferenceStore(
            sequenceOf(
                InMemoryPreferenceStore.InMemoryPreference("ultra_only_charging", true, true),
                InMemoryPreferenceStore.InMemoryPreference("ultra_only_screen_off", true, true),
            ),
        )
        assertTrue(DownloadPreferences(saved).ultraOnlyWhileCharging().get())
        assertTrue(DownloadPreferences(saved).ultraOnlyWhileScreenOff().get())
    }

    @Test
    fun `upgrade refreshes old waiting constraints but leaves paused completed and running work alone`() {
        assertTrue(UltraScheduling.needsRefresh(task, "old", emptySet(), false, false))
        assertFalse(UltraScheduling.needsRefresh(task, "old", setOf(UltraScheduling.POLICY_TAG), false, false))
        assertFalse(UltraScheduling.needsRefresh(task, "old", emptySet(), true, false))
        assertFalse(UltraScheduling.needsRefresh(task, "old", emptySet(), false, true))
        assertFalse(UltraScheduling.needsRefresh(task, "obsolete", emptySet(), false, false))
        for (phase in listOf(UltraPhase.PAUSED, UltraPhase.CANCELLED, UltraPhase.READY, UltraPhase.FAILED)) {
            assertFalse(UltraScheduling.needsRefresh(task.copy(phase = phase), "old", emptySet(), false, false))
        }
    }

    @Test
    fun `repeated resource waits preserve progress without increasing the check interval`() = runBlocking {
        val store = UltraTaskStore(File(directory, "tasks.json"))
        store.change(task.key) { task }
        var previous = "old"
        repeat(100) { attempt ->
            val next = "next-$attempt"
            UltraScheduling.handOff(store, task.key, previous, next) {
                assertEquals(next, store.tasks.value.getValue(task.key).workId)
                assertEquals(30L, UltraScheduling.RECHECK_SECONDS)
                assertTrue(System.currentTimeMillis() - store.tasks.value.getValue(task.key).updatedAt < 30_000)
            }
            store.updateWorker(task.key, previous) { it.copy(progress = 0, phase = UltraPhase.READY) }
            assertEquals(42, store.tasks.value.getValue(task.key).progress)
            assertEquals(UltraPhase.WAITING, store.tasks.value.getValue(task.key).phase)
            previous = next
        }
        val restored = UltraTaskStore(File(directory, "tasks.json"))
        restored.load()
        assertEquals(previous, restored.tasks.value.getValue(task.key).workId)
        assertTrue(restored.tasks.value.getValue(task.key).coolingRequired)
    }

    @Test
    fun `failed scheduling retains the retryable attempt and never revives a manual pause`() = runBlocking {
        val store = UltraTaskStore(File(directory, "tasks.json"))
        store.change(task.key) { task }
        val result = runCatching {
            UltraScheduling.handOff(store, task.key, "old", "next") { throw IOException("Scheduler unavailable") }
        }
        assertTrue(result.isFailure)
        assertEquals(task, store.tasks.value[task.key])
        store.change(task.key) { it!!.copy(phase = UltraPhase.PAUSED) }
        UltraScheduling.handOff(store, task.key, "old", "next") { error("Paused work must not be enqueued") }
        store.change(task.key) { null }
        UltraScheduling.handOff(store, task.key, "old", "next") { error("Deleted work must not be enqueued") }
    }
}
