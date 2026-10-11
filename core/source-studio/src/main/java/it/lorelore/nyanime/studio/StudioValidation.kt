package it.lorelore.nyanime.studio

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import java.net.URI

object StudioValidation {
    fun graph(graph: StudioGraph, requireOutput: Boolean = true): List<StudioIssue> = buildList {
        val nodes = graph.nodes.associateBy { it.id }
        if (nodes.size != graph.nodes.size) add(StudioIssue(null, "id", "Duplicate node identity"))
        if (graph.nodes.size > 128 || graph.wires.size > 512) {
            add(StudioIssue(null, "nodes", "Graph too large"))
        }
        if (requireOutput && graph.nodes.count { it.kind == StudioKind.OUTPUT } != 1) {
            add(StudioIssue(null, "output", "Exactly one result block is required"))
        }
        graph.wires.forEach { wire ->
            val from = nodes[wire.from]
            val to = nodes[wire.to]
            val output = from?.let {
                StudioPorts.outputs(it).find { port -> port.key == wire.output }
            }
            val input = to?.let { StudioPorts.inputs(it).find { port -> port.key == wire.input } }
            when {
                output == null || input == null ->
                    add(StudioIssue(wire.to, wire.input, "Missing block or port"))
                !StudioPorts.accepts(
                    output.type,
                    input.type,
                ) ->
                    add(
                        StudioIssue(
                            wire.to,
                            wire.input,
                            "Incompatible port types: ${output.type} → ${input.type}",
                        ),
                    )
            }
        }
        graph.wires
            .groupBy { it.to to it.input }
            .filterValues { it.size > 1 }
            .keys
            .forEach {
                add(StudioIssue(it.first, it.second, "An input accepts one connection"))
            }
        graph.nodes.forEach { node ->
            StudioPorts.inputs(node)
                .filter { it.required }
                .forEach { port ->
                    if (graph.wires.none { it.to == node.id && it.input == port.key }) {
                        add(StudioIssue(node.id, port.key, "Connect this input"))
                    }
                }
            if (
                node.parameters.values.any {
                    it.length > 131_072
                }
            ) {
                add(StudioIssue(node.id, "parameters", "Block parameter too large"))
            }
            if (!node.x.isFinite() || !node.y.isFinite()) {
                add(StudioIssue(node.id, "position", "Invalid block position"))
            }
            if (node.kind == StudioKind.SELECT && node.parameters["selector"].isNullOrBlank()) {
                add(StudioIssue(node.id, "selector", "Choose a selector"))
            }
            if (node.kind == StudioKind.SCRIPT && node.parameters["code"].isNullOrBlank()) {
                add(StudioIssue(node.id, "code", "Enter a script"))
            }
            if (node.kind == StudioKind.SUBGRAPH && runCatching { StudioCalls.targets(node) }.isFailure) {
                add(StudioIssue(node.id, "function", "Choose valid reusable blocks and branch fields"))
            }
            if (node.kind == StudioKind.INPUT &&
                node.parameters["type"]?.let {
                    it !in setOf("JSON", "LIST", "OBJECT", "BOOLEAN", "NUMBER", "TEXT")
                } == true
            ) {
                add(StudioIssue(node.id, "type", "Unsupported input type"))
            }
            if (node.kind == StudioKind.MAP) {
                val fields = runCatching {
                    StudioDocuments.json
                        .parseToJsonElement(node.parameters["fields"].orEmpty())
                        .jsonObject
                }
                    .getOrNull()
                if (fields == null || fields.size !in 1..40) {
                    add(StudioIssue(node.id, "fields", "Define the result fields"))
                }
                fields?.forEach { (name, raw) ->
                    val rule = raw as? JsonObject
                    if (
                        name.length !in 1..64 ||
                        (
                            rule != null &&
                                rule["mode"]?.studioText()?.let {
                                    it !in setOf("css", "xpath", "json", "constant", "value")
                                } == true
                            )
                    ) {
                        add(StudioIssue(node.id, "fields", "Unsupported field extraction"))
                    }
                }
            }
        }
        if (runCatching { order(graph) }.isFailure) {
            add(StudioIssue(null, "wires", "Connections contain a cycle"))
        }
    }

