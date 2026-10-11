package eu.kanade.tachiyomi.data.cloud.sync

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import nyanime.telegram.TelegramException
import nyanime.telegram.TelegramFailure
import nyanime.telegram.TelegramGateway
import nyanime.telegram.sync.SyncArchiveDescriptor
import nyanime.telegram.tdRequest
import nyanime.telegram.text
import nyanime.telegram.type
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

class CloudSyncDirectoryTest {
    private val client = mockk<TelegramGateway>()
    private val space = UUID.randomUUID().toString()
    private val directory = CloudSyncDirectory(client)
    private fun base() {
        coEvery { client.request(match { it.type() == "loadChats" }) } throws
            TelegramException(TelegramFailure.REQUEST, code = 404)
        coEvery { client.request(match { it.type() == "getChats" }) } returns
            tdRequest("chats", "chat_ids" to JsonArray(listOf(JsonPrimitive(-1))))
        coEvery { client.request(match { it.type() == "getChat" }) } returns
            tdRequest(
                "chat",
                "id" to JsonPrimitive(-1),
                "type" to
                    tdRequest(
                        "chatTypeSupergroup",
                        "supergroup_id" to JsonPrimitive(1),
                        "is_channel" to JsonPrimitive(true),
                    ),
            )
        coEvery { client.request(match { it.type() == "getSupergroup" }) } returns
            tdRequest(
                "supergroup",
                "status" to tdRequest("chatMemberStatusCreator"),
                "is_channel" to JsonPrimitive(true),
            )
    }

    @Test fun deletedCachedChannelDoesNotBlockReplacement() = runBlocking {
        base()
        coEvery { client.request(match { it.type() == "getSupergroupFullInfo" }) } throws
            TelegramException(TelegramFailure.ARCHIVE_MISSING)
        coEvery { client.request(match { it.type() == "createNewSupergroupChat" }) } returns
            tdRequest("chat", "id" to JsonPrimitive(-2))
        assertEquals(-2L, directory.create(space).chat)
        coVerify(exactly = 1) { client.request(match { it.type() == "createNewSupergroupChat" }) }
    }

    @Test fun networkFailureNeverCreatesADuplicateChannel() {
        base()
        coEvery { client.request(match { it.type() == "getSupergroupFullInfo" }) } throws
            TelegramException(TelegramFailure.DISCONNECTED)
        assertThrows(TelegramException::class.java) { runBlocking { directory.create(space) } }
        coVerify(exactly = 0) { client.request(match { it.type() == "createNewSupergroupChat" }) }
    }

    @Test fun previouslyCreatedReplacementIsReusedAfterRestart() = runBlocking {
        base()
        coEvery { client.request(match { it.type() == "getSupergroupFullInfo" }) } returns
            tdRequest(
                "supergroupFullInfo",
                "description" to JsonPrimitive(SyncArchiveDescriptor(space, 0).encode()),
                "member_count" to JsonPrimitive(1),
                "linked_chat_id" to JsonPrimitive(0),
            )
        assertEquals(-1L, directory.create(space).chat)
        coVerify(exactly = 0) { client.request(match { it.type() == "createNewSupergroupChat" }) }
    }
}
