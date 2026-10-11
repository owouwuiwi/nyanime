package eu.kanade.tachiyomi.data.download.anime.ultra

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UltraProcessingPolicyTest {
    private val cool = UltraProcessingPolicy.Environment(
        interactive = false, screenOffOnly = true, playerActive = false, charging = true, chargingOnly = true,
        batteryPercent = 85, batteryCelsius = 32f, thermalStatus = 0, thermalHeadroom = 0.2f, powerSave = false,
    )

    @Test
    fun `ordinary warmth and moderate pressure no longer starve the queue`() {
        val policy = UltraProcessingPolicy()
        assertNull(
            policy.waitingReason(
                cool.copy(thermalStatus = 2, batteryCelsius = 41f, thermalHeadroom = 0.85f),
            ),
        )
        assertEquals(UltraProcessingPolicy.COOLING, policy.waitingReason(cool.copy(thermalStatus = 3)))
        assertEquals(UltraProcessingPolicy.COOLING, policy.waitingReason(cool.copy(batteryCelsius = 43f)))
        assertEquals(UltraProcessingPolicy.COOLING, policy.waitingReason(cool.copy(thermalHeadroom = 0.95f)))
        assertNull(policy.waitingReason(cool.copy(thermalStatus = 2, batteryCelsius = 42f, thermalHeadroom = 0.9f)))
    }

    @Test
    fun `previously suspended job can restart warm instead of waiting for a cold phone`() {
        val policy = UltraProcessingPolicy(cooling = true)
        assertNull(policy.waitingReason(cool.copy(batteryCelsius = 40f, thermalStatus = 2, thermalHeadroom = 0.85f)))
    }

    @Test
    fun `low memory defers allocation and releases admission after recovery`() {
        val policy = UltraProcessingPolicy()
        assertEquals(UltraProcessingPolicy.MEMORY, policy.waitingReason(cool.copy(lowMemory = true)))
        assertNull(policy.waitingReason(cool))
    }

    @Test
    fun `severe heat signals independently stop an export even when plugged in`() {
        assertEquals(
            UltraProcessingPolicy.COOLING,
            UltraProcessingPolicy().waitingReason(cool.copy(batteryCelsius = 45f)),
        )
        assertEquals(
            UltraProcessingPolicy.COOLING,
            UltraProcessingPolicy().waitingReason(cool.copy(thermalHeadroom = 1f)),
        )
        assertNull(UltraProcessingPolicy().waitingReason(cool.copy(batteryCelsius = null, thermalHeadroom = Float.NaN)))
    }

    @Test
    fun `player always has priority including when screen on processing is allowed`() {
        assertEquals(
            UltraProcessingPolicy.PLAYER,
            UltraProcessingPolicy().waitingReason(cool.copy(playerActive = true, screenOffOnly = false)),
        )
        assertEquals(UltraProcessingPolicy.SCREEN, UltraProcessingPolicy().waitingReason(cool.copy(interactive = true)))
        assertNull(UltraProcessingPolicy().waitingReason(cool.copy(interactive = true, screenOffOnly = false)))
    }

    @Test
    fun `only explicitly selected charging or critically low battery defers work`() {
        assertEquals(UltraProcessingPolicy.CHARGING, UltraProcessingPolicy().waitingReason(cool.copy(charging = false)))
        assertEquals(
            UltraProcessingPolicy.BATTERY,
            UltraProcessingPolicy().waitingReason(
                cool.copy(charging = false, chargingOnly = false, batteryPercent = 14),
            ),
        )
        assertNull(UltraProcessingPolicy().waitingReason(cool.copy(powerSave = true)))
        assertNull(
            UltraProcessingPolicy().waitingReason(
                cool.copy(charging = false, chargingOnly = false, batteryPercent = 15, powerSave = true),
            ),
        )
        assertNull(UltraProcessingPolicy().waitingReason(cool.copy(batteryPercent = 5)))
    }

    @Test
    fun `pacing budgets actual completed GPU work without overflow`() {
        assertEquals(40_000_000L, UltraProcessingPolicy.restNanos(10_000_000, false))
        assertEquals(90_000_000L, UltraProcessingPolicy.restNanos(10_000_000, true))
        assertEquals(0L, UltraProcessingPolicy.restNanos(-1, false))
        assertTrue(UltraProcessingPolicy.restNanos(Long.MAX_VALUE, true) > 0)
        for (width in listOf(320, 720, 1920, 3840)) {
            val height = UltraProcessingPolicy.stripHeight(width)
            assertTrue(height * width <= 262_144)
            val rows = (0 until 2161 step height).flatMap { y -> (y until minOf(2161, y + height)).toList() }
            assertEquals((0 until 2161).toList(), rows)
        }
    }
}
