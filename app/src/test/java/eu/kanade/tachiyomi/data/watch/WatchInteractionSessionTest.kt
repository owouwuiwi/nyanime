package eu.kanade.tachiyomi.data.watch

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import nyanime.rooms.voice.RoomVoiceSignal
import nyanime.rooms.voice.RoomVoiceSignalType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WatchInteractionSessionTest {
    private class Room(val scope: TestScope, count: Int = 3) : AutoCloseable {
        val identities = List(count) { WatchIdentity() }
        val ids = identities.map { it.publicKey }
        val invite = WatchInvite.create(ids[0], 1_800_000_000_000)
        var members = ids.associateWith { "Member ${ids.indexOf(it) + 1}" }
        var online = true
        var connected = true
        var epoch = "1".repeat(32)
        var blocked = emptySet<String>()
        val routes = mutableListOf<Pair<String, String>>()
        val captured = mutableListOf<Triple<String, String, WatchInteractionSealed>>()
        var duplicate = false
        var packets = 0
        val sessions: List<WatchInteractionSession> = identities.mapIndexed { index, identity ->
            WatchInteractionSession(scope.backgroundScope, { scope.testScheduler.currentTime }) { target, packet ->
                captured.add(Triple(ids[index], target, packet))
                if (online && target !in blocked) {
                    routes.add(ids[index] to target)
                    packets++
                    val receiver = ids.indexOf(target)
                    sessions[receiver].receive(ids[index], packet)
                    if (duplicate) sessions[receiver].receive(ids[index], packet)
                }
            }.apply { start(WatchInteractionCrypto(invite, identity)) }
        }
        init {
            update()
        }
        fun update() {
            sessions.forEachIndexed { index, session ->
                session.roomChanged(
                    WatchInteractionContext(
                        active = ids[index] in members,
                        roomId = invite.encode(),
                        localId = ids[index],
                        ownerId = ids[0],
                        members = members,
                        connected = connected,
                        epoch = epoch,
                    ),
                )
            }
        }
        fun advance(ms: Long = 3000) {
            scope.advanceTimeBy(ms)
            scope.runCurrent()
        }
        override fun close() {
            sessions.forEach { it.close() }
            identities.forEach { it.clear() }
        }
    }

    @Test
    fun chatIsConfirmedOnceAndUnreadOnlyClearsWhenActuallyVisible() = runTest {
        Room(this).use { room ->
            room.advance()
            room.duplicate = true
            assertTrue(room.sessions[1].sendChat("Hello room"))
            room.advance(5000)
            room.sessions.forEach {
                assertEquals(1, it.state.value.messages.size)
                assertEquals("Hello room", it.state.value.messages.single().text)
                assertEquals(WatchChatDelivery.Sent, it.state.value.messages.single().delivery)
            }
            assertEquals(1, room.sessions[2].state.value.unread)
            room.sessions[2].chatVisible(true)
            assertEquals(0, room.sessions[2].state.value.unread)
            room.sessions[0].sendChat("Another message")
            room.advance()
            assertEquals(0, room.sessions[2].state.value.unread)
        }
    }

    @Test
    fun failedMessagesHaveBoundedRetriesAndManualRetryDoesNotDuplicateThem() = runTest {
        Room(this).use { room ->
            room.advance()
            room.online = false
            room.sessions[1].sendChat("Pending")
            room.advance(15_000)
            assertEquals(WatchChatDelivery.Failed, room.sessions[1].state.value.messages.single().delivery)
            val packets = room.packets
            room.advance(30_000)
            assertEquals(packets, room.packets)
            room.online = true
            room.sessions[1].retryChat(room.sessions[1].state.value.messages.single().id)
            room.advance(5000)
            assertEquals(WatchChatDelivery.Sent, room.sessions[1].state.value.messages.single().delivery)
            assertEquals(1, room.sessions[0].state.value.messages.size)
        }
    }

    @Test
    fun removalChangesRecipientsAndKeysWithoutEndingTheOtherConversations() = runTest {
        Room(this).use { room ->
            room.advance()
            room.sessions[1].setVoice(true)
            room.sessions[2].setVoice(true)
            room.advance()
            assertTrue(room.sessions[0].state.value.voices.getValue(room.ids[1]).joined)
            room.members = room.members - room.ids[1]
            room.update()
            room.sessions[0].sendChat("Only current participants")
            room.advance(5000)
            assertTrue(room.sessions[1].state.value.messages.isEmpty())
            assertEquals("Only current participants", room.sessions[2].state.value.messages.single().text)
            assertFalse(room.sessions[2].state.value.voices.containsKey(room.ids[1]))
            assertFalse(room.sessions[1].sendChat("Not admitted"))
        }
    }

    @Test
    fun noFirstNetworkSendStillFailsAndPreservesDraftUntilActuallySent() = runTest {
        Room(this).use { room ->
            room.connected = false
            room.update()
            room.sessions[1].setDraft("A draft")
            assertEquals("A draft", room.sessions[1].state.value.draft)
            room.sessions[1].sendChat("Offline")
            room.advance(11_000)
            assertEquals(WatchChatDelivery.Failed, room.sessions[1].state.value.messages.single().delivery)
            assertEquals(0, room.packets)
        }
    }

    @Test
    fun hostDeliveryNeedsAllCurrentRecipientsAndLateSuccessCannotHideFailure() = runTest {
        Room(this).use { room ->
            room.advance()
            room.blocked = setOf(room.ids[2])
            room.sessions[0].sendChat("For everyone")
            room.advance(1000)
            assertEquals(WatchChatDelivery.Sending, room.sessions[0].state.value.messages.single().delivery)
            room.advance(11_000)
            assertEquals(WatchChatDelivery.Failed, room.sessions[0].state.value.messages.single().delivery)
            room.blocked = emptySet()
            room.sessions[0].retryChat(room.sessions[0].state.value.messages.single().id)
            room.advance(5000)
            room.sessions.forEach { assertEquals(1, it.state.value.messages.size) }
            assertEquals(WatchChatDelivery.Sent, room.sessions[0].state.value.messages.single().delivery)
        }
    }

    @Test
    fun voiceSignalingIsDirectAndRejectsOldPerJoinSessions() = runTest {
        Room(this).use { room ->
            room.advance()
            room.sessions[1].setVoice(true)
            room.sessions[2].setVoice(true)
            room.advance(5000)
            var calls = 0
            room.sessions[2].onVoiceSignal = { sender, _ ->
                assertEquals(room.ids[1], sender)
                calls++
            }
            val presence = room.sessions[1].state.value.voices
            val signal = RoomVoiceSignal(
                "a".repeat(32),
                1,
                RoomVoiceSignalType.Restart,
                senderSession = presence.getValue(room.ids[1]).session,
                targetSession = presence.getValue(room.ids[2]).session,
            )
            room.routes.clear()
            room.sessions[1].voiceSignal(room.ids[2], watchJson.encodeToString(signal))
            room.advance(1000)
            assertEquals(1, calls)
            assertTrue(room.ids[1] to room.ids[2] in room.routes)
            assertFalse(room.ids[1] to room.ids[0] in room.routes)
            room.sessions[2].setVoice(false)
            room.advance()
            room.sessions[2].setVoice(true)
            room.advance()
            room.sessions[1].voiceSignal(room.ids[2], watchJson.encodeToString(signal))
            room.advance(11_000)
            assertEquals(1, calls)
        }
    }

    @Test
    fun hostRestartRotatesEncryptionContextAndClearsConversation() = runTest {
        Room(this).use { room ->
            room.advance()
            room.sessions[0].sendChat("Old session")
            room.advance()
            val oldPackets = room.captured.toList()
            room.epoch = "2".repeat(32)
            room.update()
            oldPackets.filter { it.second == room.ids[1] }.forEach {
                room.sessions[1].receive(it.first, it.third)
            }
            assertTrue(room.sessions[1].state.value.messages.isEmpty())
            room.sessions[0].sendChat("Fresh session")
            room.advance(5000)
            assertEquals("Fresh session", room.sessions[1].state.value.messages.single().text)
        }
    }

    @Test
    fun eightConcurrentVoiceJoinsConvergeWithoutUnboundedRosterFanout() = runTest {
        Room(this, 8).use { room ->
            room.sessions.forEach { it.setVoice(true) }
            room.advance(9000)
            room.sessions.forEach { session ->
                assertEquals(8, session.state.value.voices.count { it.value.joined })
            }
            assertTrue(room.packets < 200)
        }
    }

    @Test
    fun chatStaysBoundedAndLeavingErasesTheEntireConversation() = runTest {
        val identity = WatchIdentity()
        val invite = WatchInvite.create(identity.publicKey, 1_800_000_000_000)
        val session = WatchInteractionSession(backgroundScope, { testScheduler.currentTime }) { _, _ -> }
        try {
            session.start(WatchInteractionCrypto(invite, identity))
            session.roomChanged(
                WatchInteractionContext(
                    true,
                    invite.encode(),
                    identity.publicKey,
                    identity.publicKey,
                    mapOf(identity.publicKey to "Member"),
                    true,
                    "1".repeat(32),
                ),
            )
            repeat(300) { assertTrue(session.sendChat("Message $it")) }
            assertEquals(200, session.state.value.messages.size)
            assertFalse(session.sendChat("x".repeat(1001)))
            session.close()
            assertTrue(session.state.value.messages.isEmpty())
            assertFalse(session.state.value.supported)
        } finally {
            session.close()
            identity.clear()
        }
    }
}
