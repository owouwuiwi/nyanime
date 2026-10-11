package nyanime.telegram

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

val telegramJson = Json { ignoreUnknownKeys = true }

fun tdRequest(type: String, vararg fields: Pair<String, JsonElement>): JsonObject = buildJsonObject {
    put("@type", type)
    fields.forEach { (name, value) -> put(name, value) }
}

fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
fun JsonObject.number(key: String): Long = (get(key) as? JsonPrimitive)?.longOrNull ?: 0L
fun JsonObject.flag(key: String): Boolean = (get(key) as? JsonPrimitive)?.booleanOrNull == true
fun JsonObject.objectOrNull(key: String): JsonObject? = get(key) as? JsonObject
fun JsonObject.type(): String = text("@type")

data class TelegramAccount(val id: Long, val name: String, val username: String)

data class TelegramState(
    val authorization: String = "disconnected",
    val connection: String = "",
    val account: TelegramAccount? = null,
    val qrLink: String = "",
    val hint: String = "",
    val resendAt: Long = 0,
    val failure: TelegramFailure? = null,
) {
    val ready: Boolean get() = authorization == "authorizationStateReady" && account != null
    val online: Boolean get() = ready && connection == "connectionStateReady"
}

enum class TelegramFailure {
    CONFIGURATION,
    NATIVE_UNAVAILABLE,
    DISCONNECTED,
    RATE_LIMIT,
    REQUEST,
    ARCHIVE_UNSAFE,
    ARCHIVE_MISSING,
    PHONE_INVALID,
    CODE_INVALID,
    CODE_EXPIRED,
    PASSWORD_INVALID,
    EMAIL_INVALID,
    API_REJECTED,
    CLIENT_OUTDATED,
    PHONE_REJECTED,
    AUTH_UNAVAILABLE,
}

/** Keep protocol errors useful without forwarding private server messages into UI or logs. */
object TelegramErrors {
    fun retryable(error: TelegramException): Boolean = error.failure in
        setOf(TelegramFailure.DISCONNECTED, TelegramFailure.RATE_LIMIT) ||
        error.failure == TelegramFailure.REQUEST &&
        (error.code == 408 || error.code in 500..599)

    fun parse(error: JsonObject): TelegramException {
        val code = error.number("code").toInt()
        val message = error.text("message")
        val wait = (
            Regex("(?:FLOOD_WAIT_|retry after )(\\d+)", RegexOption.IGNORE_CASE)
                .find(message)?.groupValues?.get(1)?.toLongOrNull() ?: if (code == 429) 60 else 0
            ).coerceIn(0, 86_400)
        val failure = when {
            wait > 0 -> TelegramFailure.RATE_LIMIT
            code in setOf(400, 403, 404) &&
                message in
                setOf(
                    "CHANNEL_PRIVATE",
                    "CHANNEL_INVALID",
                    "CHAT_ID_INVALID",
                    "Chat not found",
                    "Supergroup not found",
                    "Can't access the chat",
                ) -> TelegramFailure.ARCHIVE_MISSING
            message == "PHONE_NUMBER_INVALID" -> TelegramFailure.PHONE_INVALID
            message in listOf(
                "PHONE_CODE_INVALID",
                "PHONE_CODE_EMPTY",
                "EMAIL_CODE_INVALID",
            ) -> TelegramFailure.CODE_INVALID
            message == "PHONE_CODE_EXPIRED" -> TelegramFailure.CODE_EXPIRED
            message == "PASSWORD_HASH_INVALID" -> TelegramFailure.PASSWORD_INVALID
            message == "EMAIL_ADDRESS_INVALID" -> TelegramFailure.EMAIL_INVALID
            message in setOf("API_ID_INVALID", "API_ID_PUBLISHED_FLOOD") -> TelegramFailure.API_REJECTED
            message == "UPDATE_APP_TO_LOGIN" -> TelegramFailure.CLIENT_OUTDATED
            message == "PHONE_NUMBER_BANNED" -> TelegramFailure.PHONE_REJECTED
            message in setOf("PHONE_NUMBER_APP_SIGNUP_FORBIDDEN", "SMS_CODE_CREATE_FAILED") ->
                TelegramFailure.AUTH_UNAVAILABLE
            else -> TelegramFailure.REQUEST
        }
        return TelegramException(failure, wait, code, diagnostic(error))
    }

    /** Fixed protocol identifiers only: arbitrary server text can contain private data. */
    fun diagnostic(error: JsonObject): String {
        val message = error.text("message")
        return when {
            message in DIAGNOSTICS -> message
            message == "Can't find group call participant" -> "CALL_PARTICIPANT_MISSING"
            message == "Can't unmute user" -> "CALL_UNMUTE_FORBIDDEN"
            message == "Can't mute user" -> "CALL_MUTE_FORBIDDEN"
            message.startsWith("FLOOD_WAIT_") || message.startsWith("retry after ", ignoreCase = true) -> "RATE_LIMIT"
            message.startsWith("AUTH_RESTART") -> "AUTH_RESTART"
            message.startsWith("Can't parse") || message.startsWith("Failed to parse") -> "REQUEST_SCHEMA"
            else -> "UNCLASSIFIED"
        }
    }

    private val DIAGNOSTICS = setOf(
        "API_ID_INVALID", "API_ID_PUBLISHED_FLOOD", "UPDATE_APP_TO_LOGIN", "PHONE_NUMBER_BANNED",
        "PHONE_NUMBER_APP_SIGNUP_FORBIDDEN", "SMS_CODE_CREATE_FAILED", "PHONE_NUMBER_FLOOD",
        "PHONE_PASSWORD_FLOOD", "PHONE_PASSWORD_PROTECTED", "PHONE_NUMBER_INVALID", "PHONE_CODE_INVALID",
        "PHONE_CODE_EMPTY", "EMAIL_CODE_INVALID", "PHONE_CODE_EXPIRED", "PASSWORD_HASH_INVALID",
        "EMAIL_ADDRESS_INVALID",
        "GROUPCALL_JOIN_MISSING", "GROUPCALL_FORBIDDEN", "GROUPCALL_INVALID", "GROUPCALL_VIDEO_TOO_MUCH",
        "CHAT_ADMIN_REQUIRED", "GROUPCALL_SSRC_DUPLICATE_MUCH", "GROUPCALL_NOT_MODIFIED",
    )
}

class TelegramException(
    val failure: TelegramFailure,
    val retryAfterSeconds: Long = 0,
    val code: Int = 0,
    val diagnostic: String = "UNCLASSIFIED",
    val mayHaveBeenSent: Boolean = true,
) : Exception(
    failure.name,
)

interface TelegramGateway {
    val state: StateFlow<TelegramState>
    suspend fun connect()
    suspend fun request(request: JsonObject): JsonObject
    suspend fun close()
}

interface TelegramAccountRepository {
    val state: StateFlow<TelegramState>
    suspend fun connect()
    suspend fun submit(value: String)
    suspend fun requestQr()
    suspend fun logout()
}

// Application features use stable Telegram identifiers, never nicknames as identity.
data class TelegramConversation(val accountId: Long, val chatId: Long)

interface TelegramMessagingGateway {
    suspend fun sendText(conversation: TelegramConversation, text: String): Long
}

interface NyanimeDirectory {
    suspend fun setVisible(visible: Boolean)
    suspend fun findUsers(query: String): List<TelegramAccount>
}
