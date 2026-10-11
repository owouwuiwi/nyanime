package eu.kanade.tachiyomi.data.updater

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class UpdateInstallGateTest {
    @Test
    fun `download completion must wait for a visible resumed page and a verified file`() {
        var gate = UpdateInstallGate().request()
        for ((ready, resumed) in listOf(false to true, true to false, false to false)) {
            val (next, action) = gate.claim(ready, resumed, true)
            assertNull(action)
            gate = next
        }
        assertEquals(UpdateInstallGate.Action.INSTALLER, gate.claim(true, true, true).second)
    }

    @Test
    fun `rotation and repeated emissions cannot launch Android twice`() {
        val (claimed, action) = UpdateInstallGate().request().claim(true, true, true)
        assertEquals(UpdateInstallGate.Action.INSTALLER, action)
        var gate = claimed
        repeat(5) {
            assertNull(gate.claim(true, true, true).second)
            gate = gate.lifecycle(false).lifecycle(true)
            assertNull(gate.claim(true, true, true).second)
        }
    }

    @Test
    fun `permission denial is actionable without reopening settings automatically`() {
        val (pending, action) = UpdateInstallGate().request().claim(true, true, false)
        assertEquals(UpdateInstallGate.Action.PERMISSION, action)
        assertNull(pending.claim(true, true, false).second)
        val denied = pending.permissionReturned(false)
        assertEquals(UpdateInstallGate.Stage.IDLE, denied.stage)
        assertNull(denied.claim(true, true, false).second)
        assertEquals(UpdateInstallGate.Action.PERMISSION, denied.request().claim(true, true, false).second)
    }

    @Test
    fun `granted permission continues once and still waits for a fresh verification`() {
        val pending = UpdateInstallGate().request().claim(true, true, false).first
        val granted = pending.permissionReturned(true)
        assertNull(granted.claim(false, true, true).second)
        assertNull(granted.claim(true, false, true).second)
        val (launched, action) = granted.claim(true, true, true)
        assertEquals(UpdateInstallGate.Action.INSTALLER, action)
        assertNull(launched.permissionReturned(true).claim(true, true, true).second)
    }

    @Test
    fun `installer cancellation leaves a manual install action without an automatic loop`() {
        val launched = UpdateInstallGate().request().claim(true, true, true).first
        assertEquals(UpdateInstallGate.Stage.INSTALLER, launched.lifecycle(true).stage)
        val returned = launched.lifecycle(false).lifecycle(true)
        assertEquals(UpdateInstallGate.Stage.IDLE, returned.stage)
        assertNull(returned.claim(true, true, true).second)
        assertEquals(UpdateInstallGate.Action.INSTALLER, returned.request().claim(true, true, true).second)
    }

    @Test
    fun `leaving or reopening the page never inherits automatic installation`() {
        val newVisit = UpdateInstallGate()
        assertNull(newVisit.claim(true, true, true).second)
        assertNull(newVisit.permissionReturned(true).claim(true, true, true).second)
    }

    @Test
    fun `permission return cannot install a newer download that replaced the requested APK`() {
        val pending = UpdateInstallGate().request("first").claim(true, true, false, "first").first
        val granted = pending.permissionReturned(true)
        assertEquals(UpdateInstallGate.Action.INSTALLER, granted.claim(true, true, true, "first").second)
        val (replaced, action) = granted.claim(true, true, true, "second")
        assertNull(action)
        assertEquals(UpdateInstallGate.Stage.IDLE, replaced.stage)
    }
}
