package eu.kanade.tachiyomi.ui.main

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NavigationRestorePolicyTest {
    @Test
    fun acceptsCurrentActivitySnapshotAcrossRotationAndProcessDeath() {
        assertTrue(NavigationRestorePolicy.canRestore(NavigationRestorePolicy.SCHEMA))
    }

    @Test
    fun snapshotsFromBeforeTheSearchClassRenameAreNotRestored() {
        assertFalse(NavigationRestorePolicy.canRestore(1))
    }

    @Test
    fun oldOrUnknownWorkspaceStacksDoNotEnterTheDeserializer() {
        assertFalse(NavigationRestorePolicy.canRestore(null))
        assertFalse(NavigationRestorePolicy.canRestore(0))
        assertFalse(NavigationRestorePolicy.canRestore(NavigationRestorePolicy.SCHEMA + 1))
    }
}
