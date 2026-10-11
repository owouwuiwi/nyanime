@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.data.community

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommunityProtocolTest {
    @Test fun `recovery authenticates the protected key and rejects wrong passwords or tampering`() {
        val secret = ByteArray(32) { (it + 1).toByte() }
        val password = "correct recovery password".toCharArray()
        val exported = IdentityRecovery.export(secret, password)
        assertTrue(secret.contentEquals(IdentityRecovery.restore(exported, password)))
        assertTrue(runCatching { IdentityRecovery.restore(exported, "another password".toCharArray()) }.isFailure)
        val bytes = java.util.Base64.getDecoder().decode(exported.removePrefix("NYR1."))
        bytes[40] = (bytes[40].toInt() xor 1).toByte()
        val altered = "NYR1." + java.util.Base64.getEncoder().encodeToString(bytes)
        assertTrue(runCatching { IdentityRecovery.restore(altered, password) }.isFailure)
    }

    @Test fun `encrypted recovery index spans relay limits without public title hashes`() {
        val key = ByteArray(32) { 7 }
        val ref = SyncReference(source = 4, titleUrl = "/a-private-title", itemUrl = "/part")
        val opaque = Nip44.hmac(key, ref.key().toByteArray()).hex()
        assertNotEquals(ref.key(), opaque)
        val addresses = (0 until 40_201).map {
            "nyanime.sync.v1:" + "a".repeat(32) + ":" + Nip44.hmac(key, it.toString().toByteArray()).hex()
        }
        val nodes = mutableMapOf<String, SyncCheckpoint>()
        val root = requireNotNull(SyncCheckpoint.build(addresses, key) { address, node -> nodes[address] = node })
        fun flatten(node: SyncCheckpoint): List<String> =
            if (node.leaf) node.entries else node.entries.flatMap { flatten(nodes.getValue(it)) }
        assertEquals(addresses, flatten(root))
        assertTrue(
            nodes.values.all {
                it.valid() &&
                    communityJson.encodeToString(SyncCheckpoint.serializer(), it).toByteArray().size < Nip44.MAX_BYTES
            },
        )
        assertTrue(nodes.all { (address, node) -> address == node.address(key) })
        val restored = communityJson.decodeFromString(
            SyncCheckpoint.serializer(),
            Nip44.decrypt(key, Nip44.encrypt(key, communityJson.encodeToString(SyncCheckpoint.serializer(), root))),
        )
        assertEquals(root, restored)
    }

    @Test fun `a clock ahead cannot prevent a later new friendship request`() {
        val old = PrivateAction(type = "friend.request", request = "old", revision = 4)
        val removed = PrivateAction(type = "friend.remove", request = "old", revision = 9000)
        val first = FriendLedger().apply(old, false).apply(removed, true)
        val reversed = FriendLedger().apply(removed, true).apply(old, false)
        assertEquals(first, reversed)
        assertEquals("", first.state("peer", false).incoming)
        val fresh = first.apply(old.copy(request = "new", revision = 5), false)
        assertEquals("new", fresh.state("peer", false).incoming)
        assertFalse(fresh.state("peer", false).accepted)
    }

    @Test fun `group messages are bound to their exact owner roster`() {
        val a = "a".repeat(64)
        val b = "b".repeat(64)
        val c = "c".repeat(64)
        val first = PrivateGroup("1".repeat(32), a, "Our group", listOf(a, b))
        val expanded = first.copy(revision = 2, members = listOf(a, b, c))
        val removed = expanded.copy(revision = 3, members = listOf(a, c))
        fun message(roster: PrivateGroup) = GiftWrap.rumor(
            a,
            14,
            "Hello",
            roster.members.filter { it != a }.map { listOf("p", it) } +
                listOf(listOf("nyanime-group", roster.id, roster.revision.toString(), roster.fingerprint())),
        )
        assertTrue(GroupPolicy.accepts(first, message(first), b))
        assertFalse(GroupPolicy.accepts(first, message(first), c))
        assertFalse(GroupPolicy.accepts(expanded, message(first), c))
        assertFalse(GroupPolicy.accepts(removed, message(removed), b))
        assertTrue(GroupPolicy.newer(expanded, removed))
        assertFalse(GroupPolicy.newer(removed, expanded))
        val concurrent = expanded.copy(name = "Another name")
        assertNotEquals(GroupPolicy.newer(expanded, concurrent), GroupPolicy.newer(concurrent, expanded))
    }

    @Test fun `an offline progress edit does not resurrect a deleted library entry`() {
        val ref = SyncReference(source = 1, titleUrl = "/title")
        val old = SyncRecord(ref, SyncRevision(10, 0, "a".repeat(32)), favorite = true)
        val deletion = old.copy(
            revision = SyncRevision(20, 0, "a".repeat(32)),
            favorite = false,
            deleted = true,
            edits = setOf(SyncField.Library),
        )
        val offline = old.copy(
            revision = SyncRevision(30, 0, "b".repeat(32)),
            position = 12,
            edits = setOf(SyncField.Progress),
        )
        val first = SyncMerge.merge(SyncMerge.merge(old, deletion), offline)
        val reversed = SyncMerge.merge(SyncMerge.merge(old, offline), deletion)
        assertEquals(first, reversed)
        assertTrue(first.deleted)
        assertFalse(first.favorite)
        assertEquals(12, first.position)
    }

    @Test fun `encrypted payload and public metadata have bounded decoding`() {
        assertTrue(runCatching { java.io.ByteArrayInputStream(ByteArray(33)).readBounded(32) }.isFailure)
        assertEquals(32, java.io.ByteArrayInputStream(ByteArray(32)).readBounded(32).size)
        val key = ByteArray(32) { 1 }
        assertTrue(runCatching { Nip44.encrypt(key, "x".repeat(65536)) }.isFailure)
        assertTrue(runCatching { Nip44.decrypt(key, "A".repeat(100000)) }.isFailure)
        assertFalse(safeImage("https://user:password@example.org/image.jpg"))
    }

    @Test fun `nip44 matches published independent vectors`() {
        CommunityIdentity("315e59ff51cb9209768cf7da80791ddcaae56ac9775eb25b6dee1234bc5d2268".hexBytes()).use {
            assertEquals(
                "3dfef0ce2a4d80a25e7a328accf73448ef67096f65f79588e358d9a0eb9013f1",
                it.conversationKey("c2f9d9948dc8c7c38321e4b85c8558872eafa0641cd269db76848a6073e69133").hex(),
            )
        }
        val key = "c41c775356fd92eadc63ff5a0dc1da211b268cbea22316767095b2871ea1412d".hexBytes()
        val nonce = "0".repeat(63) + "1"
        val expected = "AgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABee0G5VSK0/9YypIObAtDKfYEAjD35uVkHyB0F4DwrcNaCXlCWZKaArsGrY6M9wnuTMxWfp1RTN9Xga8no+kF5Vsb"
        assertEquals(expected, Nip44.encrypt(key, "a", nonce.hexBytes()))
        assertEquals("a", Nip44.decrypt(key, expected))
        assertTrue(runCatching { Nip44.decrypt(key, expected.dropLast(1) + "a") }.isFailure)
    }

    @Test fun `giftwrap authenticates both layers and binds recipient`() {
        CommunityIdentity().use { author ->
            CommunityIdentity().use { reader ->
                CommunityIdentity().use { stranger ->
                    val rumor = GiftWrap.rumor(
                        author.publicKey,
                        14,
                        "Private progress stays private",
                        listOf(listOf("p", reader.publicKey)),
                    )
                    val wrapped = GiftWrap.wrap(author, reader.publicKey, rumor)
                    assertEquals(rumor, GiftWrap.open(reader, wrapped))
                    assertFalse(wrapped.content.contains(rumor.content))
                    assertNotEquals(author.publicKey, wrapped.pubkey)
                    assertTrue(runCatching { GiftWrap.open(stranger, wrapped) }.isFailure)
                    assertTrue(
                        runCatching {
                            GiftWrap.open(reader, wrapped.copy(content = wrapped.content.dropLast(3)))
                        }.isFailure,
                    )
                }
            }
        }
    }

    @Test fun `accept before request converges and cancellation stays cancelled`() {
        val request = PrivateAction(type = "friend.request", request = "first", revision = 100)
        val accept = PrivateAction(type = "friend.accept", request = "first", revision = 8)
        val ordered = FriendLedger().apply(request, true).apply(accept, false)
        val reversed = FriendLedger().apply(accept, false).apply(request, true)
        assertEquals(ordered, reversed)
        assertTrue(reversed.state("peer", false).accepted)
        val removed = reversed.apply(PrivateAction(type = "friend.remove", revision = 101), true)
        assertFalse(removed.apply(accept, false).state("peer", false).accepted)
        assertFalse(ordered.state("peer", true).accepted)
    }

    @Test fun `crossing requests require an explicit acceptance`() {
        val a = PrivateAction(type = "friend.request", request = "a", revision = 1)
        val b = PrivateAction(type = "friend.request", request = "b", revision = 2)
        val ledger = FriendLedger().apply(a, true).apply(b, false)
        assertFalse(ledger.state("peer", false).accepted)
        assertTrue(
            ledger.apply(
                PrivateAction(type = "friend.accept", request = "b", revision = 3),
                true,
            ).state("peer", false).accepted,
        )
    }

    @Test fun `public profile codec never contains a private key and rejects corruption`() {
        CommunityIdentity().use { identity ->
            val code = ProfileCode.encode(identity.publicKey)
            assertEquals(identity.publicKey, ProfileCode.decode(code))
            assertTrue(runCatching { ProfileCode.decode(code.dropLast(3)) }.isFailure)
            assertFalse(code.contains(identity.exportSecret().hex()))
            val relays = listOf("wss://relay.example.org/inbox", "wss://other.example.org:8443/")
            val link = ProfileCode.link(identity.publicKey, relays)
            assertEquals(identity.publicKey, ProfileCode.decode(link))
            assertEquals(relays, ProfileCode.relayHints(link))
            assertTrue(ProfileCode.relayHints("https://example.org/?relay=wss%3A%2F%2Fbad.example.org").isEmpty())
        }
    }

    @Test fun `rewinds are newer edits and independent clocks have deterministic tie breaking`() {
        val clock = SyncRevision(1000, 0, "a".repeat(32))
        val rewind = clock.next(900, "b".repeat(32))
        assertTrue(rewind > clock)
        assertTrue(rewind.next(900, "b".repeat(32)) > rewind)
    }

    @Test fun `concurrent bookmark and rewind converge without clearing completion`() {
        val ref = SyncReference(source = 1, titleUrl = "/title", itemUrl = "/episode")
        val base = SyncRecord(ref, SyncRevision(10, 0, "a".repeat(32)), seen = true, position = 900)
        val initial = SyncMerge.merge(null, base)
        val bookmark = base.copy(
            revision = SyncRevision(11, 0, "a".repeat(32)),
            bookmark = true,
            edits = setOf(SyncField.Bookmark),
        )
        val rewind = base.copy(
            revision = SyncRevision(12, 0, "b".repeat(32)),
            position = 100,
            edits = setOf(SyncField.Progress),
        )
        val first = SyncMerge.merge(SyncMerge.merge(initial, bookmark), rewind)
        val second = SyncMerge.merge(SyncMerge.merge(initial, rewind), bookmark)
        assertEquals(first, second)
        assertTrue(first.seen)
        assertTrue(first.bookmark)
        assertEquals(100L, first.position)
        assertEquals(first, SyncMerge.merge(first, initial))
    }
}
