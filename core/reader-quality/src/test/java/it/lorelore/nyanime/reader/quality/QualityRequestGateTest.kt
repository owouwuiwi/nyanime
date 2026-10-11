package it.lorelore.nyanime.reader.quality

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QualityRequestGateTest {
    @Test
    fun `superseded request cannot run or publish and cannot retire its replacement`() {
        val gate = QualityRequestGate<String>()
        gate.submit("page", 10)
        gate.submit("page", 11)
        assertFalse(gate.isCurrent("page", 10))
        gate.retire("page", 10)
        assertTrue(gate.isCurrent("page", 11))
        gate.retire("page", 11)
        assertFalse(gate.isCurrent("page", 11))
    }

    @Test
    fun `different clients can use the same request counter without mixing their results`() {
        val gate = QualityRequestGate<String>()
        gate.submit("reader-a", 1)
        gate.submit("reader-b", 1)
        gate.submit("reader-a", 2)
        assertFalse(gate.isCurrent("reader-a", 1))
        assertTrue(gate.isCurrent("reader-b", 1))
        gate.retire("reader-a", 2)
        assertTrue(gate.isCurrent("reader-b", 1))
    }

    @Test
    fun `abandoned callers cannot grow worker state without a bound`() {
        val gate = QualityRequestGate<Int>(3)
        for (owner in 1..100) gate.submit(owner, 1)
        for (owner in 1..97) assertFalse(gate.isCurrent(owner, 1))
        for (owner in 98..100) assertTrue(gate.isCurrent(owner, 1))
    }
}
