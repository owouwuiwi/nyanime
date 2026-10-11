package eu.kanade.tachiyomi.data.watch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WatchPlayerAttachmentTest {
    private class Player : WatchPlayer {
        var released = false
        var reads = 0
        val media = WatchMedia("Test title", "Episode 1", 1.0, 1400.0)
        override fun sample(): WatchPlayback {
            check(!released) { "Native player has been released" }
            reads++
            return WatchPlayback(
                media, 125.0, false, true, false, 1.25,
                ended = true, upcoming = media.copy(number = 2.0), canAdvance = true,
            )
        }
        override fun pause(paused: Boolean) {}
        override fun seek(seconds: Double) {}
        override fun speed(value: Double) {}
    }

    @Test
    fun closingSoloPlaybackDoesNotSampleAReleasedPlayer() {
        val attachment = WatchPlayerAttachment()
        val player = Player()
        attachment.attach(player)
        player.released = true
        attachment.detach()
        attachment.forgetDetached()
        assertNull(attachment.player)
        assertNull(attachment.sample().media)
        assertEquals(0, player.reads)
    }

    @Test
    fun closingSharedPlaybackKeepsOnlySafeCachedState() {
        val attachment = WatchPlayerAttachment()
        val player = Player()
        attachment.attach(player)
        val playing = attachment.sample()
        player.released = true
        attachment.detach()
        val detached = attachment.sample()
        assertEquals(playing.media, detached.media)
        assertEquals(playing.position, detached.position)
        assertTrue(detached.paused)
        assertFalse(detached.ready)
        assertFalse(detached.ended)
        assertFalse(detached.canAdvance)
        assertNull(detached.upcoming)
        assertEquals(1, player.reads)
        attachment.detach()
        assertEquals(detached, attachment.sample())
    }

    @Test
    fun aNewPlayerCannotInheritThePreviousEpisodesCachedState() {
        val attachment = WatchPlayerAttachment()
        attachment.attach(Player())
        attachment.sample()
        attachment.detach()
        val next = Player()
        attachment.attach(next)
        next.released = true
        attachment.detach()
        assertNull(attachment.sample().media)
        assertEquals(0, next.reads)
    }

    @Test
    fun forgettingDetachedStateDoesNotDisconnectAnActivePlayer() {
        val attachment = WatchPlayerAttachment()
        val player = Player()
        attachment.attach(player)
        attachment.forgetDetached()
        assertEquals(player, attachment.player)
        assertTrue(attachment.sample().ready)
    }
}
