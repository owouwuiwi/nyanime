package it.lorelore.nyanime.studio

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Structural starting points only. Addresses, selectors and data always come from the user. */
object StudioBlueprints {
    const val HOME_FILTER = "Studio flow"
    fun fields(flow: StudioFlow, medium: StudioMedium): List<String> = when (flow) {
        StudioFlow.SEARCH, StudioFlow.POPULAR, StudioFlow.LATEST -> listOf("title", "url", "cover", "aliases")
        StudioFlow.DETAILS -> listOf(
            "title", "url", "cover", "background", "description", "genres", "author", "artist",
            "status", "aliases", "anilistId", "malId",
        )
        StudioFlow.ENTRIES -> listOf("title", "url", "number", "uploaded", "group")
        StudioFlow.CONTENT -> if (medium ==
            StudioMedium.VIDEO
        ) {
            listOf("url", "title", "resolution", "headers", "subtitles", "audio")
        } else {
            listOf("url")
        }
    }

    fun graph(flow: StudioFlow, medium: StudioMedium, json: Boolean = false): StudioGraph {
        val request =
            StudioNode(
                id = "request",
                kind = StudioKind.HTTP,
                x = 32f,
                y = 80f,
                parameters = mapOf("url" to "{{url}}", "method" to "GET", "headers" to "{}"),
            )
        val parse = StudioNode(id = "parse", kind = if (json) StudioKind.JSON else StudioKind.HTML, x = 300f, y = 80f)
        val select =
            StudioNode(
                id = "select",
                kind = StudioKind.SELECT,
                x = 568f,
                y = 80f,
                parameters = mapOf("selector" to if (json) "$" else "body", "mode" to if (json) "json" else "css"),
            )
        val map =
            StudioNode(
                id = "map",
                kind = StudioKind.MAP,
                x = 836f,
                y = 80f,
                parameters = mapOf(
                    "fields" to buildJsonObject {
                        fields(flow, medium).forEach { field ->
                            put(
                                field,
                                buildJsonObject {
                                    put("enabled", false)
                                    put("selector", "")
                                    if (json) put("mode", "json")
                                    if (field in setOf("url", "cover", "background")) {
                                        put("attribute", "href")
                                        put("url", true)
                                    }
                                    if (field in
                                        setOf("number", "uploaded", "status", "resolution", "anilistId", "malId")
                                    ) {
                                        put("type", "number")
                                    }
                                },
                            )
                        }
                    }.toString(),
                ),
            )
        val output =
            StudioNode(
                id = "result",
                kind = StudioKind.OUTPUT,
                x = 1104f,
                y = 80f,
                parameters = mapOf("structured" to "true"),
            )
        return StudioGraph(
            listOf(request, parse, select, map, output),
            listOf(
                StudioWire("request", "body", "parse", "text"),
                StudioWire("parse", "value", "select", "document"),
                StudioWire("select", "value", "map", "items"),
                StudioWire("map", "value", "result", "value"),
            ),
        )
    }

    fun source(medium: StudioMedium = StudioMedium.VIDEO) = StudioSource(
        medium = medium,
        flows = listOf(StudioFlow.SEARCH, StudioFlow.DETAILS, StudioFlow.ENTRIES, StudioFlow.CONTENT).associateWith {
            graph(it, medium)
        },
    )

    fun hasScripts(source: StudioSource): Boolean = (
        source.flows.values +
            source.homeFlows.values +
            source.functions.map { it.graph }
        )
        .any { graph -> graph.nodes.any { it.kind == StudioKind.SCRIPT } }
}
