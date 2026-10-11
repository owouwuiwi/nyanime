package eu.kanade.tachiyomi.ui.player

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlayerLifecycleTest {
    @Test
    fun `opening replacement waits for native teardown rather than main activity resume`() = runTest {
        PlayerLifecycle.acquired()
        val replacement = async(start = CoroutineStart.UNDISPATCHED) { PlayerLifecycle.awaitReleased() }
        try {
            runCurrent()
            assertFalse(replacement.isCompleted)
        } finally {
            PlayerLifecycle.released()
        }
        replacement.await()
        assertTrue(replacement.isCompleted)
    }

    @Test
    fun `closing a received link cancels only the wait and leaves playback ownership intact`() = runTest {
        PlayerLifecycle.acquired()
        try {
            val cancelled = async(start = CoroutineStart.UNDISPATCHED) { PlayerLifecycle.awaitReleased() }
            cancelled.cancel()
            cancelled.join()
            val next = async(start = CoroutineStart.UNDISPATCHED) { PlayerLifecycle.awaitReleased() }
            assertFalse(next.isCompleted)
            next.cancel()
            next.join()
        } finally {
            PlayerLifecycle.released()
        }
        PlayerLifecycle.awaitReleased()
    }
}
