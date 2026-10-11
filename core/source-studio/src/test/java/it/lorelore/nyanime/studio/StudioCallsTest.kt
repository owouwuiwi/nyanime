package it.lorelore.nyanime.studio

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StudioCallsTest {
    private val noScripts = StudioScript { _, _ -> error("No script needed") }

    private fun networkFunction(id: String): StudioFunction {
        val request = StudioNode(
            id = "$id-request",
            kind = StudioKind.HTTP,
            parameters = mapOf("url" to "https://source.invalid/$id"),
        )
        val output = StudioNode(id = "$id-output", kind = StudioKind.OUTPUT)
        return StudioFunction(
            id,
            id,
            StudioGraph(listOf(request, output), listOf(StudioWire(request.id, "body", output.id, "value"))),
        )
    }

    private fun graph(call: StudioNode): StudioGraph {
        val input = StudioNode(
            id = "input",
            kind = StudioKind.INPUT,
            parameters = mapOf(
                "key" to "value",
                "type" to "LIST",
            ),
        )
        val output = StudioNode(id = "output", kind = StudioKind.OUTPUT)
        return StudioGraph(
            listOf(input, call, output),
            listOf(
                StudioWire(input.id, "value", call.id, "value"),
                StudioWire(call.id, "value", output.id, "value"),
            ),
        )
    }

    private fun source(call: StudioNode) = StudioSource(
        schema = 2,
        name = "Fictional",
        baseUrl = "https://source.invalid",
        flows = StudioFlow.entries.associateWith {
            graph(call)
        },
        functions = listOf(networkFunction("direct"), networkFunction("archive")),
    )

    @Test
    fun `only the selected reusable branch performs network requests`() = runTest {
        val call = StudioNode(
            kind = StudioKind.SUBGRAPH,
            parameters = mapOf(
                "each" to "true",
                "branchField" to "kind",
                "cases" to "{\"direct\":\"direct\",\"archive\":\"archive\"}",
            ),
        )
        val requested = mutableListOf<String>()
        val result = StudioExecutor(
            StudioHttp { _, _, url, _ ->
                requested += url
                StudioHttpResponse("selected", url, 200)
            },
            noScripts,
        ).run(
            source(call),
            StudioFlow.SEARCH,
            JsonObject(
                mapOf(
                    "value" to JsonArray(
                        listOf(
                            JsonObject(mapOf("kind" to JsonPrimitive("direct"))),
                            JsonObject(mapOf("kind" to JsonPrimitive("direct"))),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(listOf("https://source.invalid/direct", "https://source.invalid/direct"), requested)
        assertEquals(JsonArray(listOf(JsonPrimitive("selected"), JsonPrimitive("selected"))), result.value)
        assertTrue(StudioValidation.source(source(call)).isEmpty())
    }

    @Test
    fun `iteration shares the parent request budget`() = runTest {
        val call = StudioNode(kind = StudioKind.SUBGRAPH, parameters = mapOf("function" to "direct", "each" to "true"))
        var requested = 0
        val failure = runCatching {
            StudioExecutor(
                StudioHttp { _, _, url, _ ->
                    requested++
                    StudioHttpResponse("item", url, 200)
                },
                noScripts,
            )
                .run(
                    source(call),
                    StudioFlow.SEARCH,
                    JsonObject(
                        mapOf(
                            "value" to JsonArray(
                                List(17) {
                                    JsonPrimitive(it)
                                },
                            ),
                        ),
                    ),
                )
        }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("budget"))
        assertEquals(16, requested)
    }

    @Test
    fun `unknown branch and oversized iteration fail before network`() = runTest {
        val call = StudioNode(
            kind = StudioKind.SUBGRAPH,
            parameters = mapOf(
                "each" to "true",
                "branchField" to "kind",
                "cases" to "{\"direct\":\"direct\"}",
            ),
        )
        val executor = StudioExecutor(StudioHttp { _, _, _, _ -> error("Must not request") }, noScripts)
        val unknown = runCatching {
            executor.run(
                source(call),
                StudioFlow.SEARCH,
                JsonObject(
                    mapOf(
                        "value" to JsonArray(
                            listOf(
                                JsonObject(mapOf("kind" to JsonPrimitive("unknown"))),
                            ),
                        ),
                    ),
                ),
            )
        }.exceptionOrNull()
        assertTrue(unknown?.message.orEmpty().contains("branch"))
        val oversized = runCatching {
            executor.run(
                source(call),
                StudioFlow.SEARCH,
                JsonObject(mapOf("value" to JsonArray(List(101) { JsonPrimitive(it) }))),
            )
        }.exceptionOrNull()
        assertTrue(oversized?.message.orEmpty().contains("100"))
    }

    @Test
    fun `cancellation stops a pending reusable request and later items`() = runTest {
        val call = StudioNode(kind = StudioKind.SUBGRAPH, parameters = mapOf("function" to "direct", "each" to "true"))
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val requested = java.util.concurrent.atomic.AtomicInteger()
        val job = async {
            StudioExecutor(
                StudioHttp { _, _, _, _ ->
                    requested.incrementAndGet()
                    started.complete(Unit)
                    awaitCancellation()
                },
                noScripts,
            ).run(
                source(call),
                StudioFlow.SEARCH,
                JsonObject(mapOf("value" to JsonArray(List(3) { JsonPrimitive(it) }))),
            )
        }
        started.await()
        job.cancel()
        job.join()
        assertEquals(1, requested.get())
    }

    @Test
    fun `every branch participates in recursive graph validation`() {
        val call = StudioNode(
            kind = StudioKind.SUBGRAPH,
            parameters = mapOf(
                "function" to "direct",
                "branchField" to "kind",
                "cases" to "{\"hidden\":\"archive\"}",
            ),
        )
        val recursive = StudioFunction("archive", "Recursive", graph(call))
        val definition = source(call).copy(functions = listOf(networkFunction("direct"), recursive))
        assertTrue(StudioValidation.source(definition).any { it.message.contains("Recursive") })
        assertTrue(StudioValidation.source(source(call).copy(schema = 1)).any { it.field == "schema" })
    }

    @Test
    fun `malformed branch declarations fail validation`() {
        for (raw in listOf("[]", "{}", "{\"x\":\"\"}")) {
            assertThrows(Exception::class.java) {
                StudioCalls.targets(
                    StudioNode(
                        kind = StudioKind.SUBGRAPH,
                        parameters = mapOf("cases" to raw, "branchField" to "kind"),
                    ),
                )
            }
        }
        val node = StudioNode(kind = StudioKind.SUBGRAPH, parameters = mapOf("function" to "direct"))
        assertEquals("direct", StudioCalls.select(node, JsonPrimitive("anything")))
    }
}
