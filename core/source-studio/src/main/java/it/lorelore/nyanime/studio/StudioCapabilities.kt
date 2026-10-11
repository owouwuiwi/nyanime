package it.lorelore.nyanime.studio

import it.lorelore.nyanime.source.document.SourceDocumentEntry
import it.lorelore.nyanime.source.telegram.TelegramSourceMessage
import it.lorelore.nyanime.source.telegram.TelegramSourceReader
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jsoup.Jsoup
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Optional brokers. Existing web sources and executor callers need no new capability. */
interface StudioServices {
    suspend fun telegram(source: StudioSource): TelegramSourceReader =
        error("Authorize Telegram access first")

    suspend fun document(
        source: StudioSource,
        resolver: String,
        reference: String,
    ): StudioTextDocument = error("Install and authorize the required document component")

    suspend fun documents(
        source: StudioSource,
        resolver: String,
        reference: String,
    ): List<SourceDocumentEntry> = error("Install and authorize the required document component")

    companion object {
        val Unavailable: StudioServices = object : StudioServices {}
    }
}

/** Invocation-local bytes; trace snapshots contain metadata only. */
data class StudioTextDocument(
    val reference: String,
    val title: String,
    val mimeType: String,
    val bytes: ByteArray,
) {
    init {
        require(reference.length in 1..8192 && title.length <= 2048 && mimeType.length <= 256)
        require(bytes.size <= 4 * 1024 * 1024) { "Text document exceeds the Studio limit" }
    }

    fun text(): String {
        require(
            mimeType.substringBefore(';').lowercase() in
                setOf("text/plain", "text/html", "application/xhtml+xml"),
        ) {
            "This block accepts TXT and HTML documents"
        }
        val (charset, offset) =
            when {
                bytes.size >= 3 &&
                    bytes[0] == 0xEF.toByte() &&
                    bytes[1] == 0xBB.toByte() &&
                    bytes[2] == 0xBF.toByte() -> Charsets.UTF_8 to 3
                bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                    Charsets.UTF_16LE to 2
                bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                    Charsets.UTF_16BE to 2
                else -> Charsets.UTF_8 to 0
            }
        return charset
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset))
            .toString()
            .also {
                require('\u0000' !in it) { "This document is not readable text" }
            }
    }
}

object StudioCapabilities {
    val kinds =
        setOf(
            StudioKind.TELEGRAM_HISTORY,
            StudioKind.TELEGRAM_MESSAGE,
            StudioKind.TELEGRAM_DOCUMENT,
            StudioKind.DOCUMENT_RESOLVE,
            StudioKind.DOCUMENT_LIST,
            StudioKind.DOCUMENT_TEXT,
            StudioKind.NOVEL_BODY,
        )
    val remoteKinds = kinds - setOf(StudioKind.DOCUMENT_TEXT, StudioKind.NOVEL_BODY)

