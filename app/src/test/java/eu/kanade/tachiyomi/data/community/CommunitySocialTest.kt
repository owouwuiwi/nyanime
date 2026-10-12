package eu.kanade.tachiyomi.data.community

import eu.kanade.tachiyomi.data.watch.WatchIdentity
import eu.kanade.tachiyomi.data.watch.WatchInvite
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommunitySocialTest {
    private val now = 1_800_000_000_000L
    private val activity = SocialActivity("c".repeat(32), "A shared story", "Episode 4")
    private val request = SocialWatchRequest("d".repeat(32), "a".repeat(64), "b".repeat(64), activity, now + 180_000)

    @Test fun `activity invitations travel only in authenticated private envelopes`() {
        CommunityIdentity().use { sender ->
            CommunityIdentity().use { host ->
                val invite = request.copy(requester = sender.publicKey, host = host.publicKey)
                val action = PrivateAction(
                    type = "watch.request",
                    peer = host.publicKey,
                    body = communityJson.encodeToString(invite),
                    expires = invite.expires,
                )
                val rumor = GiftWrap.rumor(sender.publicKey, 30079, communityJson.encodeToString(action))
                val wrapped = GiftWrap.wrap(sender, host.publicKey, rumor)
                val wire = communityJson.encodeToString(wrapped)
                assertFalse(wire.contains(activity.title))
                assertFalse(wire.contains(activity.item))
                assertFalse(wire.contains(activity.token))
                val received = GiftWrap.open(host, wrapped)
                assertEquals(sender.publicKey, received.pubkey)
                val decoded = communityJson.decodeFromString<PrivateAction>(received.content)
                assertEquals(invite, communityJson.decodeFromString<SocialWatchRequest>(decoded.body))
            }
        }
    }

    @Test fun `only the requested host can accept the exact unexpired episode`() {
        val identity = WatchIdentity()
        try {
            val accepted = request.copy(
                status = WatchRequestStatus.Accepted,
                invite = WatchInvite.create(identity.publicKey, now).encode(),
            )
            assertTrue(request.valid(now))
            assertTrue(request.acceptsResponse(accepted, request.host, now))
            assertFalse(request.acceptsResponse(accepted, request.requester, now))
            assertFalse(
                request.acceptsResponse(accepted.copy(activity = activity.copy(item = "Episode 5")), request.host, now),
            )
            assertFalse(request.acceptsResponse(accepted.copy(invite = "broken"), request.host, now))
            assertFalse(request.acceptsResponse(accepted, request.host, request.expires))
            assertFalse(accepted.acceptsResponse(accepted, request.host, now))
        } finally {
            identity.clear()
        }
    }

    @Test fun `cancellation and rejection cannot be spoofed by the other party`() {
        val cancelled = request.copy(status = WatchRequestStatus.Cancelled)
        val declined = request.copy(status = WatchRequestStatus.Declined)
        assertTrue(request.acceptsResponse(cancelled, request.requester, now))
        assertTrue(request.acceptsResponse(declined, request.host, now))
        assertFalse(request.acceptsResponse(cancelled, request.host, now))
        assertFalse(request.acceptsResponse(declined, request.requester, now))
        assertFalse(cancelled.acceptsResponse(declined, request.host, now))
    }

    @Test fun `public presence has no content location or private progress`() {
        val serialized = communityJson.encodeToString(SocialPresence("Watching", now + 90_000, activity))
        listOf("source", "titleUrl", "itemUrl", "position", "cookie", "stream").forEach {
            assertFalse(serialized.contains(it, ignoreCase = true))
        }
        assertEquals(activity, communityJson.decodeFromString<SocialPresence>(serialized).activity)
        assertFalse(request.copy(activity = activity.copy(manga = true)).valid(now))
        assertFalse(request.copy(expires = now + 181_000).valid(now))
    }

    @Test fun `local image drafts cannot escape into public events or bypass content limits`() {
        val image = "nyanime-image:" + "a".repeat(64)
        val profile = CommunityProfile("b".repeat(64), "Reader", avatar = image)
        assertTrue(profile.validDraft())
        assertFalse(profile.valid())
        assertTrue(SocialPost(image = image).validDraft())
        assertFalse(SocialPost(image = image).valid())
        assertFalse(SocialPost(text = "x".repeat(4001), image = image).validDraft())
        assertFalse(profile.copy(avatar = "nyanime-image:../../private").validDraft())
    }
}
