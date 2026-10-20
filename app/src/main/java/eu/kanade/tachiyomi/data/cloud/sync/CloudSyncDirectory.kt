package eu.kanade.tachiyomi.data.cloud.sync

import eu.kanade.tachiyomi.data.telegram.TelegramClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import nyanime.telegram.TelegramException
import nyanime.telegram.flag
import nyanime.telegram.number
import nyanime.telegram.objectOrNull
import nyanime.telegram.sync.SyncArchiveDescriptor
import nyanime.telegram.sync.SyncWire
import nyanime.telegram.tdRequest
import nyanime.telegram.text
import nyanime.telegram.type

internal data class CloudSyncArchive(val chat: Long, val descriptor: SyncArchiveDescriptor)

/** Archive discovery is performed for setup, never in the player or the periodic one-second loop. */
internal class CloudSyncDirectory(private val client: nyanime.telegram.TelegramGateway) {
    suspend fun discover(): List<CloudSyncArchive> {
        val found = mutableListOf<CloudSyncArchive>()
        for (list in listOf("chatListMain", "chatListArchive")) {
            while (true) {
                try {
                    client.request(
                        tdRequest("loadChats", "chat_list" to tdRequest(list), "limit" to JsonPrimitive(100)),
                    )
                } catch (error: TelegramException) {
                    if (error.code == 404) break else throw error
                }
            }
            val response = client.request(
                tdRequest("getChats", "chat_list" to tdRequest(list), "limit" to JsonPrimitive(100_000)),
            )
            for (id in (response["chat_ids"] as? JsonArray).orEmpty()) {
                val chat = client.request(tdRequest("getChat", "chat_id" to id))
                val type = chat.objectOrNull("type") ?: continue
                if (type.type() != "chatTypeSupergroup" || !type.flag("is_channel")) continue
                val groupId = JsonPrimitive(type.number("supergroup_id"))
                val group = client.request(tdRequest("getSupergroup", "supergroup_id" to groupId))
                if (group.objectOrNull("status")?.type() != "chatMemberStatusCreator") continue
                val info = try {
                    client.request(tdRequest("getSupergroupFullInfo", "supergroup_id" to groupId))
                } catch (error: TelegramException) {
                    if (error.failure == nyanime.telegram.TelegramFailure.ARCHIVE_MISSING) continue else throw error
                }
                val descriptor = SyncArchiveDescriptor.parse(info.text("description")) ?: continue
                if (SyncWire.privateArchive(group, info)) found += CloudSyncArchive(chat.number("id"), descriptor)
            }
        }
        return found.distinctBy { it.chat }
    }

    suspend fun create(space: String): CloudSyncArchive {
        val existing = discover()
        existing.singleOrNull { it.descriptor.space == space }?.let { return it }
        check(existing.none { it.descriptor.space == space }) { "More than one matching archive" }
        check(existing.isEmpty()) { "An archive already exists; associate this device instead" }
        val descriptor = SyncArchiveDescriptor(space, 0)
        val chat = client.request(
            tdRequest(
                "createNewSupergroupChat",
                "title" to JsonPrimitive("Nyanime Sync"),
                "is_channel" to JsonPrimitive(true),
                "is_forum" to JsonPrimitive(false),
                "description" to JsonPrimitive(descriptor.encode()),
                "message_auto_delete_time" to JsonPrimitive(0),
            ),
        )
        return CloudSyncArchive(chat.number("id"), descriptor)
    }

    suspend fun complete(chat: Long, descriptor: SyncArchiveDescriptor) {
        require(descriptor.rootMessage > 0)
        val currentChat = client.request(tdRequest("getChat", "chat_id" to JsonPrimitive(chat)))
        val group = currentChat.objectOrNull("type")?.number("supergroup_id") ?: error("Missing archive")
        val info = client.request(tdRequest("getSupergroupFullInfo", "supergroup_id" to JsonPrimitive(group)))
        val previous = requireNotNull(SyncArchiveDescriptor.parse(info.text("description")))
        require(previous.space == descriptor.space)
        require(previous.rootMessage == 0L || previous.rootMessage == descriptor.rootMessage)
        if (previous.rootMessage == descriptor.rootMessage &&
            previous.maintenanceVersion >= descriptor.maintenanceVersion
        ) {
            return
        }
        // Recovery/setup must never make an already compacted archive readable by legacy clients.
        val completed = descriptor.copy(
            maintenanceVersion = maxOf(
                previous.maintenanceVersion,
                descriptor.maintenanceVersion,
            ),
        )
        client.request(
            tdRequest(
                "setChatDescription",
                "chat_id" to JsonPrimitive(chat),
                "description" to JsonPrimitive(completed.encode()),
            ),
        )
    }
}
