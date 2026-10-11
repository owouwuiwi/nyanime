package eu.kanade.tachiyomi.data.reading

import eu.kanade.tachiyomi.data.watch.WatchMessage
import eu.kanade.tachiyomi.data.watch.WatchMessageType
import eu.kanade.tachiyomi.data.watch.watchJson
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReadingSessionTest {
    private val hostId = "a".repeat(64)
    private val guestId = "b".repeat(64)
    private val chapter = ReadingChapter(7, "/title/one", "/chapter/one", "Sample title", "Chapter one")

    private inner class Pairing(val scope: TestScope) {
        var offline = false
        val choices = mutableListOf<ReadingSelection?>()
        val hostSent = mutableListOf<ReadingSessionEnvelope>()
        val guestSent = mutableListOf<ReadingSessionEnvelope>()
        lateinit var host: ReadingSessionController
        lateinit var guest: ReadingSessionController
        val members = mapOf(hostId to 1, guestId to 1)
        init {
            host = ReadingSessionController(
                scope.backgroundScope,
                { scope.testScheduler.currentTime },
                { scope.testScheduler.currentTime },
                { value, target ->
                    hostSent += value
                    if (!offline && target == guestId) guest.receive(hostId, value)
                },
                {},
            )
            guest = ReadingSessionController(
                scope.backgroundScope,
                { scope.testScheduler.currentTime },
                { scope.testScheduler.currentTime },
                { value, _ ->
                    guestSent += value
                    if (!offline) host.receive(guestId, value)
                },
                { choices += it },
            )
            host.roomChanged(ReadingSessionContext(true, "room-one", hostId, hostId, true, members, true))
            guest.roomChanged(ReadingSessionContext(true, "room-one", guestId, hostId, false, members, true))
        }
    }

    @Test fun sharedChapterOpensWithoutPageCountAndDoesNotRepeatAfterReconnect() = runTest {
        val pair = Pairing(this)
        assertTrue(pair.host.choose(chapter))
        assertEquals(chapter, pair.guest.state.value.selection?.chapter)
        assertEquals(ReadingOpening.Received, pair.host.state.value.openings[guestId])
        pair.guest.receipt(ReadingOpening.Opening)
        assertEquals(ReadingOpening.Opening, pair.host.state.value.openings[guestId])
        pair.guest.receipt(ReadingOpening.Ready)
        assertEquals(ReadingOpening.Ready, pair.host.state.value.openings[guestId])
        pair.offline = true
        advanceTimeBy(5000)
        pair.offline = false
        advanceTimeBy(4000)
        runCurrent()
        assertEquals(1, pair.choices.size)
    }

    @Test fun guestsProposeAndOnlyDelegatesCanChoose() = runTest {
        val pair = Pairing(this)
        assertFalse(pair.guest.choose(chapter))
        assertTrue(pair.guest.propose(chapter))
        assertNull(pair.host.state.value.selection)
        assertEquals(chapter, pair.host.state.value.proposals.single().chapter)
        pair.host.acceptProposal(pair.host.state.value.proposals.single())
        pair.host.grant(guestId, true)
        assertTrue(pair.guest.choose(chapter.copy(chapter = "/chapter/two")))
        assertEquals("/chapter/two", pair.host.state.value.selection?.chapter?.chapter)
        pair.host.grant(guestId, false)
        assertFalse(pair.guest.choose(chapter))
    }

    @Test fun recoveryKeepsGrantsAndNewRequestsCannotReuseOldGuestIds() = runTest {
        val pair = Pairing(this)
        pair.host.grant(guestId, true)
        pair.guest.propose(chapter)
        val oldRequest = pair.guestSent.last().request
        pair.host.roomChanged(
            ReadingSessionContext(true, "room-one", hostId, hostId, true, mapOf(hostId to 1), true),
        )
        assertTrue(guestId in pair.host.state.value.permissions)
        pair.host.roomChanged(ReadingSessionContext(true, "room-one", hostId, hostId, true, pair.members, true))
        pair.guest.roomChanged(ReadingSessionContext())
        pair.guest.roomChanged(
            ReadingSessionContext(true, "room-one", guestId, hostId, false, pair.members, true, 1L shl 32),
        )
        advanceTimeBy(2500)
        runCurrent()
        assertTrue(pair.guest.propose(chapter.copy(chapter = "/chapter/recovered")))
        assertTrue(pair.guestSent.last().request > oldRequest)
        assertEquals("/chapter/recovered", pair.host.state.value.proposals.single().chapter.chapter)
    }

    @Test fun racingChoicesAndReplayedCommandsNeverOverwriteNewerSelection() = runTest {
        val pair = Pairing(this)
        pair.host.choose(chapter)
        pair.host.grant(guestId, true)
        pair.offline = true
        assertTrue(pair.guest.choose(chapter.copy(chapter = "/chapter/guest")))
        val delayed = pair.guestSent.last()
        pair.host.choose(chapter.copy(chapter = "/chapter/host"))
        pair.offline = false
        pair.host.receive(guestId, delayed)
        pair.host.receive(guestId, delayed)
        assertEquals("/chapter/host", pair.host.state.value.selection?.chapter?.chapter)
        assertTrue(pair.guest.state.value.commandFailed)
        val stale = pair.hostSent.first { it.selection != null }
        pair.guest.receive(hostId, stale)
        assertEquals(pair.host.state.value.selection, pair.guest.state.value.selection)
    }

    @Test fun offlineCommandsExpireAndCannotBecomeNewSelectionsLater() = runTest {
        val pair = Pairing(this)
        pair.offline = true
        pair.guest.propose(chapter)
        val request = pair.guestSent.last()
        advanceTimeBy(8500)
        runCurrent()
        assertFalse(pair.guest.state.value.pending)
        assertTrue(pair.guest.state.value.commandFailed)
        pair.offline = false
        pair.host.receive(guestId, request)
        assertTrue(pair.host.state.value.proposals.isEmpty())
        assertNull(pair.host.state.value.selection)
    }

    @Test fun roomChangeResetsPermissionsAndOldOwnerIsIgnored() = runTest {
        val pair = Pairing(this)
        pair.host.choose(chapter)
        pair.host.grant(guestId, true)
        val snapshot = pair.hostSent.last()
        val newOwner = "c".repeat(64)
        pair.guest.roomChanged(
            ReadingSessionContext(
                true,
                "room-two",
                guestId,
                newOwner,
                false,
                mapOf(newOwner to 1, guestId to 1),
                true,
            ),
        )
        pair.guest.receive(hostId, snapshot)
        assertNull(pair.guest.state.value.selection)
        assertTrue(pair.guest.state.value.permissions.isEmpty())
    }

    @Test fun personalReadingAndLegacyPeersDoNotReceiveAutomaticChoices() = runTest {
        val pair = Pairing(this)
        pair.guest.personal(true)
        pair.host.choose(chapter)
        assertTrue(pair.guest.state.value.personal)
        assertEquals(ReadingOpening.Personal, pair.host.state.value.openings[guestId])
        pair.host.roomChanged(
            ReadingSessionContext(
                true,
                "room-one",
                hostId,
                hostId,
                true,
                pair.members + ("c".repeat(64) to 0),
                true,
            ),
        )
        assertEquals(setOf("c".repeat(64)), pair.host.state.value.legacyMembers)
        pair.guest.personal(false)
        assertFalse(pair.guest.state.value.personal)
    }

    @Test fun optionalSessionFieldLeavesLegacyAnnotationProtocolUnchanged() {
        val message = WatchMessage(
            type = WatchMessageType.Status,
            sequence = 1,
            at = 0,
            readingVersion = 2,
            readingSessionVersion = 1,
            target = guestId,
            readingSession = ReadingSessionEnvelope(
                kind = ReadingSessionKind.Snapshot,
                revision = 1,
                selection = ReadingSelection(1, chapter),
            ),
        )
        assertTrue(message.valid())
        assertEquals(message, watchJson.decodeFromString<WatchMessage>(watchJson.encodeToString(message)))
        assertFalse(message.copy(readingSessionVersion = 0).valid())
        assertTrue(message.copy(readingSession = null, readingSessionVersion = 0).valid())
    }

    @Test fun aLateResolverCannotOpenContentFromAnEarlierMediumOrRoom() {
        val gate = RoomNavigationGeneration()
        val first = gate.select("one", "reading:1")
        assertEquals(first, gate.select("one", "reading:1"))
        val second = gate.select("one", "video:2")
        assertFalse(gate.current(first))
        assertTrue(gate.current(second))
        gate.select("two", "video:2")
        assertFalse(gate.current(second))
        val last = gate.select("two", "reading:3")
        gate.cancel()
        assertFalse(gate.current(last))
    }

    @Test fun catalogIdentityRequiresVerifiedIdsAndRejectsConflictingCatalogs() {
        assertFalse(chapter.sameCatalog(chapter))
        val verified = chapter.copy(catalogIds = mapOf("anilist" to 12, "myanimelist" to 34))
        assertTrue(
            verified.sameCatalog(chapter.copy(source = 9, manga = "/other", catalogIds = mapOf("anilist" to 12))),
        )
        assertFalse(verified.sameCatalog(chapter.copy(catalogIds = mapOf("anilist" to 12, "myanimelist" to 35))))
        assertFalse(chapter.copy(catalogIds = mapOf("unknown" to 3)).valid())
    }
}
