package it.lorelore.nyanime.updates

import eu.kanade.tachiyomi.data.backup.BackupPreferencePolicy
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

class RoadmapUpgradeNoticeTest {
    private fun newStore(): PreferenceStore {
        val key = Preference.appStateKey("roadmap_080_notice_v1")
        val persisted = InMemoryPreferenceStore.InMemoryPreference(key, null, 0)
        return mockk { every { getInt(key, 0) } returns persisted }
    }

    private val store = newStore()

    @Test
    fun firstInstallNeverReceivesNoticeIncludingAfterRestoreAndLaterUpdates() {
        RoadmapUpgradeNotice(store).initialize(previousVersion = 0, beforeStableRelease = true)
        val restored = RoadmapUpgradeNotice(store)
        restored.initialize(previousVersion = 288, beforeStableRelease = true)
        assertNotEquals(RoadmapUpgradeNotice.PENDING, restored.state.get())
    }

    @Test
    fun upgradesFromOldAndLegacyRevisionSchemesAreEligible() {
        for (previous in listOf(1, 42, 288, 289, 8392)) {
            val notice = RoadmapUpgradeNotice(newStore())
            notice.initialize(previousVersion = previous, beforeStableRelease = true)
            assertEquals(RoadmapUpgradeNotice.PENDING, notice.state.get())
        }
    }

    @Test
    fun pendingNoticeSurvivesRecreationUntilAcknowledged() {
        RoadmapUpgradeNotice(store).initialize(previousVersion = 1, beforeStableRelease = true)
        val recreated = RoadmapUpgradeNotice(store)
        recreated.initialize(previousVersion = 290, beforeStableRelease = true)
        assertEquals(RoadmapUpgradeNotice.PENDING, recreated.state.get())
        recreated.acknowledge()
        val future = RoadmapUpgradeNotice(store)
        future.initialize(previousVersion = 291, beforeStableRelease = true)
        assertNotEquals(RoadmapUpgradeNotice.PENDING, future.state.get())
    }

    @Test
    fun noOutdatedPreStableMessageAtOrAfterVersionOne() {
        val notice = RoadmapUpgradeNotice(store)
        notice.initialize(previousVersion = 1, beforeStableRelease = false)
        assertNotEquals(RoadmapUpgradeNotice.PENDING, notice.state.get())
    }

    @Test
    fun pendingMessageIsRetiredWhenStableReleaseArrives() {
        val notice = RoadmapUpgradeNotice(store)
        notice.initialize(previousVersion = 1, beforeStableRelease = true)
        notice.initialize(previousVersion = 290, beforeStableRelease = false)
        assertNotEquals(RoadmapUpgradeNotice.PENDING, notice.state.get())
    }

    @Test
    fun acknowledgementIsNotPortableThroughBackupOrCloud() {
        assertFalse(BackupPreferencePolicy.isPortable(RoadmapUpgradeNotice(store).state.key()))
    }
}
