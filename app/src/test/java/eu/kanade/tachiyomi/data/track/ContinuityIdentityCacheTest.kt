package eu.kanade.tachiyomi.data.track

import eu.kanade.tachiyomi.data.backup.BackupPreferencePolicy
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

class ContinuityIdentityCacheTest {
    private val key = Preference.appStateKey("continuity_identities_v1")
    private val persisted = InMemoryPreference(key, null, "[]")
    private val store = mockk<PreferenceStore> { every { getString(key, "[]") } returns persisted }
    private var time = 100_000L
    private fun cache() = ContinuityIdentityCache(store) { time }

    @Test
    fun `verified source identity survives restart but never a different URL or medium`() {
        cache().remember("manga", 1, "/work/alpha", SourceTrackingHints(42, 24), false)
        val restarted = cache()
        assertEquals(SourceTrackingHints(42, 24), restarted.get("manga", 1, "/work/alpha", false))
        assertNull(restarted.get("manga", 1, "/work/beta", false))
        assertNull(restarted.get("anime", 1, "/work/alpha", false))
        assertEquals(listOf(1L to "/work/alpha"), restarted.matching("manga", 42, 24, false))
        assertEquals(emptyList<Pair<Long, String>>(), restarted.matching("manga", 42, 25, false))
        assertFalse(BackupPreferencePolicy.isPortable(key))
    }

    @Test
    fun `private sessions neither reuse nor persist copies`() {
        val cache = cache()
        cache.remember("manga", 1, "/work/alpha", SourceTrackingHints(42), false)
        val before = persisted.get()
        cache.remember("manga", 1, "/work/beta", SourceTrackingHints(43), true)
        assertEquals(before, persisted.get())
        assertNull(cache.get("manga", 1, "/work/alpha", true))
        assertEquals(emptyList<Pair<Long, String>>(), cache.matching("manga", 42, null, true))
        assertNull(cache.get("manga", 1, "/work/beta", false))
    }

    @Test
    fun `expired or future dated identities require verification again`() {
        val cache = cache()
        cache.remember("manga", 1, "/work/alpha", SourceTrackingHints(42), false)
        time--
        assertNull(cache.get("manga", 1, "/work/alpha", false))
        time += 6 * 60 * 60 * 1000L + 2
        assertNull(cache.get("manga", 1, "/work/alpha", false))
    }

    @Test
    fun `reverification replaces conflicting IDs and the cache stays bounded`() {
        val cache = cache()
        repeat(140) { cache.remember("manga", 1, "/work/$it", SourceTrackingHints(it + 1L), false) }
        assertNull(cache.get("manga", 1, "/work/0", false))
        cache.remember("manga", 1, "/work/139", SourceTrackingHints(999), false)
        assertEquals(SourceTrackingHints(999), cache().get("manga", 1, "/work/139", false))
        assertEquals(emptyList<Pair<Long, String>>(), cache.matching("manga", 140, null, false))
    }

    @Test
    fun `invalid cache does not prevent opening a title`() {
        persisted.set("not json")
        assertNull(cache().get("manga", 1, "/work/alpha", false))
    }
}