    fun source(source: StudioSource): List<StudioIssue> = buildList {
        if (source.schema !in 1..2) add(StudioIssue(null, "schema", "Unsupported source schema"))
        if (source.name.isBlank()) add(StudioIssue(null, "name", "Name is required"))
        val base = runCatching { URI(source.baseUrl) }.getOrNull()
        if (
            source.transport == StudioTransport.WEB &&
            (
                base?.scheme !in setOf("https", "http") ||
                    base?.host.isNullOrBlank() ||
                    base?.userInfo != null
                )
        ) {
            add(StudioIssue(null, "baseUrl", "Enter an HTTP or HTTPS address without credentials"))
        }
        if (source.transport == StudioTransport.TELEGRAM) {
            if (source.schema < 2 || source.medium != StudioMedium.NOVEL) {
                add(
                    StudioIssue(
                        null,
                        "transport",
                        "Telegram sources currently support novels with schema 2",
                    ),
                )
            }
            if (runCatching { TelegramPublicChannel.names(source.telegramChannels) }.isFailure) {
                add(
                    StudioIssue(
                        null,
                        "telegramChannels",
                        "Declare one to sixteen public broadcast channels",
                    ),
                )
            }
        } else if (source.telegramChannels.isNotEmpty()) {
            add(StudioIssue(null, "telegramChannels", "Choose Telegram as the source transport"))
        }
        if (
            source.documentResolvers.size > 16 ||
            source.documentResolvers.any {
                !it.matches(Regex("[a-zA-Z][a-zA-Z0-9_.-]{0,159}"))
            }
        ) {
            add(StudioIssue(null, "documentResolvers", "Invalid document component dependency"))
        }
        (source.flows.values + source.homeFlows.values + source.functions.map { it.graph })
            .forEach { graph ->
                graph.nodes.forEach { node ->
                    if (node.kind in StudioCapabilities.kinds && source.schema < 2) {
                        add(StudioIssue(node.id, "schema", "This block requires source schema 2"))
                    }
                    if (
                        node.kind in
                        setOf(
                            StudioKind.TELEGRAM_HISTORY,
                            StudioKind.TELEGRAM_MESSAGE,
                            StudioKind.TELEGRAM_DOCUMENT,
                        ) &&
                        source.transport != StudioTransport.TELEGRAM
                    ) {
                        add(
                            StudioIssue(
                                node.id,
                                "transport",
                                "Declare Telegram access before using this block",
                            ),
                        )
                    }
                    if (
                        node.kind in setOf(StudioKind.DOCUMENT_RESOLVE, StudioKind.DOCUMENT_LIST) &&
                        node.parameters["resolver"] !in source.documentResolvers
                    ) {
                        add(
                            StudioIssue(node.id, "resolver", "Select a declared document component"),
                        )
                    }
                }
            }
        if (source.language.isBlank()) add(StudioIssue(null, "language", "Choose a language"))
        if (!source.language.matches(Regex("[a-z]{2,3}(-[A-Za-z]{2,4})?"))) {
            add(StudioIssue(null, "language", "Use a language code"))
        }
        source.origins.forEach {
            val uri = runCatching { URI(it) }.getOrNull()
            if (
                uri?.scheme !in setOf("https", "http") ||
                uri?.host.isNullOrBlank() ||
                uri?.userInfo != null
            ) {
                add(StudioIssue(null, "origins", "Invalid authorized origin"))
            }
        }
        if (
            source.filters.map { it.id }.distinct().size != source.filters.size ||
            source.filters.map { it.title }.distinct().size != source.filters.size
        ) {
            add(StudioIssue(null, "filters", "Duplicate filter identity or label"))
        }
        source.filters.forEach {
            if (
                it.values.keys.any { label -> label !in it.options } ||
                it.values.values.any { value -> value.length > 4096 }
            ) {
                add(StudioIssue(null, "filters", "Invalid filter values"))
            }
            if (
                !it.id.matches(Regex("[a-z][a-z0-9_-]{0,47}")) ||
                it.title.isBlank() ||
                it.title.length > 100 ||
                it.title == StudioBlueprints.HOME_FILTER
            ) {
                add(StudioIssue(null, "filters", "Invalid filter identity or label"))
            }
            if (
                it.kind !in setOf("single", "multiple", "text") ||
                it.options.size > 200 ||
                it.options.distinct().size != it.options.size ||
                it.options.any { option -> option.isBlank() || option.length > 100 }
            ) {
                add(StudioIssue(null, "filters", "Invalid filter options"))
            }
            if (it.kind != "text" && it.options.isEmpty()) {
                add(StudioIssue(null, "filters", "Add filter options"))
            }
        }
        source.home?.let { home ->
            if (
                home.title.isBlank() ||
                home.sections.size !in 1..16 ||
                home.sections.map { it.id }.distinct().size != home.sections.size
            ) {
                add(StudioIssue(null, "home", "Add distinct Home sections"))
            }
            home.sections.forEach { section ->
                if (
                    !section.id.matches(Regex("[a-z][a-z0-9-]{0,47}")) ||
                    section.title.isBlank() ||
                    section.id in setOf("search", "random", "random-episode")
                ) {
                    add(StudioIssue(null, "home", "Invalid Home section"))
                }
                if (
                    section.layout !in
                    setOf("posters", "featured", "ranking", "chapters", "updates") ||
                    (
                        source.medium == StudioMedium.VIDEO &&
                            section.layout in setOf("chapters", "updates")
                        )
                ) {
                    add(StudioIssue(null, "home", "Unsupported Home layout"))
                }
                if (section.id !in source.homeFlows) {
                    add(StudioIssue(null, "home", "Create the flow for ${section.title}"))
                }
                (section.filters + section.moreFilters.orEmpty()).forEach { (label, value) ->
                    if (
                        source.filters.none {
                            it.title == label && it.kind == "single" && value in it.options
                        }
                    ) {
                        add(StudioIssue(null, "home", "Home selection is not available: $label"))
                    }
                }
                section.dateFilter?.let { label ->
                    if (
                        source.filters.none {
                            it.title == label && it.kind == "text"
                        }
                    ) {
                        add(StudioIssue(null, "home", "Declare the date text filter"))
                    }
                }
                if (
                    listOf(section.groupId, section.groupTitle, section.groupTab).any {
                        it != null
                    } &&
                    listOf(section.groupId, section.groupTitle, section.groupTab).any {
                        it.isNullOrBlank()
                    }
                ) {
                    add(StudioIssue(null, "home", "Complete the section group"))
                }
            }
            if (
                home.categoryFilter != null &&
                source.filters.none { it.title == home.categoryFilter && it.kind != "text" }
            ) {
                add(StudioIssue(null, "home", "Choose a category filter"))
            }
            listOfNotNull(home.randomFlow, home.randomEntryFlow).forEach {
                if (it !in source.homeFlows) {
                    add(StudioIssue(null, "home", "Missing random flow"))
                }
            }
        }
        // Result flows return structured content, while reusable utility graphs may
        // legitimately return text. Never accept raw HTML as a video/title result.
        (source.flows.values + source.homeFlows.values).forEach { graph ->
            val output = graph.nodes.singleOrNull { it.kind == StudioKind.OUTPUT }
            val wire = output?.let { node ->
                graph.wires.singleOrNull { it.to == node.id && it.input == "value" }
            }
            val from = wire?.let { link -> graph.nodes.find { it.id == link.from } }
            val type = from?.let { node ->
                StudioPorts.outputs(node).find { it.key == wire?.output }?.type
            }
            if (
                type in
                setOf(StudioType.HTML, StudioType.TEXT, StudioType.NUMBER, StudioType.BOOLEAN)
            ) {
                add(
                    StudioIssue(
                        output?.id,
                        "value",
                        "Extract result fields before returning the content",
                    ),
                )
            }
        }
        setOf(StudioFlow.SEARCH, StudioFlow.DETAILS, StudioFlow.ENTRIES, StudioFlow.CONTENT)
            .forEach { flow ->
                val graph = source.flows[flow]
                if (graph == null) {
                    add(StudioIssue(null, flow.name, "Missing ${flow.name.lowercase()} flow"))
                } else {
                    addAll(graph(graph).map { it.copy(field = "${flow.name}.${it.field}") })
                    val output = graph.nodes.singleOrNull { it.kind == StudioKind.OUTPUT }
                    val ancestry = mutableSetOf<String>()
                    fun ancestors(id: String) {
                        if (ancestry.add(id)) {
                            graph.wires.filter { it.to == id }.forEach { ancestors(it.from) }
                        }
                    }
                    output?.let { ancestors(it.id) }
                    val lastMapping = runCatching {
                        order(graph)
                    }
                        .getOrDefault(emptyList())
                        .lastOrNull {
                            it.id in ancestry && it.kind == StudioKind.MAP
                        }
                    if (
                        lastMapping != null &&
                        graph.nodes.none {
                            it.id in ancestry &&
                                it.kind in setOf(StudioKind.SCRIPT, StudioKind.SUBGRAPH)
                        }
                    ) {
                        val fields = runCatching {
                            StudioDocuments.json
                                .parseToJsonElement(lastMapping.parameters["fields"].orEmpty())
                                .jsonObject
                        }
                            .getOrNull()
                        (
                            if (flow == StudioFlow.CONTENT && source.medium == StudioMedium.NOVEL) {
                                listOf("body")
                            } else if (flow == StudioFlow.CONTENT) {
                                listOf("url")
                            } else {
                                listOf("title", "url")
                            }
                            )
                            .forEach { field ->
                                val rule = fields?.get(field)
                                if (
                                    rule == null ||
                                    ((rule as? JsonObject)?.get("enabled") as? JsonPrimitive)
                                        ?.booleanOrNull == false
                                ) {
                                    add(
                                        StudioIssue(
                                            lastMapping.id,
                                            "${flow.name}.$field",
                                            "Choose the $field field",
                                        ),
                                    )
                                }
                            }
                    }
                }
            }
        source.flows
            .filterKeys {
                it !in
                    setOf(
                        StudioFlow.SEARCH,
                        StudioFlow.DETAILS,
                        StudioFlow.ENTRIES,
                        StudioFlow.CONTENT,
                    )
            }
            .values
            .forEach { addAll(graph(it)) }
        val allNodes = (
            source.flows.values +
                source.homeFlows.values +
                source.functions.map {
                    it.graph
                }
            ).flatMap { it.nodes }
        if (source.schema < 2 &&
            allNodes.any {
                it.parameters["cases"]?.isNotBlank() == true ||
                    it.parameters["each"] == "true" ||
                    (it.kind == StudioKind.INPUT && it.parameters["type"] != null)
            }
        ) {
            add(StudioIssue(null, "schema", "Typed inputs and lazy reusable branches require schema 2"))
        }
        val functions = source.functions.associateBy { it.id }
        fun inspect(current: StudioGraph, stack: Set<String>) {
            current.nodes
                .filter { it.kind == StudioKind.SUBGRAPH }
                .forEach { node ->
                    val targets = runCatching { StudioCalls.targets(node) }.getOrDefault(emptySet())
                    targets.forEach { id ->
                        val function = functions[id]
                        when {
                            function == null -> add(StudioIssue(node.id, "function", "Choose a reusable block"))
                            id in stack || stack.size >= 4 -> add(
                                StudioIssue(node.id, "function", "Recursive or excessively nested reusable block"),
                            )
                            else -> inspect(function.graph, stack + function.id)
                        }
                    }
                }
        }
        source.functions.forEach { addAll(graph(it.graph)) }
        source.homeFlows.values.forEach {
            addAll(graph(it))
            inspect(it, emptySet())
        }
        source.flows.values.forEach { inspect(it, emptySet()) }
        source.functions.forEach { inspect(it.graph, setOf(it.id)) }
    }

    fun order(graph: StudioGraph): List<StudioNode> {
        val nodes = graph.nodes.associateBy { it.id }
        val incoming =
            nodes.keys.associateWith { id -> graph.wires.count { it.to == id } }.toMutableMap()
        val ready = ArrayDeque(nodes.keys.filter { incoming[it] == 0 })
        val result = mutableListOf<StudioNode>()
        while (ready.isNotEmpty()) {
            val id = ready.removeFirst()
            result += nodes.getValue(id)
            graph.wires
                .filter { it.from == id }
                .forEach { wire ->
                    if (wire.to in incoming) {
                        incoming[wire.to] = incoming.getValue(wire.to) - 1
                        if (incoming[wire.to] == 0) ready += wire.to
                    }
                }
        }
        require(result.size == nodes.size) { "Graph contains a cycle" }
        return result
    }
}
