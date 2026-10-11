package eu.kanade.tachiyomi.data.watch

import eu.kanade.tachiyomi.data.watch.testRoomText
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WatchFeedbackTest {
    @Test
    fun pausedConnectionRecoveryStillShowsProgressWithoutRequestingPlay() {
        val room = WatchRoomState(active = true, phase = WatchPhase.Reconnecting)
        assertTrue(room.showPreparationFeedback)
        assertFalse(room.wantsPlayback)
        assertFalse(room.copy(active = false).showPreparationFeedback)
        assertFalse(room.copy(phase = WatchPhase.Paused).showPreparationFeedback)
    }

    private val waiting = WatchRoomState(
        active = true,
        phase = WatchPhase.Buffering,
        playRequested = true,
        relayCount = 2,
        localMemberId = "local",
        members = listOf(
            WatchMember("local", "Io", false, true),
            WatchMember("friend", "Marco", false, true),
        ),
    )

    @Test
    fun waitingCaptionDistinguishesLocalLoadingAndTheActualFriend() {
        assertEquals("Aspettiamo Marco…", waiting.preparationCaption(testRoomText))
        assertEquals("Il tuo video sta caricando…", waiting.preparationCaption(testRoomText, localLoading = true))
        assertEquals("Marco", waiting.copy(waitForEveryone = false).waitingFor?.name)
        assertEquals(null, waiting.copy(waitForEveryone = false, phase = WatchPhase.Playing).waitingFor)
        assertEquals(WatchRecovery.None, waiting.copy(waitingSeconds = 14).recovery)
        assertEquals(WatchRecovery.Details, waiting.copy(waitingSeconds = 15).recovery)
        assertEquals(WatchRecovery.None, waiting.copy(phase = WatchPhase.DifferentVideo, waitingSeconds = 0).recovery)
    }

    @Test
    fun commandFailuresAndConnectionFailuresHaveDifferentRecoveryActions() {
        assertEquals(WatchRecovery.Command, waiting.copy(commandFailed = true).recovery)
        assertEquals(WatchRecovery.Details, waiting.copy(commandFailed = true, localHold = true).recovery)
        assertEquals(WatchRecovery.Details, waiting.copy(commandFailed = true, sharedControls = false).recovery)
        assertEquals(WatchRecovery.None, waiting.copy(relayCount = 0, waitingSeconds = 11).recovery)
        assertEquals(WatchRecovery.Connection, waiting.copy(relayCount = 0, waitingSeconds = 12).recovery)
        assertEquals(WatchRecovery.None, waiting.copy(active = false, commandFailed = true).recovery)
    }

    @Test
    fun activityIsOptionalForExistingClientsAndRejectsInvalidValues() {
        val previous = """{"type":"Timeline","sequence":1,"at":1000,"coordinationVersion":2}"""
        assertEquals(null, watchJson.decodeFromString<WatchMessage>(previous).activity)
        val activity = WatchActivity(1, 1000, "a".repeat(64), "Marco", "seek", "episode", 3661.0)
        val message = WatchMessage(type = WatchMessageType.Timeline, sequence = 1, at = 1000, activity = activity)
        assertTrue(message.valid())
        assertEquals(activity, watchJson.decodeFromString<WatchMessage>(watchJson.encodeToString(message)).activity)
        assertEquals("Marco è andato a 1:01:01", activity.label(testRoomText))
        assertFalse(activity.copy(value = Double.NaN).valid())
        assertFalse(activity.copy(command = "unknown").valid())
        assertFalse(activity.copy(name = "x".repeat(33)).valid())
        assertFalse(message.copy(activity = activity.copy(actorId = "invalid")).valid())
    }
}
