package it.lorelore.nyanime.studio

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.io.File

/** Opt-in manual authoring harness. No live site, address or fixture ships in the repository. */
@EnabledIfSystemProperty(named = "studio.fixture", matches = ".+")
class StudioExternalFixtureTest {
    @Test fun `user supplied source runs through the same bounded executor`() = runBlocking {
        val fixture = File(System.getProperty("studio.fixture"))
        val request = StudioDocuments.json.parseToJsonElement(fixture.readText()).jsonObject
        val source = StudioDocuments.decode(request.getValue("source").toString())
        val results = mutableListOf<JsonElement>()
        val client = OkHttpClient()
        for (case in request.getValue("cases").jsonArray) {
            val item = case.jsonObject
            val flow = StudioFlow.valueOf(item.getValue("flow").jsonPrimitive.content)
            val graph = item["home"]?.jsonPrimitive?.content?.let { source.homeFlows.getValue(it) }
            val started = System.currentTimeMillis()
            try {
                val engine =
                    StudioExecutor(
                        StudioNetwork(client),
                        StudioScript { _, _ ->
                            error("This manual harness requires a script-free source")
                        },
                    )
                val result = engine.run(source, flow, item.getValue("variables").jsonObject, overrideGraph = graph)
                val rows = (result.value as? JsonArray) ?: ((result.value as? JsonObject)?.get("items") as? JsonArray)
                results +=
                    buildJsonObject {
                        put("case", item["name"] ?: JsonPrimitive(flow.name))
                        put(
                            "millis",
                            System.currentTimeMillis() - started,
                        )
                        put("result", result.value)
                    }
                File(fixture.parentFile, "results.json").writeText(JsonArray(results).toString())
                assertTrue(
                    (rows?.size ?: 1) >= (item["minimum"]?.jsonPrimitive?.intOrNull ?: 0),
                    "The flow returned fewer items than declared",
                )
                if (flow in
                    setOf(
                        StudioFlow.SEARCH,
                        StudioFlow.POPULAR,
                        StudioFlow.LATEST,
                        StudioFlow.DETAILS,
                        StudioFlow.ENTRIES,
                    )
                ) {
                    rows?.forEach { row ->
                        assertTrue(!row.jsonObject["title"]?.studioText().isNullOrBlank(), "Missing title")
                        assertTrue(!row.jsonObject["url"]?.studioText().isNullOrBlank(), "Missing address")
                    }
                }
            } catch (failure: Exception) {
                results +=
                    buildJsonObject {
                        put("case", item["name"] ?: JsonPrimitive(flow.name))
                        put("error", failure.message)
                    }
                File(fixture.parentFile, "results.json").writeText(JsonArray(results).toString())
                throw failure
            }
        }
    }
}
