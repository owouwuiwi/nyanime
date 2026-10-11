package it.lorelore.nyanime.studio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

class StudioRuntimeTest {
    private val neverScript = StudioScript { _, _ -> error("Script was not authorized") }
    private fun executor(
        body: String,
    ) = StudioExecutor(StudioHttp { _, _, url, _ -> StudioHttpResponse(body, url, 200) }, neverScript)
    private fun source(
        graph: StudioGraph,
    ) = StudioSource(
        name = "Synthetic source",
        baseUrl = "https://catalog.test",
        flows = mapOf(StudioFlow.SEARCH to graph),
    )

    @Test fun `declarative roundtrip retains identity home and draft graph`() {
        val source = StudioBlueprints.source().copy(
            name = "Synthetic source",
            baseUrl = "https://catalog.test",
            home = StudioHome("Synthetic Home", listOf(StudioHomeSection("new", "New", "featured"))),
            homeFlows = mapOf("new" to StudioBlueprints.graph(StudioFlow.SEARCH, StudioMedium.VIDEO)),
        )
        val decoded = StudioDocuments.decode(StudioDocuments.encode(source))
        assertEquals(source, decoded)
        assertEquals(source.sourceId, decoded.copy(baseUrl = "https://moved.test", revision = 2).sourceId)
        assertNotEquals(source.sourceId, source.duplicate().sourceId)
        assertTrue(StudioValidation.source(source).any { it.field.endsWith(".title") })
    }

    @Test fun `HTML source resolves relative addresses without provider knowledge`() = runTest {
        var graph = StudioBlueprints.graph(StudioFlow.SEARCH, StudioMedium.MANGA)
        graph = graph.copy(
            nodes = graph.nodes.map { node ->
                when (node.id) {
                    "select" -> node.copy(parameters = mapOf("selector" to "article"))
                    "map" -> node.copy(
                        parameters = mapOf(
                            "fields" to
                                """{"title":{"selector":"a"},"url":{"selector":"a","attribute":"href","url":true}}""",
                        ),
                    )
                    else -> node
                }
            },
        )
        val result = executor("<article><a href='/entry/7'>Synthetic Volume 7</a></article>").run(
            source(graph),
            StudioFlow.SEARCH,
            buildJsonObject {
                put("url", "https://catalog.test/list")
            },
        )
        assertEquals(
            "https://catalog.test/entry/7",
            result.value.jsonArray.single().jsonObject["url"]!!.jsonPrimitive.content,
        )
        assertEquals(5, result.traces.size)
    }

    @Test fun `JSON path handles nested lists and missing optional fields`() = runTest {
        var graph = StudioBlueprints.graph(StudioFlow.SEARCH, StudioMedium.VIDEO, json = true)
        graph = graph.copy(
            nodes = graph.nodes.map { node ->
                when (node.id) {
                    "select" -> node.copy(parameters = mapOf("mode" to "json", "selector" to "$.data[*]"))
                    "map" -> node.copy(
                        parameters = mapOf(
                            "fields" to
                                """{
                                  "title":{"mode":"json","selector":"$.label"},
                                  "url":{"mode":"json","selector":"$.href","url":true},
                                  "cover":{"mode":"json","selector":"$.cover"}
                                }""",
                        ),
                    )
                    else -> node
                }
            },
        )
        val result = executor("""{"data":[{"label":"Synthetic Series","href":"/series"}]}""").run(
            source(graph),
            StudioFlow.SEARCH,
            buildJsonObject {
                put("url", "https://catalog.test")
            },
        )
        assertEquals("Synthetic Series", result.value.jsonArray.single().jsonObject["title"]!!.jsonPrimitive.content)
        assertEquals("", result.value.jsonArray.single().jsonObject["cover"]!!.jsonPrimitive.content)
    }

