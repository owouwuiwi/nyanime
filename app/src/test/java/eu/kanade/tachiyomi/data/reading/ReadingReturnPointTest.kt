package eu.kanade.tachiyomi.data.reading

import eu.kanade.tachiyomi.data.watch.watchJson
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ReadingReturnPointTest {
    private val room = "a".repeat(64)
    private val otherRoom = "b".repeat(64)
    private val own = ReadingBookmark(11, 12, ReadingPosition(7, "/title/one", "/chapter/one", "Sample", "One", 3, 20))

    @Test fun ordinaryReadingCannotCreateOrExposeAReturnPoint() {
        val state = ReadingReturnPoint()
        state.remember(own)
        assertNull(state.bookmark)
        assertNull(state.checkpoint)
    }

    @Test fun visitingSeveralParticipantsKeepsTheOriginalPoint() {
        val state = ReadingReturnPoint()
        state.roomChanged(room)
        state.remember(own)
        state.remember(own.copy(position = own.position.copy(page = 10)))
        assertEquals(own, state.bookmark)
    }

    @Test fun restoredCheckpointStaysHiddenUntilExplicitReentry() {
        val saved = watchJson.encodeToString(ReadingReturnCheckpoint(room, own))
        val state = ReadingReturnPoint(watchJson.decodeFromString<ReadingReturnCheckpoint>(saved))
        assertNull(state.bookmark)
        state.roomChanged(null)
        state.remember(own.copy(position = own.position.copy(page = 8)))
        assertNull(state.bookmark)
        state.roomChanged(room)
        assertEquals(own, state.bookmark)
    }

    @Test fun interruptedRoomCanRecoverWithoutLeakingIntoOrdinaryReading() {
        val state = ReadingReturnPoint()
        state.roomChanged(room)
        state.remember(own)
        state.roomChanged(null)
        assertNull(state.bookmark)
        state.roomChanged(room)
        assertEquals(own, state.bookmark)
    }

    @Test fun anotherRoomCannotUseOrResurrectThePreviousPoint() {
        val state = ReadingReturnPoint(ReadingReturnCheckpoint(room, own))
        state.roomChanged(otherRoom)
        assertNull(state.bookmark)
        assertNull(state.checkpoint)
        state.roomChanged(room)
        assertNull(state.bookmark)
    }

    @Test fun leavingClosingOrDiscardingRecoveryDeletesTheCheckpoint() {
        val state = ReadingReturnPoint(ReadingReturnCheckpoint(room, own))
        state.roomChanged(room)
        state.clear()
        state.roomChanged(null)
        val reopened = ReadingReturnPoint(state.checkpoint)
        reopened.roomChanged(room)
        assertNull(reopened.bookmark)
        assertNull(reopened.checkpoint)
    }

    @Test fun completedReturnAllowsANewVisitToRememberItsOwnStartingPoint() {
        val state = ReadingReturnPoint()
        state.roomChanged(room)
        state.remember(own)
        state.clear()
        assertNull(state.bookmark)
        val next = own.copy(position = own.position.copy(page = 5))
        state.remember(next)
        assertEquals(next, state.bookmark)
    }

    @Test fun malformedAndLegacyUnscopedCheckpointsCannotBeRecovered() {
        assertNull(ReadingReturnPoint(ReadingReturnCheckpoint("", own)).checkpoint)
        assertNull(ReadingReturnPoint(ReadingReturnCheckpoint(room, own.copy(chapterId = -1))).checkpoint)
        val old = watchJson.encodeToString(own)
        assertThrows(kotlinx.serialization.SerializationException::class.java) {
            watchJson.decodeFromString<ReadingReturnCheckpoint>(old)
        }
    }
}
