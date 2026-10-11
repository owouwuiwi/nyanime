package it.lorelore.nyanime.tracking

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class TrackingAccountBindingTest {
    @Test fun `opaque source binding survives map order and set implementation changes`() {
        val one = linkedMapOf("option-one" to "fixture-value", "option-two" to linkedSetOf("b", "a"))
        val two = linkedMapOf("option-two" to hashSetOf("a", "b"), "option-one" to "fixture-value")
        assertEquals(
            TrackingAccountBinding.configurationFingerprint(one),
            TrackingAccountBinding.configurationFingerprint(two),
        )
    }

    @Test fun `a source configuration change invalidates old binding without inspecting site keys`() {
        val first = mapOf("opaque-option" to "fixture-one", "other-option" to true)
        val next = first + ("opaque-option" to "fixture-two")
        assertNotEquals(
            TrackingAccountBinding.configurationFingerprint(first),
            TrackingAccountBinding.configurationFingerprint(next),
        )
    }
}
