package nyanime.telegram.sync

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import nyanime.telegram.number
import nyanime.telegram.objectOrNull
import nyanime.telegram.text
import nyanime.telegram.type

@kotlinx.serialization.Serializable
data class SyncReceipt(val messageId: Long, val serverDate: Long)

@kotlinx.serialization.Serializable
data class SyncDelivery(val packet: SyncPacket, val receipt: SyncReceipt)
data class SyncHistoryPage(val next: Long, val newest: Long, val complete: Boolean)

interface SyncTransport {
    suspend fun verifyArchive()
    suspend fun send(packet: SyncPacket, onQueued: (Long) -> Unit): SyncReceipt
    suspend fun page(
        before: Long = 0,
        after: Long = 0,
        kinds: Set<String>? = null,
        receive: suspend (SyncDelivery) -> Unit,
    ): SyncHistoryPage
}

object SyncWire {
    fun ownedMessage(message: JsonObject, account: Long, chat: Long): Boolean {
        if (account <= 0 || chat >= 0 || message.number("chat_id") != chat || message.number("id") <= 0) return false
        if (message["forward_info"] is JsonObject || message["sending_state"] is JsonObject) return false
        val sender = message.objectOrNull("sender_id") ?: return false
        return when (sender.type()) {
            "messageSenderUser" -> sender.number("user_id") == account
            "messageSenderChat" -> sender.number("chat_id") == chat
            else -> false
        }
    }

    fun privateArchive(group: JsonObject, info: JsonObject): Boolean {
        if (group.objectOrNull("status")?.type() != "chatMemberStatusCreator" ||
            info.number("member_count") != 1L ||
            info.number("linked_chat_id") != 0L ||
            (
                info.text("description") != SyncEnvelope.ARCHIVE_MARKER &&
                    SyncArchiveDescriptor.parse(info.text("description")) == null
                )
        ) {
            return false
        }
        val usernames = group.objectOrNull("usernames")?.get("active_usernames")
        if (usernames != null && usernames.toString() != "[]") return false
        return (group["is_channel"] as? JsonPrimitive)?.booleanOrNull == true
    }

    fun text(message: JsonObject): String? {
        val content = message.objectOrNull("content") ?: return null
        if (content.type() != "messageText") return null
        val text = content.objectOrNull("text")?.text("text") ?: return null
        if (!text.startsWith(SyncEnvelope.MARKER)) return null
        require(text.length <= 4096)
        return text.removePrefix(SyncEnvelope.MARKER)
    }
}
