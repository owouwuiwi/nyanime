package it.lorelore.nyanime.updates

import eu.kanade.tachiyomi.data.backup.BackupPreferencePolicy
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.PreferenceStore

class SupportEndUpgradeNoticeTest {
    private fun newStore(): PreferenceStore {
        val preferences = mutableMapOf<String, InMemoryPreferenceStore.InMemoryPreference<Int>>()
        return mockk {
            every { getInt(any(), any()) } answers {
                val key = firstArg<String>()
                preferences.getOrPut(key) {
                    InMemoryPreferenceStore.InMemoryPreference(key, null, secondArg<Int>())
                }
            }
        }
    }

    @Test fun freshInstallationNeverReceivesNotice() {
        val notice = SupportEndUpgradeNotice(newStore())
        notice.initialize(previousVersion = 0)
        assertNotEquals(SupportEndUpgradeNotice.PENDING, notice.state.get())
    }

    @Test fun firstInstallDecisionSurvivesOnboardingRestoreAndLaterUpdates() {
        val store = newStore()
        SupportEndUpgradeNotice(store).initialize(previousVersion = 0)
        SupportEndUpgradeNotice(store).initialize(previousVersion = 293)
        SupportEndUpgradeNotice(store).initialize(previousVersion = 294)
        assertNotEquals(SupportEndUpgradeNotice.PENDING, SupportEndUpgradeNotice(store).state.get())
    }

    @Test fun updatesFromOldAndLegacyVersionsReceiveNotice() {
        for (previous in listOf(1, 42, 288, 290, 291, 292, 8392)) {
            val notice = SupportEndUpgradeNotice(newStore())
            notice.initialize(previousVersion = previous)
            assertEquals(SupportEndUpgradeNotice.PENDING, notice.state.get())
        }
    }

    @Test fun oldRoadmapAcknowledgementDoesNotHideTheNewNotice() {
        val store = newStore()
        RoadmapUpgradeNotice(store).acknowledge()
        val notice = SupportEndUpgradeNotice(store)
        notice.initialize(previousVersion = 292)
        assertEquals(SupportEndUpgradeNotice.PENDING, notice.state.get())
    }

    @Test fun obsoleteRoadmapIsRetiredEvenWhenPreviouslyPending() {
        val store = newStore()
        val roadmap = RoadmapUpgradeNotice(store)
        roadmap.initialize(previousVersion = 1, beforeStableRelease = true)
        assertEquals(RoadmapUpgradeNotice.PENDING, roadmap.state.get())
        SupportEndUpgradeNotice(store).initialize(previousVersion = 290)
        assertNotEquals(RoadmapUpgradeNotice.PENDING, roadmap.state.get())
    }

    @Test fun rotationAndRestartPreserveTheNoticeUntilAcknowledged() {
        val store = newStore()
        SupportEndUpgradeNotice(store).initialize(previousVersion = 292)
        val recreated = SupportEndUpgradeNotice(store)
        recreated.initialize(previousVersion = 293)
        assertEquals(SupportEndUpgradeNotice.PENDING, recreated.state.get())
    }

    @Test fun acknowledgementPreventsEverySubsequentAppearance() {
        val store = newStore()
        val notice = SupportEndUpgradeNotice(store)
        notice.initialize(previousVersion = 292)
        notice.acknowledge()
        repeat(3) {
            SupportEndUpgradeNotice(store).initialize(previousVersion = 293)
            assertNotEquals(SupportEndUpgradeNotice.PENDING, SupportEndUpgradeNotice(store).state.get())
        }
    }

    @Test fun theNoticeCannotBeTransferredByBackupOrCloud() {
        val notice = SupportEndUpgradeNotice(newStore())
        assertFalse(BackupPreferencePolicy.isPortable(notice.state.key()))
    }
}
