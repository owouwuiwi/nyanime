package nyanime.telegram

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TelegramChatSubscriptionsTest {
    @Test fun removingOneConsumerPreservesTheOtherIncludingSharedChats() {
        val registry = TelegramChatSubscriptions()
        registry.update("rooms", setOf(-1, -2))
        registry.update("sync", setOf(-2, -3))
        assertEquals(setOf(-1L, -2L, -3L), registry.snapshot())
        assertEquals(setOf(-2L, -3L), registry.update("rooms", emptySet()))
        assertEquals(setOf(-2L, -3L), registry.snapshot())
    }

    @Test fun invalidOrOversizedSubscriptionDoesNotDiscardWorkingSubscriptions() {
        val registry = TelegramChatSubscriptions()
        registry.update("rooms", setOf(-1))
        assertThrows(IllegalArgumentException::class.java) { registry.update("sync", setOf(1)) }
        assertThrows(IllegalArgumentException::class.java) { registry.update("sync", (-3000L..-2L).toSet()) }
        assertEquals(setOf(-1L), registry.snapshot())
    }
}
