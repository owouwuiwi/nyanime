package eu.kanade.tachiyomi.data.community

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RelayDeliveryPolicyTest {
    @Test
    fun `untrusted relay text is never reflected in the issue`() {
        val reason = RelayRejection.parse("rate-limited: private content must not be logged")
        assertEquals(RelayRejection.RateLimited, reason)
        assertTrue(reason.pausesRelay)
        assertFalse(reason.describe("wss://relay.example").contains("private content"))
        assertEquals(RelayRejection.Other, RelayRejection.parse("arbitrary text"))
    }

    @Test
    fun `cooldowns grow and remain bounded`() {
        assertEquals(30_000L, RelayRejection.RateLimited.retryDelay(1))
        assertEquals(60_000L, RelayRejection.RateLimited.retryDelay(2))
        assertEquals(3_600_000L, RelayRejection.Blocked.retryDelay(Int.MAX_VALUE))
        assertTrue(RelayRejection.Authentication.pausesRelay)
    }

    @Test
    fun `partially replicated changes are not labelled unsent`() {
        assertEquals(
            "Dati inviati · seconda copia in attesa",
            CommunityState(connected = 1, pending = 1441, replicating = 1441).deliveryLabel(),
        )
        assertTrue(CommunityState(connected = 2, pending = 1441, replicating = 1400).deliveryLabel().startsWith("41 "))
    }
}
