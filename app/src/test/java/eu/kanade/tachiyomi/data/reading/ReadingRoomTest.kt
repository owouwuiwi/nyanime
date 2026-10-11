package eu.kanade.tachiyomi.data.reading

import eu.kanade.tachiyomi.data.watch.WatchMember
import eu.kanade.tachiyomi.data.watch.WatchMessage
import eu.kanade.tachiyomi.data.watch.WatchMessageType
import eu.kanade.tachiyomi.data.watch.WatchRoomState
import eu.kanade.tachiyomi.data.watch.roomTextForTest
import eu.kanade.tachiyomi.data.watch.testRoomText
import eu.kanade.tachiyomi.data.watch.watchJson
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReadingRoomTest {
    private val hostId = "a".repeat(64)
    private val guestId = "b".repeat(64)
    private val page = ReadingPosition(7, "/manga/title", "/chapter/one", "Test manga", "Capitolo uno", 2, 24)
    private fun stroke(
        id: Int,
        author: String = guestId,
    ) = ReadingStroke(id.toString(16).padStart(32, '0'), author, 0, 2, listOf(0, 0, 10000, 10000))

    private inner class Pairing(scope: TestScope, crdt: Boolean = false, guestLanguage: String = "it") {
        var drop = false
        var duplicate = false
        var loseNextAcknowledgement = false
        val hostMessages = mutableListOf<Pair<ReadingEnvelope, String>>()
        val guestMessages = mutableListOf<ReadingEnvelope>()
        val host: ReadingRoomController
        val guest: ReadingRoomController
        init {
            host =
                ReadingRoomController(scope.backgroundScope, testRoomText, {
                    scope.testScheduler.currentTime
                }) { value, target ->
                    hostMessages.add(value to target)
                    if (loseNextAcknowledgement && value.acknowledgement > 0) {
                        loseNextAcknowledgement = false
                    } else if (!drop && (target.isBlank() || target == guestId)) {
                        deliverToGuest(value)
                    }
                }
            guest =
                ReadingRoomController(scope.backgroundScope, roomTextForTest(guestLanguage), {
                    scope.testScheduler.currentTime
                }) { value, _ ->
                    guestMessages.add(value)
                    if (!drop) {
                        host.receive(guestId, value, false)
                        if (duplicate) host.receive(guestId, value, false)
                    }
                }
            val members = listOf(WatchMember(hostId, "A", false, false), WatchMember(guestId, "B", false, false))
            host.roomChanged(
                WatchRoomState(
                    active = true,
                    host = true,
                    localMemberId = hostId,
                    invite = "same-code",
                    relayCount = 2,
                    readingVersion = if (crdt) 2 else 1,
                    members = members,
                ),
            )
            guest.roomChanged(
                WatchRoomState(
                    active = true,
                    localMemberId = guestId,
                    readingSupported = true,
                    readingVersion = if (crdt) 2 else 1,
                    invite = "same-code",
                    relayCount = 2,
                    members = members,
                ),
            )
            host.position(page, true)
            guest.position(page.copy(page = 8), true)
        }
        private fun deliverToGuest(value: ReadingEnvelope) {
            guest.receive(hostId, value, true)
        }
    }

    @Test fun crdtRecoversOfflineNotesAndModeration() = runTest {
        val pair = Pairing(this, crdt = true)
        runCurrent()
        pair.drop = true
        assertTrue(pair.guest.draw(page, stroke(1)))
        assertTrue(pair.guest.addNote(page, 2000, 3000, "Guarda qui"))
        advanceTimeBy(6000)
        runCurrent()
        assertTrue(pair.host.state.value.strokes(page).isEmpty())
        pair.drop = false
        advanceTimeBy(12000)
        runCurrent()
        assertEquals(listOf(stroke(1)), pair.host.state.value.strokes(page))
        assertEquals("Guarda qui", pair.host.state.value.notes(page).single().text)
        assertEquals(pair.guest.state.value.notes(page), pair.host.state.value.notes(page))
        assertTrue(pair.host.setVisible(page, stroke(1).id, false))
        advanceTimeBy(3000)
        runCurrent()
        assertTrue(pair.guest.state.value.strokes(page).isEmpty())
        assertTrue(pair.host.setVisible(page, stroke(1).id, true))
        advanceTimeBy(3000)
        runCurrent()
        assertEquals(listOf(stroke(1)), pair.guest.state.value.strokes(page))
    }

    @Test fun guestRestoresJournalBeforeHostAdvertisesReadingVersion() = runTest {
        val guest = ReadingRoomController(backgroundScope, testRoomText, { testScheduler.currentTime }) { _, _ -> }
        val joining = WatchRoomState(
            active = true,
            localMemberId = guestId,
            invite = "same-code",
            relayCount = 1,
        )
        guest.roomChanged(joining)
        val id = "1".repeat(32)
        guest.restore(
            listOf(
                ReadingEdit(
                    id,
                    guestId,
                    page,
                    1,
                    ReadingEditKind.Note,
                    note = ReadingNote(id, guestId, 1000, 2000, "Salvata"),
                ),
            ),
        )
        assertTrue(guest.state.value.notes(page).isEmpty())
        guest.roomChanged(joining.copy(readingSupported = true, readingVersion = 2))
        assertEquals("Salvata", guest.state.value.notes(page).single().text)
    }

    @Test fun crdtRecoversOfflinePageTheHostHasNeverOpened() = runTest {
        val pair = Pairing(this, crdt = true)
        val distantPage = page.copy(page = 19)
        runCurrent()
        pair.drop = true
        assertTrue(pair.guest.draw(distantPage, stroke(8)))
        advanceTimeBy(6000)
        runCurrent()
        pair.drop = false
        advanceTimeBy(12000)
        runCurrent()
        assertEquals(listOf(stroke(8)), pair.host.state.value.strokes(distantPage))
    }

    @Test fun rejectedDrawingKeepsItsExplanationAfterLostAcknowledgement() = runTest {
        val pair = Pairing(this)
        runCurrent()
        repeat(12) { pair.guest.draw(page, stroke(it + 1)) }
        pair.loseNextAcknowledgement = true
        pair.guest.draw(page, stroke(13))
        assertEquals(1, pair.guest.state.value.pending.size)
        advanceTimeBy(2001)
        runCurrent()
        assertTrue(pair.guest.state.value.pending.isEmpty())
        assertTrue(pair.guest.state.value.notice.contains("12"))
        assertEquals(12, pair.guest.state.value.strokes(page).size)
    }

    @Test fun annotationErrorsUseTheGuestsLanguage() = runTest {
        val pair = Pairing(this, guestLanguage = "en")
        runCurrent()
        repeat(12) { pair.guest.draw(page, stroke(it + 1)) }
        pair.guest.draw(page, stroke(13))
        assertEquals(
            "This page already has 12 sketches. Clear some before continuing.",
            pair.guest.state.value.notice,
        )
        assertEquals(12, pair.guest.state.value.strokes(page).size)
    }

    @Test fun reorderedPresenceCannotMoveAReaderBackwards() = runTest {
        val pair = Pairing(this)
        runCurrent()
        pair.host.receive(
            guestId,
            ReadingEnvelope(
                kind = ReadingKind.Presence,
                peer = ReadingPeer("B", page.copy(page = 20), true),
                revision = 50,
            ),
            false,
        )
        pair.host.receive(
            guestId,
            ReadingEnvelope(
                kind = ReadingKind.Presence,
                peer = ReadingPeer("B", page.copy(page = 1), true),
                revision = 49,
            ),
            false,
        )
        assertEquals(20, pair.host.state.value.members[guestId]?.position?.page)
    }

    @Test fun reorderedRosterCannotMoveRemoteReadersBackwards() = runTest {
        val pair = Pairing(this)
        runCurrent()
        val newest = ReadingEnvelope(
            kind = ReadingKind.Roster,
            members = mapOf(
                hostId to ReadingPeer("A", page.copy(page = 21), true),
                guestId to ReadingPeer("B", page.copy(page = 8), true),
            ),
            revision = 100,
        )
        pair.guest.receive(hostId, newest, true)
        pair.guest.receive(
            hostId,
            newest.copy(members = mapOf(hostId to ReadingPeer("A", page, true)), revision = 99),
            true,
        )
        assertEquals(21, pair.guest.state.value.members[hostId]?.position?.page)
        assertEquals(2, pair.guest.state.value.members.size)
    }

    @Test fun expiredPageCannotBeRestoredByAnOldSnapshot() = runTest {
        val pair = Pairing(this)
        runCurrent()
        val first = page.copy(page = 0, pages = 40)
        pair.host.draw(first, stroke(1, hostId))
        val old = pair.hostMessages.last { it.first.kind == ReadingKind.Board && it.second.isBlank() }.first
        repeat(33) { index ->
            pair.host.draw(first.copy(page = index + 1), stroke(index + 2, hostId))
        }
        assertEquals(32, pair.host.state.value.boards.size)
        assertEquals(32, pair.guest.state.value.boards.size)
        assertFalse(first.pageKey in pair.guest.state.value.boards)
        pair.guest.receive(hostId, old, true)
        assertFalse(first.pageKey in pair.guest.state.value.boards)
    }

    @Test fun localReturnBookmarksNeverBecomeWireIdentifiers() {
        val bookmark = ReadingBookmark(12, 34, page.copy(source = 0))
        assertTrue(bookmark.valid())
        assertFalse(bookmark.position.valid())
        val message = WatchMessage(
            type = WatchMessageType.Status,
            sequence = 1,
            at = 1,
            reading = ReadingEnvelope(kind = ReadingKind.Presence, peer = ReadingPeer("A", page, true)),
        )
        val encoded = watchJson.encodeToString(message)
        listOf("mangaId", "chapterId", "imageUrl", "cookies", "headers").forEach {
            assertFalse(encoded.contains("\"$it\""))
        }
    }

    @Test fun independentPositionsInSameRoom() = runTest {
        val pair = Pairing(this)
        runCurrent()
        assertEquals(2, pair.guest.state.value.members[hostId]?.position?.page)
        assertEquals(8, pair.host.state.value.members[guestId]?.position?.page)
        pair.guest.position(page.copy(page = 19), true)
        advanceTimeBy(501)
        runCurrent()
        assertEquals(2, pair.host.state.value.members[hostId]?.position?.page)
        assertEquals(19, pair.host.state.value.members[guestId]?.position?.page)
        assertEquals(pair.host.state.value.invite, pair.guest.state.value.invite)
    }

    @Test fun changingMangaKeepsMembership() = runTest {
        val pair = Pairing(this)
        runCurrent()
        pair.guest.position(page.copy(manga = "/another", chapter = "/another/one", title = "Another"), true)
        advanceTimeBy(501)
        runCurrent()
        assertEquals(2, pair.host.state.value.members.size)
        assertEquals("Test manga", pair.host.state.value.members[hostId]?.position?.title)
        assertEquals("Another", pair.host.state.value.members[guestId]?.position?.title)
    }

    @Test fun duplicateStrokeIsAppliedOnceAndAcknowledged() = runTest {
        val pair = Pairing(this)
        runCurrent()
        pair.duplicate = true
        pair.guest.draw(page, stroke(1))
        assertEquals(listOf(stroke(1)), pair.host.state.value.strokes(page))
        assertEquals(listOf(stroke(1)), pair.guest.state.value.strokes(page))
        assertTrue(pair.guest.state.value.pending.isEmpty())
    }

    @Test fun offlineSketchesRemainPendingAndRecover() = runTest {
        val pair = Pairing(this)
        runCurrent()
        pair.drop = true
        pair.guest.draw(page, stroke(1))
        pair.guest.draw(page, stroke(2))
        advanceTimeBy(16000)
        runCurrent()
        assertEquals(2, pair.guest.state.value.pending.size)
        assertFalse(pair.guest.state.value.connected)
        pair.drop = false
        advanceTimeBy(4000)
        runCurrent()
        assertTrue(pair.guest.state.value.connected)
        assertTrue(pair.guest.state.value.pending.isEmpty())
        assertEquals(listOf(stroke(1), stroke(2)), pair.host.state.value.strokes(page))
    }

    @Test fun oldSnapshotDoesNotResurrectClearedSketches() = runTest {
        val pair = Pairing(this)
        runCurrent()
        pair.guest.draw(page, stroke(1))
        val old = pair.hostMessages.last { it.first.kind == ReadingKind.Board && it.first.page == page }.first
        pair.guest.clear(page)
        pair.guest.receive(hostId, old, true)
        assertTrue(pair.guest.state.value.strokes(page).isEmpty())
        assertTrue(pair.host.state.value.strokes(page).isEmpty())
    }

    @Test fun guestCanClearOnlyOwnDrawings() = runTest {
        val pair = Pairing(this)
        runCurrent()
        pair.host.draw(page, stroke(1, hostId))
        pair.guest.draw(page, stroke(2))
        pair.guest.clear(page)
        assertEquals(listOf(stroke(1, hostId)), pair.host.state.value.strokes(page))
        pair.host.clear(page)
        assertTrue(pair.guest.state.value.strokes(page).isEmpty())
    }

    @Test fun authorSpoofingAndUnadmittedSendersAreRejected() = runTest {
        val pair = Pairing(this)
        runCurrent()
        val bad = ReadingEnvelope(kind = ReadingKind.Ink, page = page, operation = 1, stroke = stroke(1, hostId))
        pair.host.receive(guestId, bad, false)
        pair.host.receive("c".repeat(64), bad, false)
        assertTrue(pair.host.state.value.strokes(page).isEmpty())
        pair.guest.receive(
            guestId,
            ReadingEnvelope(kind = ReadingKind.Board, page = page, revision = 100, strokes = listOf(stroke(1))),
            false,
        )
        assertTrue(pair.guest.state.value.strokes(page).isEmpty())
    }

    @Test fun boundedQueueAndPageGiveVisibleFeedback() = runTest {
        val pair = Pairing(this)
        runCurrent()
        repeat(13) { pair.guest.draw(page, stroke(it + 1)) }
        assertEquals(12, pair.host.state.value.strokes(page).size)
        assertTrue(pair.guest.state.value.notice.contains("12"))
        assertTrue(pair.guest.state.value.pending.isEmpty())
        pair.drop = true
        repeat(16) { assertTrue(pair.guest.draw(page.copy(page = 3), stroke(it + 30))) }
        assertFalse(pair.guest.draw(page.copy(page = 3), stroke(90)))
        assertEquals(16, pair.guest.state.value.pending.size)
    }

    @Test fun editionsSourcesChaptersAndPagesHaveSeparateDrawings() = runTest {
        val pair = Pairing(this)
        runCurrent()
        pair.guest.draw(page, stroke(1))
        assertTrue(pair.guest.state.value.strokes(page.copy(pages = 25)).isEmpty())
        assertTrue(pair.guest.state.value.strokes(page.copy(source = 8)).isEmpty())
        assertTrue(pair.guest.state.value.strokes(page.copy(page = 3)).isEmpty())
        assertTrue(pair.guest.state.value.strokes(page.copy(chapter = "/chapter/two")).isEmpty())
    }

    @Test fun closingClearsMemoryAndIgnoresDelayedMessages() = runTest {
        val pair = Pairing(this)
        runCurrent()
        pair.guest.draw(page, stroke(1))
        val old = pair.hostMessages.last { it.first.kind == ReadingKind.Board }.first
        pair.guest.roomChanged(WatchRoomState())
        pair.guest.receive(hostId, old, true)
        assertFalse(pair.guest.state.value.active)
        assertTrue(pair.guest.state.value.boards.isEmpty())
        assertTrue(pair.guest.state.value.pending.isEmpty())
    }

    @Test fun incognitoCanClearPositionWithoutLeaving() = runTest {
        val pair = Pairing(this)
        runCurrent()
        pair.guest.position(null, false)
        advanceTimeBy(501)
        runCurrent()
        assertNull(pair.host.state.value.members[guestId]?.position)
        assertTrue(pair.guest.state.value.active)
    }

    @Test fun invalidCoordinatesAndOversizedPayloadsAreRejected() {
        assertFalse(stroke(1).copy(points = listOf(-1, 0, 0, 0)).valid())
        assertFalse(stroke(1).copy(points = List(194) { 1 }).valid())
        assertFalse(stroke(1).copy(color = 99).valid())
        assertFalse(page.copy(page = 24).valid())
        assertFalse(page.copy(pages = Int.MAX_VALUE).valid())
        assertFalse(
            ReadingEnvelope(
                kind = ReadingKind.Ink,
                page = page,
                operation = 1,
                clear = true,
                stroke = stroke(1),
            ).valid(),
        )
        val max =
            ReadingEnvelope(
                kind = ReadingKind.Board,
                page = page.copy(manga = "m".repeat(1024), chapter = "c".repeat(1024)),
                strokes = List(12) {
                    stroke(it).copy(points = List(192) { 10000 })
                },
            )
        val message = WatchMessage(type = WatchMessageType.Status, sequence = 1, at = 1, reading = max)
        assertTrue(message.valid())
        assertTrue(watchJson.encodeToString(message).toByteArray().size < 23500)
    }

    @Test fun transformsRoundTripInEveryLayout() {
        ReadingPageLayout.entries.forEach { layout ->
            listOf(ReadingPoint(0.12f, 0.23f), ReadingPoint(0.86f, 0.77f)).forEach { displayed ->
                val original = layout.original(displayed.x, displayed.y)
                val again = layout.displayed(original)
                assertNotNull(again)
                assertEquals(displayed.x, again!!.x, 0.0001f, layout.name)
                assertEquals(displayed.y, again.y, 0.0001f, layout.name)
            }
        }
        assertNull(ReadingPageLayout.Left.displayed(ReadingPoint(0.8f, 0.5f)))
        assertNull(ReadingPageLayout.Right.displayed(ReadingPoint(0.2f, 0.5f)))
    }

    @Test fun longStrokesRetainEndpointsWithinProtocolBudget() {
        val points = mutableListOf<ReadingPoint>()
        repeat(10000) {
            compactReadingPoints(points)
            points.add(ReadingPoint(it / 10000f, 0.5f))
            assertTrue(points.size <= 96)
        }
        assertEquals(0f, points.first().x)
        assertEquals(0.9999f, points.last().x)
    }
}