    suspend fun execute(
        source: StudioSource,
        node: StudioNode,
        incoming: Map<String, StudioValue>,
        services: StudioServices,
        expand: (String) -> String,
        base: String,
    ): Map<String, StudioValue> {
        fun text(key: String) = incoming[key]?.value?.studioText().orEmpty()
        fun value(type: StudioType, raw: String) = StudioValue(type, JsonPrimitive(raw))
        fun channel(): String {
            require(source.transport == StudioTransport.TELEGRAM)
            val name = TelegramPublicChannel.name(expand(node.parameters["channel"].orEmpty()))
            require(name in TelegramPublicChannel.names(source.telegramChannels)) {
                "Declare this channel in source access"
            }
            return name
        }
        fun resolver(): String =
            node.parameters["resolver"].orEmpty().also {
                require(it.isNotBlank() && it in source.documentResolvers) {
                    "Declare the document component dependency"
                }
            }
        fun document(result: StudioTextDocument) =
            mapOf(
                "document" to
                    StudioValue(
                        StudioType.DOCUMENT,
                        buildJsonObject {
                            put("reference", result.reference)
                            put("title", result.title)
                            put("mimeType", result.mimeType)
                            put("size", result.bytes.size)
                        },
                        document = result,
                    ),
            )
        return when (node.kind) {
            StudioKind.TELEGRAM_HISTORY -> {
                val name = channel()
                val page =
                    services
                        .telegram(source)
                        .history(
                            name,
                            text("cursor")
                                .ifBlank { expand(node.parameters["cursor"].orEmpty()) }
                                .takeIf(String::isNotBlank),
                            node.parameters["limit"]?.toIntOrNull()?.also { require(it in 1..100) }
                                ?: 50,
                        )
                mapOf(
                    "messages" to
                        StudioValue(StudioType.LIST, JsonArray(page.messages.map(::message))),
                    "cursor" to value(StudioType.TEXT, page.nextCursor.orEmpty()),
                    "hasNext" to
                        StudioValue(StudioType.BOOLEAN, JsonPrimitive(page.nextCursor != null)),
                )
            }
            StudioKind.TELEGRAM_MESSAGE -> {
                val name = channel()
                val id =
                    text("message")
                        .ifBlank { expand(node.parameters["message"].orEmpty()) }
                        .toLong()
                mapOf(
                    "value" to
                        StudioValue(
                            StudioType.JSON,
                            message(services.telegram(source).message(name, id)),
                        ),
                )
            }
            StudioKind.TELEGRAM_DOCUMENT -> {
                require(source.transport == StudioTransport.TELEGRAM)
                val result = services.telegram(source).document(text("reference"))
                document(
                    StudioTextDocument(result.reference, result.name, result.mimeType, result.bytes),
                )
            }
            StudioKind.DOCUMENT_RESOLVE ->
                document(services.document(source, resolver(), text("reference")))
            StudioKind.DOCUMENT_LIST -> {
                val items = services.documents(source, resolver(), text("reference"))
                require(items.size <= 2000) { "Collection exceeds the Studio limit" }
                mapOf(
                    "value" to
                        StudioValue(
                            StudioType.LIST,
                            JsonArray(
                                items.map {
                                    buildJsonObject {
                                        put("url", it.reference)
                                        put("title", it.title)
                                        put("mimeType", it.mimeType)
                                    }
                                },
                            ),
                        ),
                )
            }
            StudioKind.DOCUMENT_TEXT -> {
                val result =
                    requireNotNull(incoming["document"]?.document) { "Connect a document block" }
                val declaredMime = node.parameters["mimeType"]?.takeIf(String::isNotBlank)
                val textDocument = declaredMime?.let { result.copy(mimeType = it) } ?: result
                mapOf(
                    "value" to value(StudioType.TEXT, textDocument.text()),
                    "title" to value(StudioType.TEXT, result.title),
                    "mimeType" to value(StudioType.TEXT, textDocument.mimeType),
                )
            }
            StudioKind.NOVEL_BODY -> {
                if (node.parameters["format"] == "TEXT") {
                    return mapOf(
                        "value" to StudioValue(
                            StudioType.JSON,
                            buildJsonObject {
                                put("body", text("document"))
                                put("format", "TEXT")
                                put("resources", JsonArray(emptyList()))
                            },
                        ),
                    )
                }
                val page = Jsoup.parse(text("document"), base)
                val selected =
                    page.selectFirst(
                        expand(node.parameters["selector"].orEmpty()).ifBlank { "body" },
                    ) ?: error("The selected text body was not found")
                // The reader remains the security boundary for HTML. Only declared web resources
                // are materialized.
                val resources =
                    selected
                        .select("img[src]")
                        .mapNotNull { image ->
                            image
                                .absUrl("src")
                                .takeIf { it.startsWith("https://") || it.startsWith("http://") }
                                ?.let { url ->
                                    image.attr("src", url)
                                    buildJsonObject {
                                        put("htmlReference", url)
                                        put("reference", url)
                                    }
                                }
                        }
                        .distinct()
                        .also { require(it.size <= 200) { "Too many illustrations" } }
                mapOf(
                    "value" to
                        StudioValue(
                            StudioType.JSON,
                            buildJsonObject {
                                put("body", selected.html())
                                put("format", "HTML")
                                put("resources", JsonArray(resources))
                            },
                        ),
                )
            }
            else -> error("Not a capability block")
        }
    }

    private fun message(item: TelegramSourceMessage): JsonObject = buildJsonObject {
        put("channelId", item.channelId)
        put("messageId", item.messageId)
        put("text", item.text)
        put("publishedAt", item.publishedAt?.let(::JsonPrimitive) ?: JsonNull)
        put("editedAt", item.editedAt?.let(::JsonPrimitive) ?: JsonNull)
        put("links", JsonArray(item.links.map(::JsonPrimitive)))
        put(
            "document",
            item.document?.let { document ->
                buildJsonObject {
                    put("reference", document.reference)
                    put("title", document.name)
                    put("mimeType", document.mimeType)
                    put("size", document.size?.let(::JsonPrimitive) ?: JsonNull)
                }
            } ?: JsonNull,
        )
    }
}
