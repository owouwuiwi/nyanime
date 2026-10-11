package it.lorelore.nyanime.exchange

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI

@Serializable
internal enum class ExchangePhase { IDLE, PREPARING, SHARING, CONNECTING, RECEIVING, REVIEW, FAILED, STOPPED }

@Serializable
internal data class ExchangeFile(
    val name: String,
    val path: String,
    val bytes: Long,
    val hash: String,
) : java.io.Serializable

@Serializable
internal data class ExchangeSnapshot(
    val phase: ExchangePhase = ExchangePhase.IDLE,
    val name: String = "",
    val ticket: String = "",
    val bytes: Long = 0,
    val expected: Long? = null,
    val files: List<ExchangeFile> = emptyList(),
    val error: String = "",
    val expiresAt: Long = 0,
    val internet: Boolean = false,
) {
    val busy get() = phase in
        setOf(ExchangePhase.PREPARING, ExchangePhase.SHARING, ExchangePhase.CONNECTING, ExchangePhase.RECEIVING)
}

internal object ExchangeProtocol {
    val json = Json { ignoreUnknownKeys = false }
    const val OBSERVE = 1
    const val SHARE = 2
    const val RECEIVE = 3
    const val STOP = 4
    const val SNAPSHOT = 5
    const val LIMIT = 128L * 1024 * 1024
    const val LIFETIME = 15L * 60 * 1000
    private val suffixes = setOf("nyastudio", "nyasource", "json", "rin", "apk")

    fun filename(value: String): String {
        require(value.isNotBlank() && value.toByteArray().size <= 256)
        require(value.none { it.isISOControl() || it == '/' || it == '\\' })
        require(value.substringAfterLast('.', "").lowercase() in suffixes)
        return value
    }

    fun ticket(value: String): String {
        require(value.length <= 66000)
        val trimmed = value.trim()
        val ticket = if (trimmed.startsWith("nyanime://")) {
            val uri = URI(trimmed)
            require(uri.host == "sources" && uri.path == "/v1" && uri.rawQuery == null && uri.userInfo == null)
            uri.fragment.orEmpty()
        } else {
            trimmed
        }
        require(ticket.length in 16..65536 && ticket.none(Char::isWhitespace))
        require(ticket.startsWith("fd2:") || ticket.startsWith("blob"))
        return ticket
    }

    fun link(ticket: String) = "nyanime://sources/v1#${ticket(ticket)}"
}
