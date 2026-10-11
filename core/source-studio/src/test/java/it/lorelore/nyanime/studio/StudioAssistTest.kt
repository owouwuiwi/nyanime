package it.lorelore.nyanime.studio

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StudioAssistTest {
    private val root = "https://example.test"
    private val fields = mapOf("title" to StudioAssist.Field("a"), "url" to StudioAssist.Field("a", "href"))
    private val source = StudioSource(name = "Synthetic", baseUrl = root)
    private fun engine(html: String, requested: (String) -> Unit = {}) = StudioExecutor(
        StudioHttp { _, _, url, _ ->
            requested(url)
            StudioHttpResponse(html, url, 200)
        },
        StudioScript { _, _ -> error("Assistance must not generate scripts") },
    )

    @Test fun `search uses observed parameter and preserves punctuation through runtime encoding`() {
        val template = StudioAssist.searchTemplate("$root/find?term=Example+%26+Book&lang=en", "Example & Book")
        assertEquals("$root/find?lang=en&term={{query|url}}", template)
        assertEquals(
            "$root/find?lang=en&term=A%2BB+%26+C",
            StudioTemplates.expand(template, mapOf("query" to JsonPrimitive("A+B & C"))),
        )
    }

    @Test fun `ambiguous or hidden searches are not guessed`() {
        listOf("$root/find?q=X&s=X", "$root/find/X", "$root/find?q=X&q=X", "$root/find?q=X&token=secret").forEach {
            assertThrows(IllegalArgumentException::class.java) { StudioAssist.searchTemplate(it, "X") }
        }
        assertThrows(IllegalArgumentException::class.java) { StudioAssist.address("https://user:pass@example.test") }
    }

    @Test fun `proposed HTML graph reproduces observed title and relative addresses`() = runTest {
        val graph = StudioAssist.graph(
            StudioFlow.SEARCH,
            StudioMedium.MANGA,
            "$root/find?q={{query|url}}",
            "article",
            fields,
        )
        assertTrue(StudioValidation.graph(graph).isEmpty())
        val result = engine("<article><a href='/book'>Synthetic Book</a></article>").run(
            source,
            StudioFlow.SEARCH,
            buildJsonObject {
                put("query", "Synthetic")
            },
            overrideGraph = graph,
        )
        StudioAssist.verify(result.value, StudioFlow.SEARCH, "Synthetic Book")
        assertEquals("$root/book", result.value.jsonArray.single().jsonObject.getValue("url").studioText())
        assertFalse(graph.nodes.any { it.kind == StudioKind.SCRIPT })
    }

    @Test fun `details retain canonical URL and require a single real title`() = runTest {
        val graph = StudioAssist.graph(
            StudioFlow.DETAILS,
            StudioMedium.VIDEO,
            "{{url}}",
            "body",
            mapOf("title" to StudioAssist.Field("h1")),
        )
        val result = engine("<h1>Synthetic Title</h1>").run(
            source,
            StudioFlow.DETAILS,
            buildJsonObject {
                put("url", "$root/title")
            },
            overrideGraph = graph,
        )
        StudioAssist.verify(result.value, StudioFlow.DETAILS, "Synthetic Title")
        assertEquals("$root/title", result.value.jsonArray.single().jsonObject.getValue("url").studioText())
    }

    @Test fun `missing rendered-only or mismatched examples cannot be approved`() {
        assertThrows(IllegalArgumentException::class.java) {
            StudioAssist.verify(JsonArray(emptyList()), StudioFlow.SEARCH)
        }
        val item = buildJsonObject {
            put("title", "Other")
            put("url", "$root/other")
        }
        assertThrows(IllegalArgumentException::class.java) {
            StudioAssist.verify(JsonArray(listOf(item)), StudioFlow.SEARCH, "Expected")
        }
        assertThrows(IllegalArgumentException::class.java) {
            StudioAssist.verify(JsonArray(listOf(item, item)), StudioFlow.DETAILS)
        }
        val unsafe = buildJsonObject { put("url", "javascript:alert(1)") }
        assertThrows(IllegalArgumentException::class.java) {
            StudioAssist.verify(JsonArray(listOf(unsafe)), StudioFlow.CONTENT)
        }
    }

    @Test fun `pagination is inferred only from a second page of the same query`() = runTest {
        val template = StudioAssist.searchTemplate("$root/find?q=X", "X")
        val paged = StudioAssist.pagination(template, "$root/find?q=X", "$root/find?q=X&offset=2")
        assertEquals("$root/find?q={{query|url}}&offset={{page}}", paged)
        val graph = StudioAssist.graph(StudioFlow.SEARCH, StudioMedium.VIDEO, paged, "article", fields, "a.next")
        val html = "<article><a href='/book'>Example</a></article>"
        val result = engine(html).run(
            source,
            StudioFlow.SEARCH,
            buildJsonObject {
                put("query", "X")
                put("page", 1)
            },
            overrideGraph = graph,
        )
        assertEquals("false", result.value.jsonObject.getValue("hasNext").studioText())
        listOf(
            "$root/find?q=Y&offset=2",
            "$root/other?q=X&offset=2",
            "$root/find?q=X&offset=20",
            "https://other.test/find?q=X&offset=2",
        ).forEach {
            assertThrows(IllegalArgumentException::class.java) {
                StudioAssist.pagination(template, "$root/find?q=X", it)
            }
        }
    }

    @Test fun `filter labels map to values while home routes and source identity stay separate`() {
        val graph = StudioAssist.graph(
            StudioFlow.SEARCH,
            StudioMedium.VIDEO,
            "$root/find?q={{query|url}}",
            "article",
            fields,
        )
        val original = source.copy(flows = mapOf(StudioFlow.SEARCH to graph))
        val one = StudioAssist.filter(
            original,
            "Category",
            "Mystery",
            "$root/find?q=X&genre=all",
            "$root/find?q=X&genre=7",
            "Any",
        )
        val two = StudioAssist.filter(
            one,
            "Category",
            "Adventure",
            "$root/find?q=X&genre=all",
            "$root/find?q=X&genre=9",
            "Any",
        )
        assertEquals(original.sourceId, two.sourceId)
        assertEquals(null, two.home)
        assertEquals(listOf("Any", "Mystery", "Adventure"), two.filters.single().options)
        assertEquals("7", StudioAssist.filterValue(two.filters.single(), "Mystery"))
        assertEquals("all", StudioAssist.filterValue(two.filters.single(), "Any"))
        assertEquals(two, StudioDocuments.decode(StudioDocuments.encode(two)))
        assertThrows(IllegalArgumentException::class.java) {
            StudioAssist.filter(two, "Category", "Other", "$root/find?q=X", "$root/find?q=X&language=it", "Any")
        }
    }

    @Test fun `filter cannot replace more than one observed request parameter`() {
        assertThrows(IllegalArgumentException::class.java) {
            StudioAssist.filterRoute("$root/find?q=X", "$root/find?q=Y&genre=7")
        }
    }

    @Test fun `recreating search retains observed filter bindings or refuses unsupported rewrites`() {
        val graph = StudioAssist.graph(
            StudioFlow.SEARCH,
            StudioMedium.VIDEO,
            "$root/find?q={{query|url}}",
            "article",
            fields,
        )
        val configured = StudioAssist.filter(
            source.copy(flows = mapOf(StudioFlow.SEARCH to graph)),
            "Category",
            "Mystery",
            "$root/find?q=X",
            "$root/find?q=X&genre=7",
            "Any",
        )
        val next = StudioAssist.preserveFilters("$root/new?title={{query|url}}", configured)
        assertEquals("$root/new?title={{query|url}}&genre={{filter.filter1|url}}", next)
        assertThrows(IllegalArgumentException::class.java) {
            StudioAssist.preserveFilters("$root/new?genre={{query|url}}", configured)
        }
        val complex = configured.copy(flows = mapOf(StudioFlow.SEARCH to graph))
        assertThrows(IllegalStateException::class.java) {
            StudioAssist.preserveFilters("$root/new?q={{query|url}}", complex)
        }
    }
}
