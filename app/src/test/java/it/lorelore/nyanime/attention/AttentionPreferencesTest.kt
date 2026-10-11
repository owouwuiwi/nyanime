package it.lorelore.nyanime.attention

import eu.kanade.tachiyomi.data.backup.BackupPreferencePolicy
import io.mockk.every
import io.mockk.mockk
import it.lorelore.nyanime.attention.core.AttentionMode
import it.lorelore.nyanime.attention.core.AttentionTimeline
import it.lorelore.nyanime.attention.core.AttentionTraceChunk
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

class AttentionPreferencesTest {
    private class MemoryStore {
        val values = mutableMapOf<String, Any>()
        val store = mockk<PreferenceStore>()
        private inline fun <reified T : Any> preference(key: String, default: T): Preference<T> =
            mockk<Preference<T>>().also { pref ->
                every { pref.get() } answers {
                    @Suppress("UNCHECKED_CAST")
                    (values[key] as? T) ?: default
                }
                every { pref.set(any()) } answers { values[key] = firstArg<T>() }
                every { pref.delete() } answers {
                    values.remove(key)
                    Unit
                }
            }
        init {
            every { store.getAll() } answers { values.toMap() }
            every { store.getString(any(), any()) } answers { preference(firstArg(), secondArg<String>()) }
            every { store.getBoolean(any(), any()) } answers { preference(firstArg(), secondArg<Boolean>()) }
            every { store.getInt(any(), any()) } answers { preference(firstArg(), secondArg<Int>()) }
            every { store.getFloat(any(), any()) } answers { preference(firstArg(), secondArg<Float>()) }
        }
    }

    @Test fun disabledByDefaultAndRestoringDefaultsPreservesRecovery() {
        val memory = MemoryStore()
        val preferences = AttentionPreferences(memory.store)
        assertFalse(preferences.options().enabled)
        assertEquals(1_500, preferences.options().returnMs)
        preferences.enabled.set(true)
        val key = AttentionPreferences.key("test-medium", "test-content")
        preferences.save(key, 42_000, false)
        preferences.defaults()
        assertFalse(preferences.options().enabled)
        assertNotNull(preferences.point(key, false))
    }

    @Test fun stableLengthDelimitedIdentityHasNoConcatenationCollision() {
        assertNotEquals(AttentionPreferences.key("ab", "c"), AttentionPreferences.key("a", "bc"))
        assertEquals(AttentionPreferences.key("one", "two"), AttentionPreferences.key("one", "two"))
        assertEquals(64, AttentionPreferences.key("one").length)
    }

    @Test fun recoveredSettingsAndPointsSurviveDeviceLocalIdChanges() {
        val first = MemoryStore()
        val preferences = AttentionPreferences(first.store)
        val key = AttentionPreferences.key("native", "stable-provider", "/entry/opaque", "/part/opaque")
        preferences.enabled.set(true)
        preferences.awaySeconds.set(8)
        preferences.save(key, 12_345, false)
        assertTrue(first.values.keys.all(BackupPreferencePolicy::isPortable))
        val second = MemoryStore().also { it.values.putAll(first.values) }
        val restored = AttentionPreferences(second.store)
        assertTrue(restored.options().enabled)
        assertEquals(8_000, restored.options().awayMs)
        assertEquals(12_345, restored.point(key, false)?.positionMs)
    }

    @Test fun incognitoDoesNotWritePointsAndCannotReadOrdinaryPoints() {
        val memory = MemoryStore()
        val preferences = AttentionPreferences(memory.store)
        val key = AttentionPreferences.key("private-content")
        preferences.save(key, 50_000, false)
        val saved = memory.values.toMap()
        assertNull(preferences.point(key, true))
        preferences.save(key, 60_000, true)
        assertEquals(saved, memory.values)
        assertEquals(60_000, preferences.point(key, true)?.positionMs)
        assertEquals(50_000, preferences.point(key, false)?.positionMs)
        assertNull(AttentionPreferences(memory.store).point(key, true))
        preferences.clear()
        assertNull(preferences.point(key, true))
        assertNull(preferences.point(key, false))
    }

    @Test fun storeKeepsOnlyNewestHundredAndReplacesSameContent() {
        val memory = MemoryStore()
        var time = 1_000_000L
        val preferences = AttentionPreferences(memory.store) { time }
        repeat(105) {
            time++
            preferences.save(AttentionPreferences.key("item-$it"), it.toLong(), false)
        }
        assertNull(preferences.point(AttentionPreferences.key("item-0"), false))
        val newest = AttentionPreferences.key("item-104")
        preferences.save(newest, 999, false)
        assertEquals(999, preferences.point(newest, false)?.positionMs)
        assertNotNull(preferences.point(AttentionPreferences.key("item-5"), false))
    }

    @Test fun expiredFutureAndMalformedRecordsAreRejected() {
        val memory = MemoryStore()
        var time = 1_000_000L
        val preferences = AttentionPreferences(memory.store) { time }
        val key = AttentionPreferences.key("opaque")
        preferences.save(key, 1_000, false)
        time--
        assertNull(preferences.point(key, false))
        time += 31L * 24 * 60 * 60 * 1_000
        assertNull(preferences.point(key, false))
        memory.values["attention_v1_recovery_points"] = "invalid"
        assertNull(preferences.point(key, false))
        memory.values["attention_v1_recovery_points"] = "x".repeat(100_000)
        assertNull(preferences.point(key, false))
    }

