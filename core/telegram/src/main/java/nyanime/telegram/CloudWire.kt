package nyanime.telegram

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** Accept only our own complete document messages in the chosen private archive. */
object CloudWire {
    fun manifest(message: JsonObject, account: Long, chat: Long): CloudManifest? {
        if (message.number("chat_id") != chat) return null
        val sender = message.objectOrNull("sender_id") ?: return null
        // Channel posts are authored by the channel. The caller separately verifies sole ownership of this archive.
        val ownedSender = when (sender.type()) {
            "messageSenderUser" -> sender.number("user_id") == account
            "messageSenderChat" -> sender.number("chat_id") == chat
            else -> false
        }
        if (!ownedSender) return null
        val content = message.objectOrNull("content") ?: return null
        if (content.type() != "messageDocument") return null
        return CloudManifest.parse(content.objectOrNull("caption")?.text("text").orEmpty())
    }

    fun copy(message: JsonObject, account: Long, chat: Long): CloudCopy? {
        val manifest = manifest(message, account, chat) ?: return null
        if (message["sending_state"] != null && message["sending_state"] != JsonNull) return null
        val document = message.objectOrNull("content")?.objectOrNull("document") ?: return null
        val file = document.objectOrNull("document") ?: return null
        if (!document.text("file_name").endsWith(".nyabk", true) ||
            file.number("size") != manifest.bytes ||
            message.number("id") <= 0 ||
            message.number("date") <= 0 ||
            file.number("id") <= 0
        ) {
            return null
        }
        return CloudCopy(
            manifest,
            account,
            chat,
            message.number("id"),
            file.number("id"),
            message.number("date"),
            message.flag("is_pinned"),
        )
    }

    fun privateArchive(group: JsonObject, info: JsonObject): Boolean {
        val names = group.objectOrNull("usernames")?.get("active_usernames") as? JsonArray
        return group.objectOrNull("status")?.type() == "chatMemberStatusCreator" &&
            info.number("member_count") == 1L &&
            info.number("linked_chat_id") == 0L &&
            names.isNullOrEmpty() &&
            group.text("username").isBlank() &&
            info.text("description") == CloudManifest.ARCHIVE_MARKER
    }
}
