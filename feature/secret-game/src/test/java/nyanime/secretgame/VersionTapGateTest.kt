package nyanime.secretgame

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VersionTapGateTest {
    @Test
    fun `five taps open once until reset`() {
        val gate = VersionTapGate()
        repeat(4) { assertFalse(gate.tap(it * 100L)) }
        assertTrue(gate.tap(400))
        repeat(10) { assertFalse(gate.tap(500 + it * 100L)) }
        gate.reset()
        repeat(4) { assertFalse(gate.tap(2_000 + it * 100L)) }
        assertTrue(gate.tap(2_400))
    }

    @Test
    fun `two seconds inactivity starts a new sequence`() {
        val gate = VersionTapGate()
        repeat(4) { assertFalse(gate.tap(it * 100L)) }
        assertFalse(gate.tap(2_300))
        repeat(3) { assertFalse(gate.tap(2_400 + it * 100L)) }
        assertTrue(gate.tap(2_700))
    }

    @Test
    fun `lifecycle reset and long press clear incomplete sequence`() {
        val gate = VersionTapGate()
        repeat(4) { gate.tap(it * 100L) }
        gate.reset()
        assertFalse(gate.tap(500))
        repeat(3) { assertFalse(gate.tap(600 + it * 100L)) }
        assertTrue(gate.tap(900))
    }
}
