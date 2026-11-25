package eu.kanade.tachiyomi.data.extensionstore

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference
import tachiyomi.core.common.preference.PreferenceStore

class CatalogueUpgradeResetTest {
    private fun preferences(): PreferenceStore {
        val values = mutableMapOf<String, InMemoryPreference<Int>>()
        return mockk {
            every { getInt(any(), any()) } answers {
                values.getOrPut(firstArg()) { InMemoryPreference(firstArg(), null, secondArg()) }
            }
        }
    }

    @Test fun `a fresh installation is never cleared even after adding catalogues and upgrading later`() = runBlocking {
        val store = preferences()
        CatalogueUpgradeReset(store).prepare(0)
        val upgraded = CatalogueUpgradeReset(store)
        upgraded.prepare(298)
        upgraded.resetCatalogues { error("A fresh installation has no migration") }
        assertFalse(upgraded.pending)
    }

    @Test fun `both public and larger legacy revision codes clear exactly once across restarts`() = runBlocking {
        for (previous in listOf(141, 8_200)) {
            val store = preferences()
            var deletes = 0
            val first = CatalogueUpgradeReset(store)
            first.prepare(previous)
            assertFalse(first.finish { false })
            first.resetCatalogues { deletes++ }
            assertTrue(first.pending)
            val restarted = CatalogueUpgradeReset(store)
            restarted.prepare(298)
            restarted.resetCatalogues { deletes++ }
            assertEquals(1, deletes)
            assertFalse(restarted.finish { true })
            assertTrue(restarted.pending)
            assertTrue(restarted.finish { false })
            val later = CatalogueUpgradeReset(store)
            later.prepare(299)
            later.resetCatalogues { deletes++ }
            assertEquals(1, deletes)
            assertFalse(later.pending)
        }
    }

    @Test fun `an interrupted partial reset retries instead of silently marking it finished`() = runBlocking {
        val store = preferences()
        val catalogues = mutableListOf("first", "second")
        val initial = CatalogueUpgradeReset(store)
        initial.prepare(297)
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                initial.resetCatalogues {
                    catalogues.remove("first")
                    error("Interrupted storage operation")
                }
            }
        }
        val restarted = CatalogueUpgradeReset(store)
        assertTrue(restarted.pending)
        assertFalse(restarted.finish { false })
        restarted.resetCatalogues { catalogues.clear() }
        assertTrue(catalogues.isEmpty())
        assertTrue(restarted.finish { false })
    }
}
