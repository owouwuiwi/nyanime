package eu.kanade.tachiyomi.data.watch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WatchCryptoTest {
    private val now = 1_800_000_000_000L
    private val identity = WatchIdentity()
    private val invite = WatchInvite.create(identity.publicKey, now)
    private val crypto = WatchCrypto(invite, identity)
    private val message = WatchMessage(type = WatchMessageType.Command, sequence = 1, at = 2000, command = "play")

    @Test
    fun compactCodeRoundtripsWithChatMessage() {
        val code = invite.encode()
        assertEquals(42, code.length)
        val parsed = WatchInvite.parse("Join me!\n" + code + "\nThanks", now)
        assertEquals(invite.secret, parsed.secret)
        assertEquals(invite.owner, parsed.owner)
        assertEquals(invite.topic, parsed.topic)
        assertTrue(parsed.owns(identity.publicKey))
        assertFalse(parsed.owns(WatchIdentity().publicKey))
    }

    @Test
    fun truncatedOrExtendedCodesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { WatchInvite.parse(invite.encode().dropLast(1), now) }
        assertThrows(IllegalArgumentException::class.java) { WatchInvite.parse(invite.encode() + "X", now) }
        assertThrows(IllegalArgumentException::class.java) { WatchInvite.parse("NY1.somethingwrong", now) }
    }

    @Test
    fun authenticatedEncryptionUsesFreshNonces() {
        val first = crypto.seal(message, now)
        val second = crypto.seal(message, now)
        assertEquals(message, crypto.open(first, now))
        assertNotEquals(first.content, second.content)
        assertNotEquals(first.id, second.id)
        assertFalse(first.content.contains("play"))
    }

    @Test
    fun tamperingWrongRoomsExpiredFramesAndForgedSendersAreRejected() {
        val sealed = crypto.seal(message, now)
        assertNull(crypto.open(sealed.copy(content = sealed.content.reversed()), now))
        assertNull(crypto.open(sealed.copy(pubkey = WatchIdentity().publicKey), now))
        assertNull(crypto.open(sealed.copy(sig = "00".repeat(64)), now))
        assertNull(crypto.open(sealed, now + 61_000))
        val other = WatchCrypto(WatchInvite.create(identity.publicKey, now), identity)
        assertNull(other.open(sealed, now))
    }

    @Test
    fun numericAndSizeLimitsAreChecked() {
        assertFalse(message.copy(speed = Double.NaN).valid())
        assertFalse(message.copy(position = Double.POSITIVE_INFINITY).valid())
        assertFalse(message.copy(media = WatchMedia("X", "", 1.0, -1.0)).valid())
        assertFalse(message.copy(sequence = -1).valid())
        assertFalse(message.copy(name = "x".repeat(33)).valid())
    }

    @Test
    fun unsafeRelayAddressesAndExpiredInvitationsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            invite.copy(relays = listOf("ws://example.test")).validate(now)
        }
        assertThrows(IllegalArgumentException::class.java) {
            invite.copy(relays = listOf("wss://name:secret@example.test")).validate(now)
        }
        assertThrows(IllegalArgumentException::class.java) { invite.copy(expires = now - 1).validate(now) }
        assertNotNull(invite.validate(now))
    }

    @Test
    fun clockPrefersLowRttSamples() {
        val clock = WatchClock()
        assertFalse(clock.accept(100, 99, 1000))
        assertFalse(clock.accept(0, 20_000, 1000))
        assertTrue(clock.accept(100, 300, 1200))
        assertEquals(1000L, clock.offset)
        assertTrue(clock.accept(500, 550, 1510))
        assertEquals(985L, clock.offset)
    }

    @Test
    fun catalogReferencesCannotSendSourceCredentialsToAnotherOrigin() {
        assertTrue(WatchCatalogReference.isAllowed("/show/42", "https://example.test"))
        assertTrue(WatchCatalogReference.isAllowed("https://example.test/show/42", "https://example.test"))
        assertFalse(WatchCatalogReference.isAllowed("http://example.test/show/42", "https://example.test"))
        assertFalse(WatchCatalogReference.isAllowed("https://other.test/", "https://example.test"))
        assertFalse(WatchCatalogReference.isAllowed("//other.test/", "https://example.test"))
        assertFalse(WatchCatalogReference.isAllowed("https://example.test@other.test/", "https://example.test"))
        assertFalse(WatchCatalogReference.isAllowed("file:///private/data", "https://example.test"))
        assertFalse(WatchCatalogReference.isAllowed("https://example.test:8443/", "https://example.test"))
    }

    @Test
    fun resolvedEpisodeKeepsRoomIdentityAndItsActualLocalDuration() {
        val remote = WatchMedia("Test Animation", "Episode 2", 2.0, 1400.0, 7, "/title", "/old-episode-url")
        val local = remote.copy(episodeUrl = "/refreshed-episode-url", duration = 1401.0)
        val selection = WatchSelection(remote, local.key, 1, 2)
        val sample = WatchPlayback(local, 25.0, true, true, false, 1.0)
        val mapped = selection.applyTo(sample)
        assertEquals(remote.key, mapped.media?.key)
        assertEquals(1401.0, mapped.media?.duration)
        assertTrue(mapped.media!!.matches(remote))
        assertFalse(selection.applyTo(sample.copy(media = local.copy(duration = 1000.0))).media!!.matches(remote))
    }

    @Test
    fun matchingCatalogEpisodesTolerateManifestDurationNoiseButRejectAnotherCut() {
        val media = WatchMedia("Test Animation", "Episode 2", 2.0, 1400.0, 7, "/title", "/2")
        assertTrue(media.matches(media.copy(duration = 1425.0)))
        assertFalse(media.matches(media.copy(duration = 0.0)))
        assertFalse(media.matches(media.copy(duration = 1500.0)))
        assertFalse(media.matches(media.copy(episodeUrl = "/3")))
    }

    @Test
    fun anotherEpisodeCannotInheritResolvedRoomIdentity() {
        val remote = WatchMedia("Test Animation", "Episode 2", 2.0, 1400.0, 7, "/title", "/2")
        val selection = WatchSelection(remote, remote.key, 1, 2)
        val other = WatchPlayback(remote.copy(episodeUrl = "/3"), 25.0, true, true, false, 1.0)
        assertEquals(other, selection.applyTo(other))
    }
}
