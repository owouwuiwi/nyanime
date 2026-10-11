package it.lorelore.nyanime.player

import eu.kanade.tachiyomi.ui.player.PlaybackScrubCoordinator
import eu.kanade.tachiyomi.ui.player.PlayerSession
import eu.kanade.tachiyomi.ui.player.PlayerViewModel
import eu.kanade.tachiyomi.ui.player.session.LivePlayback
import eu.kanade.tachiyomi.ui.player.session.MpvPlaybackEngine
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Exercise the real native-event boundary, including delayed decoder acknowledgements. */
class PlayerPauseObservationTest {
    @Test fun `next episode starts after the temporary native track preparation hold`() {
        val player = Fixture(paused = false)
        player.observe(true)
        assertTrue(player.observed.value)
        player.finishLoading()
        assertFalse(player.observed.value)
    }

    @Test fun `quality replacement retains both playing and intentionally paused states`() {
        listOf(false, true).forEach { paused ->
            val player = Fixture(paused)
            repeat(3) { player.observe(true) }
            player.finishLoading()
            assertEquals(paused, player.observed.value)
        }
    }

    @Test fun `a delayed hold acknowledgement cannot overwrite resume during loading`() {
        val player = Fixture(paused = false)
        player.model.pause()
        player.model.unpause()
        player.observe(true)
        player.finishLoading()
        assertFalse(player.observed.value)
    }

    @Test fun `a native resume cannot overwrite an explicit pause during loading`() {
        val player = Fixture(paused = false)
        player.model.pause()
        player.observe(false)
        player.finishLoading()
        assertTrue(player.observed.value)
    }

    @Test fun `stale native acknowledgements never publish an obsolete state`() {
        listOf(false, true).forEach { paused ->
            val player = Fixture(paused)
            player.nativePaused = paused
            player.session.onObserverEvent("pause", !paused)
            assertEquals(paused, player.observed.value)
            verify(exactly = 0) { player.model.onNativePause(any()) }
            verify(exactly = 0) { player.model.pause() }
            verify(exactly = 0) { player.model.unpause() }
        }
    }

    @Test fun `observed pause and resume update presentation without issuing playback commands`() {
        val player = Fixture(paused = false)
        player.finishLoading()
        player.observe(true)
        assertTrue(player.observed.value)
        player.observe(false)
        assertFalse(player.observed.value)
        verify(exactly = 0) { player.model.pause() }
        verify(exactly = 0) { player.model.unpause() }
    }

    /** The model contract distinguishes commands (pending intent) from native observations. */
    private class Fixture(paused: Boolean) {
        val session = mockk<PlayerSession>(relaxed = true)
        val model = mockk<PlayerViewModel>(relaxed = true)
        private val native = mockk<MpvPlaybackEngine>(relaxed = true)
        val observed = MutableStateFlow(paused)
        var nativePaused = paused
        private var loading = true
        private var pending: Boolean? = paused

        init {
            every { session.player } returns native
            every { session.viewModel } returns model
            // The native boundary reads its own internal backing field directly.
            // Initialize that flow without constructing the Android/native session.
            PlayerSession::class.java.getDeclaredField("live").apply {
                isAccessible = true
                set(session, MutableStateFlow<LivePlayback?>(null))
            }
            every { native.isExiting } returns false
            every { native.paused } answers { nativePaused }
            every { model.scrubState } returns MutableStateFlow<PlaybackScrubCoordinator.State?>(null)
            every { model.watchTogether.active } returns false
            every { model.paused } returns observed
            every { model.pause() } answers {
                if (loading) pending = true
                observed.value = true
            }
            every { model.unpause() } answers {
                if (loading) pending = false else observed.value = false
            }
            every { model.onNativePause(any()) } answers { observed.value = firstArg() }
            every { session.onObserverEvent(any(), any<Boolean>()) } answers { callOriginal() }
        }

        fun observe(paused: Boolean) {
            nativePaused = paused
            session.onObserverEvent("pause", paused)
        }

        fun finishLoading() {
            observed.value = requireNotNull(pending)
            pending = null
            loading = false
        }
    }
}
