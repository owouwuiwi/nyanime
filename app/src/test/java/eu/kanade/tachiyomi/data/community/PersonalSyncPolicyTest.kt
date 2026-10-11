package eu.kanade.tachiyomi.data.community

import eu.kanade.tachiyomi.BuildConfig
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PersonalSyncPolicyTest {
    @Test
    fun `private sync is explicit and incognito never captures progress`() {
        assertFalse(BuildConfig.COMMUNITY_ENABLED)
        assertFalse(PersonalSyncPolicy.capture(false, false))
        assertFalse(PersonalSyncPolicy.capture(false, true))
        assertFalse(PersonalSyncPolicy.capture(true, true))
        assertTrue(PersonalSyncPolicy.capture(true, false))
        assertNotEquals("community.identity", PersonalSyncPolicy.VAULT)
        assertNotEquals("community-v1.db", PersonalSyncPolicy.DATABASE)
        assertNotEquals("nyanime.community.local.v1", PersonalSyncPolicy.KEY_ALIAS)
    }

    @Test
    fun `personal ingress rejects profiles posts presence strangers and social commands`() {
        CommunityIdentity().use { owner ->
            CommunityIdentity().use { stranger ->
                val address = "nyanime.sync.v1:" + "d".repeat(32) + ":" + "f".repeat(64)
                val privateEvent = NostrEvent.create(owner, 30078, "ciphertext", listOf(listOf("d", address)))
                assertTrue(PersonalSyncPolicy.event(privateEvent, owner.publicKey))
                assertFalse(PersonalSyncPolicy.event(privateEvent.copy(pubkey = stranger.publicKey), owner.publicKey))
                for (kind in listOf(0, 1, 7, 10002, 10050, 30315)) {
                    assertFalse(PersonalSyncPolicy.event(NostrEvent.create(owner, kind, "public"), owner.publicKey))
                }
                assertFalse(
                    PersonalSyncPolicy.event(
                        privateEvent.copy(tags = listOf(listOf("d", "nyanime.profile.v1"))),
                        owner.publicKey,
                    ),
                )
                val command = GiftWrap.rumor(owner.publicKey, 30079, "device command")
                assertTrue(PersonalSyncPolicy.event(GiftWrap.wrap(owner, owner.publicKey, command), owner.publicKey))
                assertFalse(
                    PersonalSyncPolicy.event(GiftWrap.wrap(owner, stranger.publicKey, command), owner.publicKey),
                )
                assertTrue(PersonalSyncPolicy.command("device.offer"))
                assertTrue(PersonalSyncPolicy.command("device.ack"))
                assertFalse(PersonalSyncPolicy.command("friend.request"))
                assertFalse(PersonalSyncPolicy.command("presence"))
                assertFalse(PersonalSyncPolicy.command("device.unrecognized"))
            }
        }
    }

    @Test
    fun `encrypted progress can resume on a second copy of the identity without public content references`() {
        CommunityIdentity().use { first ->
            val secret = first.exportSecret()
            try {
                CommunityIdentity(secret).use { second ->
                    val record = record("/chapter/4", manga = true, history = 100, position = 12)
                    val key = first.conversationKey(first.publicKey)
                    val content = Nip44.encrypt(key, communityJson.encodeToString(record))
                    val event = NostrEvent.create(
                        first,
                        30078,
                        content,
                        listOf(
                            listOf(
                                "d",
                                "nyanime.sync.v1:" + Nip44.hmac(key, record.ref.key().toByteArray()).hex(),
                            ),
                        ),
                    )
                    assertFalse(communityJson.encodeToString(event).contains(record.ref.itemUrl))
                    assertFalse(communityJson.encodeToString(event).contains(record.title))
                    val recovered = communityJson.decodeFromString<SyncRecord>(
                        Nip44.decrypt(second.conversationKey(second.publicKey), event.content),
                    )
                    assertEquals(record, recovered)
                    assertTrue(event.valid())
                }
            } finally {
                secret.fill(0)
            }
        }
    }

    @Test
    fun `resume shows the latest unfinished item per title and respects rewinds`() {
        val first = record("/episode/1", history = 10, position = 80_000)
        val later = record("/episode/2", history = 20, position = 9000)
        val manga = record("/chapter/1", manga = true, history = 30, position = 4)
        val records = listOf(first, later, manga, record("/done", history = 40).copy(seen = true))
        assertEquals(listOf(manga, later), PersonalSyncPolicy.resume(records))
        val rewind = later.copy(
            position = 1000,
            revision = later.revision.copy(millis = 200),
            edits = setOf(SyncField.Progress),
        )
        assertEquals(1000L, SyncMerge.merge(later, rewind).position)
        assertTrue(PersonalSyncPolicy.resume(listOf(later.copy(deleted = true))).isEmpty())
    }

    @Test
    fun `pairing code contains ephemeral identity and distinguishes personal from community setup`() {
        val now = System.currentTimeMillis()
        val code =
            PairingCode(
                "a".repeat(64),
                "b".repeat(64),
                now + 60_000,
                listOf("wss://relay.example.org"),
                PersonalSyncPolicy.PURPOSE,
            )
        assertEquals(PersonalSyncPolicy.PURPOSE, PairingCode.parse(code.encode()).purpose)
        assertNotEquals(PairingCode(code.key, code.nonce, code.until, code.relays).purpose, code.purpose)
        assertFalse(code.copy(until = now - 1).valid())
    }

    @Test
    fun `first sync prioritizes last nights unfinished episode over the completed library`() {
        val yesterday = record("/episode/2", history = 50_000, position = 9000)
        val now = 86_400_000L
        assertEquals(2, PersonalSyncPolicy.deliveryPriority(yesterday, now))
        assertEquals(0, PersonalSyncPolicy.deliveryPriority(yesterday.copy(seen = true), now))
        assertEquals(
            2,
            PersonalSyncPolicy.deliveryPriority(yesterday.copy(seen = true, edits = setOf(SyncField.Progress)), now),
        )
        assertEquals(0, PersonalSyncPolicy.deliveryPriority(yesterday.copy(history = 0, position = 0), now))
    }

    private fun record(item: String, manga: Boolean = false, history: Long, position: Long = 0) = SyncRecord(
        ref = SyncReference(manga, 42, "/example-title", item),
        revision = SyncRevision(100, 0, "a".repeat(32)),
        title = "Private library title",
        history = history,
        position = position,
    )
}