    @Test fun `validation rejects cycles incompatible ports and duplicate wires`() {
        val graph = StudioBlueprints.graph(StudioFlow.SEARCH, StudioMedium.VIDEO)
        assertTrue(
            StudioValidation.graph(
                graph.copy(
                    wires =
                    graph.wires + StudioWire("select", "value", "parse", "text"),
                ),
            ).isNotEmpty(),
        )
        assertTrue(StudioValidation.graph(graph.copy(wires = graph.wires + graph.wires.first())).isNotEmpty())
        assertTrue(
            StudioValidation.graph(
                graph.copy(
                    wires =
                    graph.wires + StudioWire("result", "value", "request", "url"),
                ),
            ).isNotEmpty(),
        )
        val rawHtml = graph.copy(
            wires =
            graph.wires.filterNot { it.to == "result" } + StudioWire("parse", "value", "result", "value"),
        )
        assertTrue(StudioValidation.graph(rawHtml).any { it.field == "value" && it.message.contains("Incompatible") })
    }

    @Test fun `JSON picker paths preserve punctuation escapes and literal stars`() {
        val key = "a]b.'quoted\"\\field"
        val document = buildJsonObject {
            put(
                key,
                buildJsonObject {
                    put("*", "chosen")
                    put("other", "unused")
                },
            )
        }
        assertEquals(
            listOf(JsonPrimitive("chosen")),
            StudioJsonPath.select(document, "$[${JsonPrimitive(key)}][\"*\"]"),
        )
        assertEquals(
            listOf(JsonPrimitive("chosen"), JsonPrimitive("unused")),
            StudioJsonPath.select(document, "$[${JsonPrimitive(key)}][*]"),
        )
        assertThrows(IllegalArgumentException::class.java) { StudioJsonPath.select(document, "$['unterminated]") }
    }

    @Test fun `unreachable scripts never execute and selected block test prunes later requests`() = runTest {
        val graph =
            StudioGraph(
                listOf(
                    StudioNode("text", StudioKind.CONSTANT, parameters = mapOf("value" to "ready")),
                    StudioNode("script", StudioKind.SCRIPT, parameters = mapOf("code" to "while(true){}")),
                    StudioNode("out", StudioKind.OUTPUT),
                ),
                listOf(StudioWire("text", "value", "out", "value")),
            )
        val result = executor("").run(source(graph), StudioFlow.SEARCH, JsonObject(emptyMap()))
        assertEquals(JsonPrimitive("ready"), result.value)
        assertEquals(2, result.traces.size)
        assertEquals(1, executor("").run(source(graph), StudioFlow.SEARCH, JsonObject(emptyMap()), "text").traces.size)
    }

    @Test fun `cancellation is not converted into a source error`() = runTest {
        val engine = StudioExecutor(StudioHttp { _, _, _, _ -> throw CancellationException("stop") }, neverScript)
        try {
            engine.run(
                source(StudioBlueprints.graph(StudioFlow.SEARCH, StudioMedium.VIDEO)),
                StudioFlow.SEARCH,
                buildJsonObject {
                    put("url", "https://catalog.test")
                },
            )
            fail("Cancellation was swallowed")
        } catch (_: CancellationException) { }
    }

    @Test fun `strict import and secret isolation reject unsupported behavior`() {
        assertThrows(Exception::class.java) { StudioDocuments.decode("""{"schema":9}""") }
        assertThrows(Exception::class.java) { StudioTemplates.expand("{{secret.token}}", emptyMap()) }
        assertThrows(Exception::class.java) {
            StudioDocuments.decode("x".repeat(StudioDocuments.MAX_DOCUMENT_BYTES + 1))
        }
        assertEquals("blue+moon", StudioTemplates.expand("{{query|url}}", mapOf("query" to JsonPrimitive("blue moon"))))
    }

    @Test fun `undo redo is bounded and does not mutate the original snapshot`() {
        val source = StudioBlueprints.source()
        val history = StudioHistory(source)
        history.change(source.copy(name = "Draft"))
        assertEquals(source, history.undo())
        assertEquals("Draft", history.redo().name)
        history.undo()
        history.change(source.copy(name = "Other"))
        assertFalse(history.canRedo)
    }

    @Test fun `publication revisions stay independent of editing undo and redo`() {
        val original = StudioBlueprints.source()
        val history = StudioHistory(original)
        history.change(original.copy(name = "Edited draft"))
        history.alignRevision(4)
        assertEquals(original.name, history.undo().name)
        assertEquals(4L, history.current.revision)
        assertEquals("Edited draft", history.redo().name)
        assertEquals(4L, history.current.revision)
    }
}
