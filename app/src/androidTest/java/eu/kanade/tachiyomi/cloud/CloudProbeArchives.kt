package eu.kanade.tachiyomi.cloud

import android.content.Context
import eu.kanade.tachiyomi.data.cloud.CloudRepository
import kotlinx.coroutines.delay
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

/** Test fixtures only. Never uses the user's configured archive selection or removes keys. */
internal object CloudProbeArchives {
    private const val PREFIX = "Nyanime maintenance test "
    private fun pending(context: Context) = context.getSharedPreferences("cloud-maintenance-probe.pending", 0)
    fun retrySeconds(context: Context): Long = (
        context.getSharedPreferences("telegram.runtime.v1", 0).getLong("retry_until", 0) -
            System.currentTimeMillis() +
            999
        ).coerceAtLeast(0) /
        1000
    fun count(context: Context) = pending(context).all.size
    fun created(context: Context, account: Long, chat: Long, space: String) {
        check(pending(context).edit().putString(space, "$account|$chat").commit())
    }
    fun removed(context: Context, space: String) {
        check(pending(context).edit().remove(space).commit())
    }
    suspend fun cleanup(context: Context): String {
        val wait = retrySeconds(context)
        if (wait > 0) return "WAIT: $wait seconds; no Telegram request sent"
        val cloud = CloudRepository.get(context)
        val client = cloud.client
        val lease = client.retain()
        try {
            val account = cloud.ready()
            try {
                client.request(
                    tdRequest(
                        "loadChats",
                        "chat_list" to tdRequest("chatListMain"),
                        "limit" to JsonPrimitive(100),
                    ),
                )
            } catch (error: TelegramException) {
                if (error.code != 404) throw error
            }
            val response = client.request(
                tdRequest(
                    "getChats",
                    "chat_list" to tdRequest("chatListMain"),
                    "limit" to JsonPrimitive(100),
                ),
            )
            val candidates = (response["chat_ids"] as? JsonArray).orEmpty().mapNotNull {
                it.toString().toLongOrNull()
            }.toMutableSet()
            pending(context).all.values.filterIsInstance<String>().forEach { value ->
                if (value.substringBefore('|') ==
                    account.toString()
                ) {
                    value.substringAfter('|').toLongOrNull()?.let(candidates::add)
                }
            }
            var deleted = 0
            for (id in candidates) {
                val chat = client.request(tdRequest("getChat", "chat_id" to JsonPrimitive(id)))
                if (!chat.text("title").startsWith(PREFIX)) continue
                val type = chat.objectOrNull("type") ?: continue
                if (type.type() != "chatTypeSupergroup" || !type.flag("is_channel")) continue
                val groupId = type.number("supergroup_id")
                val group = client.request(tdRequest("getSupergroup", "supergroup_id" to JsonPrimitive(groupId)))
                if (group.objectOrNull("status")?.type() != "chatMemberStatusCreator") continue
                val info = client.request(tdRequest("getSupergroupFullInfo", "supergroup_id" to JsonPrimitive(groupId)))
                if (!SyncWire.privateArchive(group, info)) continue
                val descriptor = SyncArchiveDescriptor.parse(info.text("description")) ?: continue
                if (chat.text("title") != PREFIX + descriptor.space.take(8)) continue
                val known = pending(context).getString(descriptor.space, null) == "$account|$id"
                if (!known && group.number("date") < System.currentTimeMillis() / 1000 - 4 * 3600) continue
                created(context, account, id, descriptor.space)
                if (deleted < 2) {
                    check(chat.flag("can_be_deleted_for_all_users"))
                    client.request(tdRequest("deleteChat", "chat_id" to JsonPrimitive(id)))
                    removed(context, descriptor.space)
                    deleted++
                    delay(500)
                }
            }
            return "Test archives deleted: $deleted; pending: ${count(context)}"
        } finally {
            client.release(lease)
        }
    }
}
