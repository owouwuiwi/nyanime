package it.lorelore.nyanime.source.telegram

/** Optional capability. Ordinary novel, anime and manga source contracts remain unchanged. */
interface TelegramBackedSource {
    /** Public usernames chosen by the source author, reviewed before granting access. */
    val telegramChannels: List<String>

    /** The host supplies a revocable reader, never its account session or raw Telegram client. */
    fun attachTelegram(reader: TelegramSourceReader)
}

interface TelegramSourceReader {
    suspend fun history(
        channel: String,
        cursor: String? = null,
        limit: Int = 50,
    ): TelegramMessagePage

    suspend fun message(channel: String, messageId: Long): TelegramSourceMessage

    /** Reads only a document that belongs to a message in an authorized channel. */
    suspend fun document(reference: String): TelegramSourceDocument
}

data class TelegramMessagePage(
    val messages: List<TelegramSourceMessage>,
    val nextCursor: String? = null,
) {
    init {
        require(messages.size <= 100 && (nextCursor == null || nextCursor.length <= 256))
    }
}

data class TelegramSourceMessage(
    val channelId: Long,
    val messageId: Long,
    val text: String,
    val publishedAt: Long?,
    val editedAt: Long? = null,
    val links: List<String> = emptyList(),
    val document: TelegramSourceAttachment? = null,
) {
    init {
        require(channelId != 0L && messageId > 0 && text.length <= 65_536)
        require(publishedAt == null || publishedAt >= 0)
        require(editedAt == null || editedAt >= 0)
        require(links.size <= 100 && links.all { it.length in 1..8192 })
    }
}

data class TelegramSourceAttachment(
    val reference: String,
    val name: String,
    val mimeType: String,
    val size: Long?,
) {
    init {
        require(reference.length in 1..512 && name.length <= 1024 && mimeType.length <= 256)
        require(size == null || size >= 0)
    }
}

data class TelegramSourceDocument(
    val reference: String,
    val name: String,
    val mimeType: String,
    val bytes: ByteArray,
) {
    init {
        require(reference.length in 1..512 && name.length <= 1024 && mimeType.length <= 256)
        require(bytes.size <= MAX_DOCUMENT_BYTES) { "Document exceeds the source limit" }
    }

    companion object {
        const val MAX_DOCUMENT_BYTES = 4 * 1024 * 1024
    }
}