    @Test fun observationModeIsExplicitPortableAndDefaultsBackToAutomatic() {
        val memory = MemoryStore()
        val preferences = AttentionPreferences(memory.store)
        assertEquals(AttentionMode.Automatic, preferences.options().mode)
        preferences.mode.set(AttentionMode.ObserveOnly.ordinal)
        assertEquals(AttentionMode.ObserveOnly, preferences.options().mode)
        assertTrue(memory.values.keys.all(BackupPreferencePolicy::isPortable))
        val second = MemoryStore().also { it.values.putAll(memory.values) }
        assertEquals(AttentionMode.ObserveOnly, AttentionPreferences(second.store).options().mode)
        preferences.mode.set(999)
        assertEquals(AttentionMode.Automatic, preferences.options().mode)
        preferences.defaults()
        assertEquals(AttentionMode.Automatic, preferences.options().mode)
    }

    @Test fun estimatedTraceMergesRevisitsAndStaysSeparatePerMedia() {
        val memory = MemoryStore()
        val preferences = AttentionPreferences(memory.store)
        val first = AttentionPreferences.key("first-item")
        val second = AttentionPreferences.key("second-item")
        val epoch = preferences.traceGeneration()
        preferences.mergeTrace(AttentionTraceChunk(first, false, byteArrayOf(1, 0, 2)), epoch)
        preferences.mergeTrace(AttentionTraceChunk(first, false, byteArrayOf(2, 8)), epoch)
        preferences.mergeTrace(AttentionTraceChunk(second, false, byteArrayOf(4)), epoch)
        assertArrayEquals(byteArrayOf(3, 8, 2), preferences.trace(first))
        assertArrayEquals(byteArrayOf(4), preferences.trace(second))
        assertEquals(2, preferences.traceCount())
    }

    @Test fun traceBackupPreservesStableMediaKeysWithoutDeviceDatabaseIds() {
        val memory = MemoryStore()
        val preferences = AttentionPreferences(memory.store)
        val key = AttentionPreferences.key("native", "opaque-provider", "/entry/opaque", "/part/opaque")
        preferences.mergeTrace(AttentionTraceChunk(key, false, byteArrayOf(9, 1, 2)), preferences.traceGeneration())
        assertTrue(memory.values.keys.all(BackupPreferencePolicy::isPortable))
        val second = MemoryStore().also { it.values.putAll(memory.values) }
        assertArrayEquals(byteArrayOf(9, 1, 2), AttentionPreferences(second.store).trace(key))
    }

    @Test fun incognitoDoesNotReadOrPersistAttentionTraces() {
        val memory = MemoryStore()
        val preferences = AttentionPreferences(memory.store)
        val key = AttentionPreferences.key("private-item")
        val epoch = preferences.traceGeneration()
        preferences.mergeTrace(AttentionTraceChunk(key, false, byteArrayOf(1)), epoch)
        val saved = memory.values.toMap()
        preferences.mergeTrace(AttentionTraceChunk(key, true, byteArrayOf(2)), epoch)
        assertEquals(saved, memory.values)
        assertNull(preferences.trace(key, incognito = true))
        assertArrayEquals(byteArrayOf(1), preferences.trace(key))
    }

    @Test fun deletingTraceDataFencesQueuedWritesAndPreservesNormalProgressSettingsAndPoints() {
        val memory = MemoryStore()
        val preferences = AttentionPreferences(memory.store)
        val key = AttentionPreferences.key("deletion-item")
        preferences.enabled.set(true)
        preferences.mode.set(AttentionMode.ObserveOnly.ordinal)
        preferences.save(key, 12_000, false)
        val epoch = preferences.traceGeneration()
        preferences.mergeTrace(AttentionTraceChunk(key, false, byteArrayOf(1)), epoch)
        preferences.clearTraces()
        assertNull(preferences.trace(key))
        assertEquals(0, preferences.traceCount())
        assertFalse(preferences.mergeTrace(AttentionTraceChunk(key, false, byteArrayOf(2)), epoch))
        assertNull(preferences.trace(key))
        assertTrue(preferences.options().enabled)
        assertEquals(AttentionMode.ObserveOnly, preferences.options().mode)
        assertEquals(12_000, preferences.point(key, false)?.positionMs)
        assertTrue(
            preferences.mergeTrace(AttentionTraceChunk(key, false, byteArrayOf(4)), preferences.traceGeneration()),
        )
        assertArrayEquals(byteArrayOf(4), preferences.trace(key))
    }

    @Test fun malformedAndOversizedTraceRecordsAreIgnoredWithoutCrashing() {
        val memory = MemoryStore()
        val preferences = AttentionPreferences(memory.store)
        val key = AttentionPreferences.key("corrupt-item")
        val storedKey = "attention_v1_coverage_" + key
        for (invalid in listOf("invalid", "1:5000:z", "1:5000:" + "f".repeat(AttentionTimeline.MAX_BINS + 1))) {
            memory.values[storedKey] = invalid
            assertNull(preferences.trace(key))
        }
        preferences.mergeTrace(AttentionTraceChunk(key, false, byteArrayOf(2)), preferences.traceGeneration())
        assertArrayEquals(byteArrayOf(2), preferences.trace(key))
    }
}
