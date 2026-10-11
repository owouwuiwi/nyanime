package it.lorelore.nyanime.studio

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** Proposals are ordinary graphs. No domain, selector, query parameter or title is supplied here. */
object StudioAssist {
    data class Field(val selector: String, val attribute: String = "text")
    data class FilterRoute(val parameter: String, val value: String, val initial: String)

    fun address(raw: String): URI = URI(raw).also {
        require(raw.length <= 4096 && it.scheme in setOf("http", "https") && !it.host.isNullOrBlank())
        require(it.userInfo == null && it.fragment == null)
        require(
            parameters(it).keys.none { key ->
                key.lowercase() in
                    setOf(
                        "token",
                        "access_token",
                        "auth",
                        "authorization",
                        "password",
                        "session",
                        "sid",
                        "key",
                        "api_key",
                    )
            },
        )
    }

    private fun parameters(uri: URI): Map<String, String> {
        val entries = uri.rawQuery.orEmpty().split('&').filter(String::isNotBlank).map {
            val pair = it.split('=', limit = 2)
            URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
        }
        require(
            entries.map {
                it.first
            }.distinct().size == entries.size,
        ) { "Repeated query parameters require the editor" }
        return entries.toMap()
    }

    /** Only an observed GET parameter whose value equals the user's example can become a search. */
    fun searchTemplate(raw: String, example: String): String {
        val uri = address(raw)
        require(example.isNotBlank())
        val names = parameters(uri).filterValues { it == example }.keys
        require(names.size == 1) { "Choose a GET search with one matching query parameter" }
        return parameter(raw, names.single(), "{{query|url}}")
    }

    fun filterRoute(example: String, option: String): FilterRoute {
        val first = address(example)
        val second = address(option)
        require(
            first.scheme == second.scheme &&
                first.rawAuthority == second.rawAuthority &&
                first.rawPath == second.rawPath,
        )
        val before = parameters(first)
        val after = parameters(second)
        require(before.keys.all { it in after })
        val changed = after.filter { (name, value) -> before[name] != value }
        require(changed.size == 1) { "Choose one filter at a time in the same search" }
        return changed.entries.single().let { FilterRoute(it.key, it.value, before[it.key].orEmpty()) }
    }

    fun parameter(template: String, name: String, value: String): String {
        require(name.isNotBlank() && name.length <= 100)
        val base = template.substringBefore('?')
        val pairs = template.substringAfter('?', "").split('&').filter(String::isNotBlank)
        val kept = pairs.filterNot { URLDecoder.decode(it.substringBefore('='), "UTF-8") == name }
        return base + "?" + (kept + "${URLEncoder.encode(name, "UTF-8")}=$value").joinToString("&")
    }

    fun preserveFilters(template: String, source: StudioSource): String {
        if (source.filters.isEmpty()) return template
        val previous = source.flows[StudioFlow.SEARCH]?.nodes?.singleOrNull { it.kind == StudioKind.HTTP }
            ?.parameters?.get("url") ?: error("Complex search filters require the editor")
        val pairs = previous.substringAfter('?', "").split('&')
        var result = template
        source.filters.forEach { field ->
            val marker = "{{filter.${field.id}|url}}"
            val binding = pairs.singleOrNull { it.substringAfter('=', "") == marker }
                ?: error("Complex search filters require the editor")
            val key = URLDecoder.decode(binding.substringBefore('='), "UTF-8")
            val existing = result.substringAfter('?', "").split('&').firstOrNull {
                URLDecoder.decode(it.substringBefore('='), "UTF-8") == key
            }?.substringAfter('=')
            require(existing == null || (!existing.contains("{{query") && !existing.contains("{{page")))
            result = parameter(result, key, marker)
        }
        return result
    }

