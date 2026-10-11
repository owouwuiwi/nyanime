package eu.kanade.tachiyomi.data.watch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WatchInteractionCryptoTest {
    @Test
    fun pairwiseMessagesCannotBeReadUsingAnOldRoomInvitation() {
        val owner = WatchIdentity()
        val friend = WatchIdentity()
        val removed = WatchIdentity()
        try {
            val invite = WatchInvite.create(owner.publicKey, 1_800_000_000_000)
            val host = WatchInteractionCrypto(invite, owner)
            val receiver = WatchInteractionCrypto(invite, friend)
            val outsider = WatchInteractionCrypto(invite, removed)
            val membership = watchHash("two members".toByteArray()).watchHex()
            val message = WatchInteractionPayload("a".repeat(32), WatchInteractionKind.Chat, text = "Hello")
            val first = host.seal(friend.publicKey, membership, message)
            val second = host.seal(friend.publicKey, membership, message)
            assertNotEquals(first.content, second.content)
            assertEquals(message, receiver.open(owner.publicKey, membership, first))
            assertNull(outsider.open(owner.publicKey, membership, first))
            assertNull(receiver.open(owner.publicKey, "0".repeat(64), first))
            assertNull(receiver.open(removed.publicKey, membership, first))
            assertNull(receiver.open(owner.publicKey, membership, first.copy(content = first.content.reversed())))
        } finally {
            owner.clear()
            friend.clear()
            removed.clear()
        }
    }

    @Test
    fun keyDerivationMatchesRfc5869CaseOne() {
        val result = WatchInteractionCrypto.hkdf(
            "0b".repeat(22).watchBytes(),
            "000102030405060708090a0b0c".watchBytes(),
            "f0f1f2f3f4f5f6f7f8f9".watchBytes(),
        )
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf", result.watchHex())
    }
}
