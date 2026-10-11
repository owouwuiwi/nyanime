package eu.kanade.tachiyomi.ui.player

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlaybackDiagnosticGateTest {
    @Test
    fun `warning bursts cannot create an unbounded native sampling queue`() {
        val gate = PlaybackDiagnosticGate()
        assertTrue(gate.accept(1, 0))
        repeat(100) { assertFalse(gate.accept(1, 1_000)) }
        assertTrue(gate.accept(1, 2_000))
        repeat(30) { assertTrue(gate.accept(1, 4_000L + it * 2_000)) }
        assertFalse(gate.accept(1, 1_000_000))
    }

    @Test
    fun `new media can be diagnosed but late callbacks never reset its budget`() {
        val gate = PlaybackDiagnosticGate()
        assertTrue(gate.accept(1, 0))
        assertTrue(gate.accept(2, 20))
        assertFalse(gate.accept(1, 3_000))
        assertFalse(gate.accept(2, 21))
        assertTrue(gate.accept(2, 2_020))
    }
}
