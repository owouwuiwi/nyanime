package eu.kanade.tachiyomi.data.extensionstore

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference
import tachiyomi.core.common.preference.PreferenceStore

class CatalogueUpgradeResetTest {
    private fun preferences(): PreferenceStore {
        val values = mutableMapOf<String, InMemoryPreference<Boolean>>()
        return mockk {
            every { getBoolean(any(), any()) } answers {
                values.getOrPut(firstArg()) { InMemoryPreference(firstArg(), null, secondArg()) }
            }
        }
    }

    @Test fun `a fresh installation is never cleared even after adding catalogues and upgrading later`() = runBlocking {
        val store = preferences()
        var deletes = 0
        CatalogueUpgradeReset(store).initialize(0) { deletes++ }
        val upgraded = CatalogueUpgradeReset(store)
        upgraded.initialize(150) { deletes++ }
        assertEquals(0, deletes)
        assertFalse(upgraded.notice.get())
    }

    @Test fun `both public and larger legacy revision codes clear exactly once across restarts`() = runBlocking {
        for (previous in listOf(141, 8_200)) {
            val store = preferences()
            var deletes = 0
            val first = CatalogueUpgradeReset(store)
            first.initialize(previous) { deletes++ }
            assertTrue(first.notice.get())
            first.acknowledge()
            val restarted = CatalogueUpgradeReset(store)
            restarted.initialize(previous) { deletes++ }
            restarted.initialize(999) { deletes++ }
            assertEquals(1, deletes)
            assertFalse(restarted.notice.get())
        }
    }

    @Test fun `an interrupted partial reset retries instead of silently marking it finished`() = runBlocking {
        val store = preferences()
        val catalogues = mutableListOf("first", "second")
        assertTrue(
            runCatching {
                CatalogueUpgradeReset(store).initialize(141) {
                    catalogues.remove("first")
                    error("Interrupted storage operation")
                }
            }.isFailure,
        )
        val restarted = CatalogueUpgradeReset(store)
        assertFalse(restarted.notice.get())
        restarted.initialize(142) { catalogues.clear() }
        assertTrue(catalogues.isEmpty())
        assertTrue(restarted.notice.get())
    }
}
