package eu.kanade.tachiyomi.data.watch

import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WatchProtocolCompatibilityTest {
    @Test
    fun previousInvitesAndStatusMessagesKeepTheirProtocolVersion() {
        val parsed = watchJson.decodeFromString<WatchMessage>(
            """{"type":"Status","sequence":1,"at":0,"coordinationVersion":2}""",
        )
        assertTrue(parsed.valid())
        assertEquals(1, parsed.version)
        assertEquals(2, parsed.coordinationVersion)
    }

    @Test
    fun telegramMetadataFromAnOlderPeerIsIgnoredAndNeverRetransmitted() {
        val parsed = watchJson.decodeFromString<WatchMessage>(
            """
            {
                "type":"Timeline","sequence":2,"at":1000,"paused":false,"position":42.0,
                "telegramUserId":5,
                "telegramGroup":{"chatId":-10,"ownerId":5,"members":[5,6]},
                "peers":{
                    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa":{"name":"Friend","ready":true,"buffering":false,"telegramUserId":6}
                }
            }
            """.trimIndent(),
        )
        assertTrue(parsed.valid())
        assertFalse(parsed.paused)
        assertEquals(42.0, parsed.position)
        assertEquals("Friend", parsed.peers.values.single().name)
        assertFalse(watchJson.encodeToString(parsed).contains("telegram", ignoreCase = true))
    }

    @Test
    fun ignoredCapabilitiesDoNotDisableCoreMessageValidation() {
        val parsed = watchJson.decodeFromString<WatchMessage>(
            """{"type":"Status","sequence":0,"at":0,"telegramUserId":5}""",
        )
        assertFalse(parsed.valid())
    }

    @Test
    fun previousMessagesDoNotFalselyAdvertiseObserverSupport() {
        val previous = watchJson.decodeFromString<WatchMessage>(
            """{"type":"Status","sequence":1,"at":0,"ready":true}""",
        )
        assertTrue(previous.valid())
        assertEquals(0, previous.observerVersion)
        assertFalse(previous.observer)
        assertFalse(previous.copy(observer = true).valid())
        assertTrue(previous.copy(observer = true, observerVersion = 1, ready = false).valid())
        assertFalse(previous.copy(observerVersion = 2).valid())
    }
}