    fun graph(
        flow: StudioFlow,
        medium: StudioMedium,
        request: String,
        selector: String,
        fields: Map<String, Field>,
        nextSelector: String = "",
    ): StudioGraph {
        require(selector.isNotBlank() && selector.length <= 4096)
        val required = when (flow) {
            StudioFlow.CONTENT -> setOf("url")
            StudioFlow.DETAILS -> setOf("title")
            else -> setOf("title", "url")
        }
        require(fields.keys.containsAll(required))
        val rules = buildJsonObject {
            fields.forEach { (key, field) ->
                require(key in StudioBlueprints.fields(flow, medium))
                require(
                    field.selector.length <= 4096 &&
                        field.attribute in setOf("text", "href", "src", "data-src", "alt", "title", "content"),
                )
                put(
                    key,
                    buildJsonObject {
                        put("selector", field.selector)
                        if (field.attribute != "text") put("attribute", field.attribute)
                        if (key in setOf("url", "cover", "background")) put("url", true)
                        put("required", key in required)
                    },
                )
            }
            if (flow ==
                StudioFlow.DETAILS
            ) {
                put(
                    "url",
                    buildJsonObject {
                        put("mode", "constant")
                        put("value", "{{url}}")
                    },
                )
            }
        }
        val blueprint = StudioBlueprints.graph(flow, medium)
        val nodes = blueprint.nodes.map {
            when (it.id) {
                "request" -> it.copy(parameters = it.parameters + ("url" to request))
                "select" -> it.copy(parameters = mapOf("selector" to selector, "mode" to "css"))
                "map" -> it.copy(parameters = mapOf("fields" to rules.toString()))
                else -> it
            }
        }
        if (nextSelector.isBlank()) return blueprint.copy(nodes = nodes)
        // A next-page control is evidence of more content, never an assumption based on item count.
        return blueprint.copy(
            nodes = nodes +
                listOf(
                    StudioNode(
                        "next",
                        StudioKind.SELECT,
                        x = 568f,
                        y = 300f,
                        parameters = mapOf("selector" to nextSelector),
                    ),
                    StudioNode(
                        "hasNext",
                        StudioKind.CONDITION,
                        x = 836f,
                        y = 300f,
                        parameters = mapOf("operation" to "exists"),
                    ),
                ),
            wires = blueprint.wires +
                listOf(
                    StudioWire("parse", "value", "next", "document"),
                    StudioWire("next", "value", "hasNext", "value"),
                    StudioWire("hasNext", "value", "result", "hasNext"),
                ),
        )
    }

    /** Adding a filter keeps the existing graph and maps human labels to observed request values. */
    fun filter(
        source: StudioSource,
        title: String,
        label: String,
        example: String,
        option: String,
        allLabel: String,
    ): StudioSource {
        val route = filterRoute(example, option)
        require(title.isNotBlank() && label.isNotBlank() && label != allLabel)
        val old = source.filters.find { it.title == title }
        require(old == null || old.kind == "single")
        val anyLabel = old?.initial ?: allLabel
        require(label != anyLabel)
        val id = old?.id ?: "filter${source.filters.size + 1}"
        val current = old ?: StudioFilter(id, title, options = listOf(allLabel), initial = allLabel)
        require(old == null || current.values[anyLabel] == route.initial)
        val field = current.copy(
            options = (current.options + label).distinct(),
            values =
            current.values + (label to route.value) + (anyLabel to route.initial),
        )
        val graph = requireNotNull(source.flows[StudioFlow.SEARCH])
        val request = graph.nodes.single { it.kind == StudioKind.HTTP }
        val marker = "{{filter.$id|url}}"
        // Never overwrite an unrelated route when the user reuses a group name.
        val template = request.parameters.getValue("url")
        require(old == null || template.contains("${URLEncoder.encode(route.parameter, "UTF-8")}=$marker"))
        return source.copy(
            filters = source.filters.filterNot { it.id == id } + field,
            flows = source.flows +
                (
                    StudioFlow.SEARCH to graph.copy(
                        nodes = graph.nodes.map {
                            if (it.id ==
                                request.id
                            ) {
                                it.copy(
                                    parameters =
                                    it.parameters + ("url" to parameter(template, route.parameter, marker)),
                                )
                            } else {
                                it
                            }
                        },
                    )
                    ),
        )
    }

    /** The selected next link must be the second page of the same query, with a single numeric parameter. */
    fun pagination(template: String, example: String, next: String): String {
        val route = filterRoute(example, next)
        require(route.value == "2")
        val previous = parameters(address(example))[route.parameter]
        require(previous == null || previous == "1")
        return parameter(template, route.parameter, "{{page}}")
    }

    fun filterValue(field: StudioFilter, label: String): String = if (field.kind == "multiple") {
        label.split(',').joinToString(",") { field.values[it] ?: it }
    } else {
        field.values[label] ?: label
    }

    /** Rendered-DOM evidence alone never proves that HTTP can reproduce the selected item. */
    fun verify(value: JsonElement, flow: StudioFlow, exampleTitle: String? = null) {
        val items = (value as? JsonArray) ?: ((value as? JsonObject)?.get("items") as? JsonArray)
        require(items != null && items.size in 1..2000)
        if (flow == StudioFlow.DETAILS) require(items.size == 1)
        val rows = items.map { it as? JsonObject ?: error("Expected structured items") }
        rows.forEach { row ->
            require(row["url"]?.studioText()?.isNotBlank() == true)
            val url = URI(row.getValue("url").studioText())
            require(url.scheme in setOf("http", "https") && url.userInfo == null && !url.host.isNullOrBlank())
            if (flow != StudioFlow.CONTENT) require(!row["title"]?.studioText().isNullOrBlank())
        }
        if (!exampleTitle.isNullOrBlank()) {
            fun normalize(text: String) = text.trim().replace(Regex("\\s+"), " ")
            require(rows.any { normalize(it["title"]?.studioText().orEmpty()) == normalize(exampleTitle) })
        }
    }
}
