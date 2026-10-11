package eu.kanade.tachiyomi.ui.search

import eu.kanade.tachiyomi.data.backup.BackupPreferencePolicy
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

class AtlasUpgradeNoticeTest {
    private val key = Preference.appStateKey("atlas_upgrade_notice_v1")
    private val persisted = InMemoryPreference(key, null, 0)
    private val store = mockk<PreferenceStore> {
        every { getInt(key, 0) } returns persisted
    }

    @Test
    fun freshInstallDoesNotReceiveTheNoticeOnLaterLaunchesOrUpdates() {
        val fresh = AtlasUpgradeNotice(store)
        fresh.initialize(previousVersion = 0)
        assertNotEquals(AtlasUpgradeNotice.PENDING, fresh.state.get())

        val laterLaunch = AtlasUpgradeNotice(store)
        laterLaunch.initialize(previousVersion = 133)
        assertNotEquals(AtlasUpgradeNotice.PENDING, laterLaunch.state.get())
    }

    @Test
    fun existingInstallReceivesTheNoticeEvenWhenAndroidVersionCodeHasNotChanged() {
        val upgrade = AtlasUpgradeNotice(store)
        upgrade.initialize(previousVersion = 133)
        assertEquals(AtlasUpgradeNotice.PENDING, upgrade.state.get())
    }

    @Test
    fun pendingNoticeSurvivesRestartUntilAcknowledged() {
        AtlasUpgradeNotice(store).initialize(previousVersion = 132)
        val restarted = AtlasUpgradeNotice(store)
        restarted.initialize(previousVersion = 133)
        assertEquals(AtlasUpgradeNotice.PENDING, restarted.state.get())
        restarted.acknowledge()

        val futureUpdate = AtlasUpgradeNotice(store)
        futureUpdate.initialize(previousVersion = 134)
        assertNotEquals(AtlasUpgradeNotice.PENDING, futureUpdate.state.get())
    }

    @Test
    fun backupDoesNotTransferTheNoticeToAnotherInstallation() {
        val notice = AtlasUpgradeNotice(store)
        assertFalse(BackupPreferencePolicy.isPortable(notice.state.key()))
    }
}
