package eu.kanade.tachiyomi.extension

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference
import tachiyomi.core.common.preference.PreferenceStore

class ExtensionUpdatePolicyTest {
    private class Store {
        val longs = mutableMapOf<String, InMemoryPreference<Long>>()
        val sets = mutableMapOf<String, InMemoryPreference<Set<String>>>()
        val preferences = mockk<PreferenceStore> {
            every { getLong(any(), any()) } answers {
                val key = firstArg<String>()
                longs.getOrPut(key) { InMemoryPreference(key, null, secondArg()) }
            }
            every { getStringSet(any(), any()) } answers {
                val key = firstArg<String>()
                sets.getOrPut(key) { InMemoryPreference(key, null, secondArg()) }
            }
        }
    }

    @Test
    fun activityAndProcessRecreationCannotRepeatAutomaticChecksBefore24Hours(): Unit = runBlocking {
        val store = Store()
        var now = 1_000_000L
        var calls = 0
        suspend fun check() = ExtensionUpdateCheckGate(store.preferences) {
            now
        }.run(ExtensionUpdateKind.ANIME) { ++calls }
        assertEquals(1, check())
        repeat(20) { assertNull(check()) }
        now += 86_399_999
        assertNull(check())
        now++
        assertEquals(2, check())
    }

    @Test
    fun animeAndMangaHaveIndependentSchedules(): Unit = runBlocking {
        val gate = ExtensionUpdateCheckGate(Store().preferences) { 1_000_000L }
        assertEquals("anime", gate.run(ExtensionUpdateKind.ANIME) { "anime" })
        assertEquals("manga", gate.run(ExtensionUpdateKind.MANGA) { "manga" })
        assertNull(gate.run(ExtensionUpdateKind.ANIME) { error("duplicate") })
    }

    @Test
    fun overlappingApiInstancesMakeOnlyOneCheck(): Unit = runBlocking {
        val store = Store()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val first = async {
            ExtensionUpdateCheckGate(store.preferences).run(ExtensionUpdateKind.ANIME) {
                started.complete(Unit)
                finish.await()
                1
            }
        }
        started.await()
        val second =
            async { ExtensionUpdateCheckGate(store.preferences).run(ExtensionUpdateKind.ANIME) { error("duplicate") } }
        finish.complete(Unit)
        assertEquals(1, first.await())
        assertNull(second.await())
    }

    @Test
    fun failedCheckRetriesAfter15MinutesNotEveryEpisode(): Unit = runBlocking {
        val store = Store()
        var now = 1_000_000L
        val gate = ExtensionUpdateCheckGate(store.preferences) { now }
        try {
            gate.run(ExtensionUpdateKind.ANIME) { throw java.io.IOException("offline") }
            fail<Unit>("Expected error")
        } catch (_: java.io.IOException) { }
        assertNull(gate.run(ExtensionUpdateKind.ANIME) { error("retry storm") })
        now += 900_000
        assertEquals(1, gate.run(ExtensionUpdateKind.ANIME) { 1 })
    }

    @Test
    fun partialCatalogueFailureRetriesWithoutMarkingAFullDaySuccessful(): Unit = runBlocking {
        var now = 1_000_000L
        val gate = ExtensionUpdateCheckGate(Store().preferences) { now }
        assertEquals(1, gate.run(ExtensionUpdateKind.MANGA, complete = { false }) { 1 })
        assertNull(gate.run(ExtensionUpdateKind.MANGA) { error("retry storm") })
        now += 900_000
        assertEquals(2, gate.run(ExtensionUpdateKind.MANGA) { 2 })
    }

    @Test
    fun cancellationDoesNotCommitSuccessOrBlockTheNextActivity(): Unit = runBlocking {
        val store = Store()
        val gate = ExtensionUpdateCheckGate(store.preferences)
        try {
            gate.run(ExtensionUpdateKind.ANIME) { throw CancellationException() }
            fail<Unit>("Expected cancellation")
        } catch (_: CancellationException) { }
        assertEquals(1, gate.run(ExtensionUpdateKind.ANIME) { 1 })
    }

    @Test
    fun backwardsClockDoesNotSuppressChecksIndefinitely(): Unit = runBlocking {
        var now = 1_000_000L
        val gate = ExtensionUpdateCheckGate(Store().preferences) { now }
        gate.run(ExtensionUpdateKind.ANIME) { 1 }
        now -= 1_000
        assertEquals(2, gate.run(ExtensionUpdateKind.ANIME) { 2 })
    }

    @Test
    fun dismissedIdenticalReorderedAndPartialResultsStaySilentAcrossRecreation() {
        val store = Store()
        val a = ExtensionUpdate("a", 1, 16.0, "A")
        val b = ExtensionUpdate("b", 3, 16.0, "B")
        ExtensionUpdateAnnouncements(store.preferences, ExtensionUpdateKind.ANIME).record(listOf(a, b))
        val recreated = ExtensionUpdateAnnouncements(store.preferences, ExtensionUpdateKind.ANIME)
        assertFalse(recreated.hasNew(listOf(b, a)))
        assertFalse(recreated.hasNew(listOf(a)))
        assertFalse(recreated.hasNew(emptyList()))
        recreated.record(listOf(a))
        assertFalse(recreated.hasNew(listOf(b, a)))
        assertTrue(recreated.hasNew(listOf(a.copy(versionCode = 2), b)))
        assertTrue(recreated.hasNew(listOf(a.copy(libVersion = 17.0))))
        assertTrue(recreated.hasNew(listOf(a.copy(packageName = "c"))))
        assertTrue(ExtensionUpdateAnnouncements(store.preferences, ExtensionUpdateKind.MANGA).hasNew(listOf(a)))
    }

    @Test
    fun newlyAnnouncedVersionsBecomeSilentWithoutGrowingTheLedger() {
        val store = Store()
        val ledger = ExtensionUpdateAnnouncements(store.preferences, ExtensionUpdateKind.ANIME)
        val a = ExtensionUpdate("a", 1, 16.0, "A")
        ledger.record(listOf(a))
        ledger.record(listOf(a.copy(versionCode = 2)))
        assertFalse(ledger.hasNew(listOf(a.copy(versionCode = 2))))
        assertEquals(1, store.sets.values.single().get().size)
        ledger.record((1..1500).map { a.copy(packageName = "p" + it) })
        assertEquals(1024, store.sets.values.single().get().size)
    }
}
