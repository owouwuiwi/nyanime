package it.lorelore.nyanime.studio

import it.lorelore.nyanime.source.telegram.TelegramMessagePage
import it.lorelore.nyanime.source.telegram.TelegramSourceDocument
import it.lorelore.nyanime.source.telegram.TelegramSourceMessage
import it.lorelore.nyanime.source.telegram.TelegramSourceReader
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StudioCapabilitiesTest {
    private val noHttp = StudioHttp { _, _, _, _ -> error("No web request expected") }
    private val noScripts = StudioScript { _, _ -> error("No script expected") }

    @Test
    fun `explicit text MIME handles generic attachments without weakening decoding`() = runTest {
        val original = StudioTextDocument("opaque", "Fiction.txt", "application/octet-stream", "A < B".toByteArray())
        val result = StudioCapabilities.execute(
            StudioSource(schema = 2),
            StudioNode(kind = StudioKind.DOCUMENT_TEXT, parameters = mapOf("mimeType" to "text/plain")),
            mapOf("document" to StudioValue(StudioType.DOCUMENT, JsonObject(emptyMap()), document = original)),
            StudioServices.Unavailable,
            { it },
            "",
        )
        assertEquals("A < B", result.getValue("value").value.studioText())
        assertEquals("text/plain", result.getValue("mimeType").value.studioText())
        assertEquals("application/octet-stream", original.mimeType)
        assertThrows(IllegalArgumentException::class.java) { original.text() }
        assertThrows(IllegalArgumentException::class.java) { original.copy(mimeType = "application/pdf").text() }
        assertThrows(IllegalArgumentException::class.java) {
            original.copy(mimeType = "text/plain", bytes = byteArrayOf(65, 0, 66)).text()
        }
        assertThrows(java.nio.charset.CharacterCodingException::class.java) {
            original.copy(mimeType = "text/plain", bytes = byteArrayOf(0xFF.toByte())).text()
        }
    }

    @Test
    fun `schema one encoded bytes and script consent fingerprints keep their previous fields`() {
        val source =
            StudioSource(
                uuid = "00000000-0000-0000-0000-000000000001",
                name = "Fictional",
                baseUrl = "https://source.invalid",
            )
        val encoded = StudioDocuments.encode(source)
        assertFalse(encoded.contains("transport"))
        assertFalse(encoded.contains("telegramChannels"))
        assertFalse(encoded.contains("documentResolvers"))
        assertEquals(source, StudioDocuments.decode(encoded))
        assertEquals(
            source.sourceId,
            source.copy(transport = StudioTransport.TELEGRAM, schema = 2).sourceId,
        )
    }

    @Test
    fun `new capabilities cannot silently enter schema one`() {
        val source = StudioSource(schema = 1, medium = StudioMedium.NOVEL)
        assertThrows(IllegalArgumentException::class.java) {
            StudioDocuments.decode(StudioDocuments.encode(source))
        }
    }

    @Test
    fun `text document uses strict encoding and rejects binary and unsupported formats`() {
        assertEquals(
            "A fictional chapter",
            StudioTextDocument("ref", "Title", "text/plain", "A fictional chapter".toByteArray())
                .text(),
        )
        assertEquals(
            "Chapter",
            StudioTextDocument(
                "ref",
                "Title",
                "text/plain",
                byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
                    "Chapter".toByteArray(),
            )
                .text(),
        )
        assertThrows(IllegalArgumentException::class.java) {
            StudioTextDocument("ref", "Title", "application/pdf", byteArrayOf()).text()
        }
        assertThrows(IllegalArgumentException::class.java) {
            StudioTextDocument("ref", "Title", "text/plain", byteArrayOf(0)).text()
        }
        assertThrows(java.nio.charset.CharacterCodingException::class.java) {
            StudioTextDocument("ref", "Title", "text/plain", byteArrayOf(0xFF.toByte())).text()
        }
    }

    @Test
    fun `Telegram graph uses scoped reader and preserves the source cursor`() = runTest {
        var calls = 0
        val service =
            object : StudioServices {
                override suspend fun telegram(source: StudioSource) =
                    object : TelegramSourceReader {
                        override suspend fun history(
                            channel: String,
                            cursor: String?,
                            limit: Int,
                        ): TelegramMessagePage {
                            assertEquals("fictional_channel", channel)
                            assertEquals("previous", cursor)
                            assertEquals(50, limit)
                            calls++
                            return TelegramMessagePage(
                                listOf(TelegramSourceMessage(-1, 42, "Fiction", null)),
                                "next",
                            )
                        }

                        override suspend fun message(
                            channel: String,
                            messageId: Long,
                        ): TelegramSourceMessage = error("Unexpected")

                        override suspend fun document(reference: String): TelegramSourceDocument =
                            error("Unexpected")
                    }
            }
        val history =
            StudioNode(
                "history",
                StudioKind.TELEGRAM_HISTORY,
                parameters = mapOf("channel" to "fictional_channel", "cursor" to "{{cursor}}"),
            )
        val output = StudioNode("result", StudioKind.OUTPUT)
        val graph =
            StudioGraph(
                listOf(history, output),
                listOf(
                    StudioWire("history", "messages", "result", "value"),
                    StudioWire("history", "cursor", "result", "cursor"),
                    StudioWire("history", "hasNext", "result", "hasNext"),
                ),
            )
        val source =
            StudioSource(
                schema = 2,
                medium = StudioMedium.NOVEL,
                transport = StudioTransport.TELEGRAM,
                telegramChannels = listOf("fictional_channel"),
                flows = mapOf(StudioFlow.ENTRIES to graph),
            )
        val result =
            StudioExecutor(noHttp, noScripts, service)
                .run(
                    source,
                    StudioFlow.ENTRIES,
                    JsonObject(mapOf("cursor" to JsonPrimitive("previous"))),
                )
        assertEquals(1, calls)
        assertEquals(JsonPrimitive("next"), result.value.jsonObject["cursor"])
        assertFalse(result.traces.any { it.truncated })
    }

    @Test
    fun `document bytes never enter trace snapshots`() = runTest {
        val document =
            StudioNode(
                "doc",
                StudioKind.DOCUMENT_RESOLVE,
                parameters = mapOf("resolver" to "fixture.document"),
            )
        val input =
            StudioNode("input", StudioKind.CONSTANT, parameters = mapOf("value" to "opaque"))
        val output = StudioNode("result", StudioKind.OUTPUT)
        val graph =
            StudioGraph(
                listOf(input, document, output),
                listOf(
                    StudioWire("input", "value", "doc", "reference"),
                    StudioWire("doc", "document", "result", "value"),
                ),
            )
        val services =
            object : StudioServices {
                override suspend fun document(
                    source: StudioSource,
                    resolver: String,
                    reference: String,
                ) =
                    StudioTextDocument(
                        reference,
                        "Fiction",
                        "text/plain",
                        "A private body".toByteArray(),
                    )
            }
        val source =
            StudioSource(
                schema = 2,
                documentResolvers = setOf("fixture.document"),
                flows = mapOf(StudioFlow.CONTENT to graph),
            )
        val answer =
            StudioExecutor(noHttp, noScripts, services)
                .run(source, StudioFlow.CONTENT, JsonObject(emptyMap()))
        assertTrue(answer.traces.all { it.outputs.values.all { value -> value.document == null } })
        assertFalse(answer.toString().contains("A private body"))
    }

    @Test
    fun `plain chapter text preserves angle brackets and does not parse HTML`() = runTest {
        val body = "A < B\n\nA fictional chapter."
        val node = StudioNode(kind = StudioKind.NOVEL_BODY, parameters = mapOf("format" to "TEXT"))
        val output = StudioCapabilities.execute(
            StudioSource(schema = 2),
            node,
            mapOf("document" to StudioValue(StudioType.TEXT, JsonPrimitive(body))),
            StudioServices.Unavailable,
            {
                it
            },
            "",
        )
            .getValue("value").value.jsonObject
        assertEquals(body, output.getValue("body").studioText())
        assertEquals("TEXT", output.getValue("format").studioText())
        assertEquals("[]", output.getValue("resources").toString())
        assertEquals(StudioType.TEXT, StudioPorts.inputs(node).single().type)
    }

    @Test
    fun `web novel body keeps declared illustration references and actual chapter text`() =
        runTest {
            val graph = StudioBlueprints.novelBodyGraph(selector = "article")
            val source =
                StudioSource(
                    schema = 2,
                    medium = StudioMedium.NOVEL,
                    baseUrl = "https://fixture.invalid",
                    flows = mapOf(StudioFlow.CONTENT to graph),
                )
            val http = StudioHttp { _, _, url, _ ->
                StudioHttpResponse(
                    "<article><h1>A fictional title</h1><p>Actual text.</p><img src='/art.png'></article>",
                    url,
                    200,
                )
            }
            val answer =
                StudioExecutor(http, noScripts)
                    .run(
                        source,
                        StudioFlow.CONTENT,
                        JsonObject(
                            mapOf("url" to JsonPrimitive("https://fixture.invalid/chapter")),
                        ),
                    )
                    .value
                    .jsonObject
            assertTrue(answer.getValue("body").toString().contains("Actual text."))
            assertTrue(
                answer.getValue("resources").toString().contains("https://fixture.invalid/art.png"),
            )
        }
}
