package it.lorelore.nyanime.studio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.time.TimeSource

/** One bounded, cancellable invocation. Editor and installed sources use this same executor. */
class StudioExecutor(private val http: StudioHttp, private val scripts: StudioScript) {
    suspend fun run(
        source: StudioSource,
        flow: StudioFlow,
        variables: JsonObject,
        untilNode: String? = null,
        onTrace: (StudioTrace) -> Unit = {},
        overrideGraph: StudioGraph? = null,
    ): StudioResult = withContext(Dispatchers.Default) {
        withTimeout(45_000) {
            val graph = overrideGraph ?: source.flows[flow] ?: error("Missing ${flow.name.lowercase()} flow")
            val traces = mutableListOf<StudioTrace>()
            var remaining = 512
            var requests = 16
            var traceCharacters = 0
            suspend fun executeGraph(current: StudioGraph, input: JsonObject, depth: Int, until: String?): JsonElement {
                require(depth <= 4) { "Reusable block nesting limit" }
                val terminal = until ?: current.nodes.singleOrNull { it.kind == StudioKind.OUTPUT }?.id
                    ?: error("Choose one result block")
                require(current.nodes.any { it.id == terminal }) { "Block not found" }
                val needed = linkedSetOf(terminal)
                var changed: Boolean
                do {
                    changed = false
                    current.wires.filter { it.to in needed }.forEach { if (needed.add(it.from)) changed = true }
                } while (changed)
                val graphToRun = current.copy(
                    nodes = current.nodes.filter {
                        it.id in needed
                    },
                    wires = current.wires.filter {
                        it.to in
                            needed
                    },
                )
                val issues = StudioValidation.graph(graphToRun, requireOutput = until == null)
                require(issues.isEmpty()) { issues.firstOrNull()?.message ?: "Invalid graph" }
                val values = mutableMapOf<Pair<String, String>, StudioValue>()
                var retainedCharacters = 0
                var result: JsonElement = JsonNull
                for (node in StudioValidation.order(graphToRun)) {
                    currentCoroutineContext().ensureActive()
                    require(--remaining >= 0) { "Execution budget exhausted" }
                    val started = TimeSource.Monotonic.markNow()
                    onTrace(StudioTrace(node.id, running = true))
                    try {
                        val incoming = graphToRun.wires.filter { it.to == node.id }.associate { wire ->
                            wire.input to (values[wire.from to wire.output] ?: error("Missing input ${wire.input}"))
                        }
                        val params = node.parameters
                        fun value(name: String = "value") = incoming[name]?.value ?: JsonNull
                        fun text(name: String = "value") = value(name).studioText()
                        val base =
                            incoming.values.firstNotNullOfOrNull { it.baseUrl }
                                ?: input["url"]?.studioText()?.takeIf { it.isNotBlank() }
                                ?: source.baseUrl
                        val variables =
                            input +
                                incoming.mapValues { it.value.value } +
                                mapOf("baseUrl" to JsonPrimitive(source.baseUrl)) +
                                values.mapKeys { (port, _) ->
                                    "node.${port.first}.${port.second}"
                                }.mapValues { it.value.value }
                        fun expand(raw: String) = StudioTemplates.expand(raw, variables)
                        val output: Map<String, StudioValue> = when (node.kind) {
                            StudioKind.INPUT -> mapOf(
                                "value" to
                                    StudioValue(
                                        StudioPorts.outputs(node).single().type,
                                        input[params["key"] ?: "query"] ?: JsonPrimitive(""),
                                    ),
                            )
                            StudioKind.CONSTANT -> mapOf(
                                "value" to
                                    StudioValue(StudioType.TEXT, JsonPrimitive(expand(params["value"].orEmpty()))),
                            )
                            StudioKind.HTTP -> {
                                require(--requests >= 0) { "Network request budget exhausted" }
                                val url = incoming["url"]?.value?.studioText()?.takeIf { it.isNotBlank() }
                                    ?: expand(params["url"].orEmpty())
                                val response = http.request(
                                    source,
                                    node,
                                    URI(base).resolve(url).toASCIIString(),
                                    JsonObject(variables),
                                )
                                mapOf(
                                    "body" to StudioValue(StudioType.TEXT, JsonPrimitive(response.body), response.url),
                                    "url" to StudioValue(StudioType.TEXT, JsonPrimitive(response.url)),
                                    "status" to StudioValue(StudioType.NUMBER, JsonPrimitive(response.status)),
                                )
                            }
                            StudioKind.HTML -> mapOf(
                                "value" to StudioValue(StudioType.HTML, JsonPrimitive(text("text"))),
                            )
                            StudioKind.JSON -> mapOf(
                                "value" to
                                    StudioValue(StudioType.JSON, StudioDocuments.json.parseToJsonElement(text("text"))),
                            )
                            StudioKind.SELECT -> {
                                val selector = expand(params["selector"].orEmpty())
                                val items = if (params["mode"] == "json") {
                                    StudioJsonPath.select(value("document"), selector)
                                } else {
                                    val document = Jsoup.parse(text("document"), base)
                                    val elements = if (params["mode"] ==
                                        "xpath"
                                    ) {
                                        document.selectXpath(selector)
                                    } else {
                                        document.select(selector)
                                    }
                                    require(elements.size <= 2000) { "Too many selected elements" }
                                    elements.map { JsonPrimitive(it.outerHtml()) }
                                }
                                mapOf("value" to StudioValue(StudioType.LIST, JsonArray(items)))
                            }
                            StudioKind.MAP -> {
                                val fields = StudioDocuments.json.parseToJsonElement(
                                    params["fields"].orEmpty().ifBlank {
                                        "{}"
                                    },
                                ).jsonObject
                                require(fields.size in 1..40) { "Define the result fields" }
                                val items = value("items").jsonArray
                                require(items.size <= 2000) { "Too many result items" }
                                val mapped = items.map { item ->
                                    JsonObject(
                                        fields.mapValues { (_, rule) ->
                                            StudioFields.extract(item, rule, base, variables)
                                        },
                                    )
                                }
                                mapOf("value" to StudioValue(StudioType.LIST, JsonArray(mapped)))
                            }
                            StudioKind.TEXT -> {
                                val raw = text()
                                val transformed = when (params["operation"]) {
                                    "replace" -> {
                                        val expression = params["pattern"].orEmpty()
                                        require(expression.length <= 256 && raw.length <= 65_536) {
                                            "Text operation too large"
                                        }
                                        com.google.re2j.Pattern.compile(
                                            expression,
                                        ).matcher(raw).replaceAll(params["replacement"].orEmpty())
                                    }
                                    "number" -> (raw.trim().toDoubleOrNull() ?: error("Invalid number")).toString()
                                    "date" -> SimpleDateFormat(params["format"] ?: "yyyy-MM-dd", Locale.ROOT).apply {
                                        isLenient =
                                            false
                                    }.parse(raw)?.time?.toString()
                                        ?: error("Invalid date")
                                    "lowercase" -> raw.lowercase(Locale.ROOT)
                                    "uppercase" -> raw.uppercase(Locale.ROOT)
                                    "decodeHtml" -> Jsoup.parse(raw).text()
                                    "first" -> (value() as? JsonArray)?.firstOrNull()?.studioText().orEmpty()
                                    "extract" -> {
                                        val document = Jsoup.parse(raw, base)
                                        val selected = document.selectFirst(
                                            expand(params["selector"].orEmpty()).ifBlank {
                                                "body"
                                            },
                                        )
                                        if (params["attribute"].isNullOrBlank()) {
                                            selected?.text().orEmpty()
                                        } else {
                                            selected?.attr(
                                                params["attribute"].orEmpty(),
                                            ).orEmpty()
                                        }
                                    }
                                    else -> raw.trim()
                                }
                                mapOf(
                                    "value" to
                                        StudioValue(
                                            StudioType.TEXT,
                                            JsonPrimitive(
                                                expand(params["prefix"].orEmpty()) +
                                                    transformed +
                                                    expand(params["suffix"].orEmpty()),
                                            ),
                                        ),
                                )
                            }
                            StudioKind.URL -> mapOf(
                                "value" to
                                    StudioValue(
                                        StudioType.TEXT,
                                        JsonPrimitive(
                                            URI(
                                                expand(
                                                    params["base"].orEmpty().ifBlank {
                                                        base
                                                    },
                                                ),
                                            ).resolve(text()).toASCIIString(),
                                        ),
                                    ),
                            )
                            StudioKind.LIST -> {
                                val items = (value() as? JsonArray)?.toList() ?: listOf(value())
                                val mapped = when (params["operation"]) {
                                    "reverse" -> items.reversed()
                                    "unique" -> items.distinct()
                                    "take" -> items.take(params["count"]?.toIntOrNull()?.coerceIn(0, 2000) ?: 20)
                                    "flatten" -> items.flatMap {
                                        (it as? JsonArray)?.toList() ?: listOf(it)
                                    }.also { require(it.size <= 2000) }
                                    else -> items
                                }
                                mapOf("value" to StudioValue(StudioType.LIST, JsonArray(mapped)))
                            }
                            StudioKind.CONDITION -> mapOf(
                                "value" to StudioValue(
                                    StudioType.BOOLEAN,
                                    JsonPrimitive(
                                        when (params["operation"]) {
                                            "equals" -> text() == expand(params["expected"].orEmpty())
                                            "contains" -> text().contains(expand(params["expected"].orEmpty()))
                                            else -> when (val checked = value()) {
                                                is JsonArray -> checked.isNotEmpty()
                                                is JsonObject -> checked.isNotEmpty()
                                                else ->
                                                    checked !=
                                                        JsonNull &&
                                                        text().isNotBlank()
                                            }
                                        },
                                    ),
                                ),
                            )
                            StudioKind.CHOOSE -> mapOf(
                                "value" to
                                    StudioValue(
                                        StudioType.JSON,
                                        value(if (value("condition").jsonPrimitive.boolean) "yes" else "no"),
                                    ),
                            )
                            StudioKind.FALLBACK -> mapOf(
                                "value" to
                                    StudioValue(
                                        StudioType.JSON,
                                        value().takeUnless {
                                            it == JsonNull ||
                                                it.studioText().isBlank() ||
                                                it == JsonArray(emptyList())
                                        }
                                            ?: value("fallback"),
                                    ),
                            )
                            StudioKind.SCRIPT -> mapOf(
                                "value" to
                                    StudioValue(StudioType.JSON, scripts.evaluate(params["code"].orEmpty(), value())),
                            )
                            StudioKind.SUBGRAPH -> {
                                val function =
                                    source.functions.singleOrNull { it.id == params["function"] }
                                        ?: error("Reusable block unavailable")
                                mapOf(
                                    "value" to
                                        StudioValue(
                                            StudioType.JSON,
                                            executeGraph(
                                                function.graph,
                                                JsonObject(input + ("value" to value())),
                                                depth + 1,
                                                null,
                                            ),
                                        ),
                                )
                            }
                            StudioKind.OUTPUT -> {
                                result = if ("hasNext" in incoming || "cursor" in incoming) {
                                    buildJsonObject {
                                        put("items", value())
                                        put(
                                            "hasNext",
                                            value("hasNext").takeUnless { it == JsonNull }
                                                ?: JsonPrimitive(text("cursor").isNotBlank()),
                                        )
                                        put("cursor", value("cursor"))
                                    }
                                } else {
                                    value()
                                }
                                emptyMap()
                            }
                        }
                        var truncated = false
                        val preview = mutableMapOf<String, StudioValue>()
                        output.forEach { (port, data) ->
                            val encoded = data.value.toString()
                            require(encoded.length <= 2_097_152) { "Block output too large" }
                            retainedCharacters += encoded.length
                            require(retainedCharacters <= 8_388_608) {
                                "Flow memory budget exhausted: split the operation into reusable flows"
                            }
                            values[node.id to port] = if (data.baseUrl == null) data.copy(baseUrl = base) else data
                            val available = (524_288 - traceCharacters).coerceIn(0, 16_384)
                            val snapshot = if (encoded.length <=
                                available
                            ) {
                                data
                            } else {
                                StudioValue(StudioType.TEXT, JsonPrimitive(encoded.take(available)))
                            }
                            if (snapshot !== data) truncated = true
                            traceCharacters += snapshot.value.toString().length
                            preview[port] = snapshot
                        }
                        if (node.id == terminal &&
                            node.kind != StudioKind.OUTPUT
                        ) {
                            result = output.values.firstOrNull()?.value ?: JsonNull
                        }
                        val trace =
                            StudioTrace(
                                node.id,
                                elapsedMillis = started.elapsedNow().inWholeMilliseconds,
                                outputs = preview,
                                truncated = truncated,
                            )
                        traces += trace
                        onTrace(trace)
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (failure: Exception) {
                        val trace =
                            StudioTrace(
                                node.id,
                                elapsedMillis = started.elapsedNow().inWholeMilliseconds,
                                error =
                                failure.message?.take(300) ?: "Block failed",
                            )
                        traces += trace
                        onTrace(trace)
                        throw StudioExecutionException(node.id, trace.error.orEmpty(), traces.toList())
                    }
                }
                return result
            }
            StudioResult(executeGraph(graph, variables, 0, untilNode), traces)
        }
    }
}

class StudioExecutionException(val node: String, message: String, val traces: List<StudioTrace>) : Exception(message)

fun JsonElement.studioText(): String = when (this) {
    is JsonPrimitive -> contentOrNull.orEmpty()
    JsonNull -> ""
    else -> toString()
}

object StudioTemplates {
    private val pattern = Regex("\\{\\{([A-Za-z0-9_.-]+)(\\|url)?\\}\\}")
    fun expand(template: String, variables: Map<String, JsonElement>): String = pattern.replace(template) { match ->
        val key = match.groupValues[1]
        // Secret expansion belongs solely to the authorized network broker, never script inputs or traces.
        require(!key.startsWith("secret.")) { "Secrets may only be used in HTTP headers" }
        val value = variables[key]?.studioText() ?: ""
        if (match.groupValues[2].isNotEmpty()) URLEncoder.encode(value, "UTF-8") else value
    }
}

object StudioJsonPath {
    fun select(root: JsonElement, path: String): List<JsonElement> {
        require(path.startsWith("$") && path.length <= 512) { "A JSON path starts with $" }
        var current = listOf(root)
        var index = 1
        while (index < path.length) {
            val key: String
            var wildcard = false
            when (path[index]) {
                '.' -> {
                    val start = ++index
                    while (index < path.length && path[index] !in ".[") index++
                    key = path.substring(start, index)
                    require(key.isNotBlank()) { "Empty JSON property" }
                    wildcard = key == "*"
                }
                '[' -> {
                    index++
                    while (index < path.length && path[index].isWhitespace()) index++
                    require(index < path.length) { "Unclosed JSON path" }
                    if (path[index] == '\'' || path[index] == '"') {
                        val quote = path[index++]
                        val text = StringBuilder()
                        var closed = false
                        while (index < path.length) {
                            val character = path[index++]
                            if (character == quote) {
                                closed = true
                                break
                            }
                            if (character != '\\') {
                                text.append(character)
                            } else {
                                require(index < path.length) { "Unclosed JSON escape" }
                                text.append(
                                    when (val escaped = path[index++]) {
                                        '\\', '\'', '"', '/' -> escaped
                                        'n' -> '\n'
                                        'r' -> '\r'
                                        't' -> '\t'
                                        'b' -> '\b'
                                        'f' -> '\u000C'
                                        'u' -> {
                                            require(index + 4 <= path.length) { "Invalid JSON Unicode escape" }
                                            val code =
                                                path.substring(index, index + 4).toIntOrNull(16)
                                                    ?: error("Invalid JSON Unicode escape")
                                            index += 4
                                            code.toChar()
                                        }
                                        else -> error("Unsupported JSON escape")
                                    },
                                )
                            }
                        }
                        require(closed) { "Unclosed JSON property" }
                        key = text.toString()
                    } else {
                        val start = index
                        while (index < path.length && path[index] != ']') index++
                        key = path.substring(start, index).trim()
                        wildcard = key == "*"
                        require(
                            wildcard ||
                                key.toIntOrNull()?.let {
                                    it >= 0
                                } == true,
                        ) { "Use a quoted JSON property or array index" }
                    }
                    while (index < path.length && path[index].isWhitespace()) index++
                    require(index < path.length && path[index] == ']') { "Unclosed JSON path" }
                    index++
                }
                else -> error("Unsupported JSON path at $index")
            }
            current = buildList {
                fun append(value: JsonElement?) {
                    if (value == null) return
                    require(size < 2000) { "JSON selection too large" }
                    add(value)
                }
                current.forEach { value ->
                    when {
                        wildcard && value is JsonArray -> value.forEach(::append)
                        wildcard && value is JsonObject -> value.values.forEach(::append)
                        value is JsonObject -> append(value[key])
                        value is JsonArray -> key.toIntOrNull()?.let { append(value.getOrNull(it)) }
                        else -> Unit
                    }
                }
            }
        }
        return current
    }
}

object StudioFields {
    fun extract(
        item: JsonElement,
        declaration: JsonElement,
        base: String,
        variables: Map<String, JsonElement> = emptyMap(),
    ): JsonElement {
        val rule = if (declaration is JsonObject) {
            declaration
        } else {
            buildJsonObject {
                put("selector", declaration.studioText())
            }
        }
        if (rule["enabled"]?.jsonPrimitive?.booleanOrNull == false) return JsonNull
        val selector = StudioTemplates.expand(rule["selector"]?.studioText().orEmpty(), variables)
        var result: JsonElement = when (rule["mode"]?.studioText()) {
            "constant" -> rule["value"]?.let {
                if (it is JsonPrimitive &&
                    it.isString
                ) {
                    JsonPrimitive(StudioTemplates.expand(it.content, variables))
                } else {
                    it
                }
            }
                ?: JsonPrimitive("")
            "json" -> StudioJsonPath.select(item, selector.ifBlank { "$" }).firstOrNull() ?: JsonNull
            "value" -> item
            else -> {
                val document = Jsoup.parse(item.studioText(), base)
                val selected = if (selector.isBlank()) {
                    document.body()
                } else if (rule["mode"]?.studioText() ==
                    "xpath"
                ) {
                    document.selectXpath(selector).first()
                } else {
                    document.selectFirst(selector)
                }
                val attribute = rule["attribute"]?.studioText().orEmpty()
                JsonPrimitive(
                    when {
                        selected == null -> ""
                        attribute == "html" -> selected.html()
                        attribute.isNotBlank() && attribute != "text" -> selected.attr(attribute)
                        else -> selected.text()
                    },
                )
            }
        }
        if (result == JsonNull || result.studioText().isBlank()) result = rule["fallback"] ?: JsonPrimitive("")
        rule["pattern"]?.studioText()?.takeIf { it.isNotBlank() }?.let { pattern ->
            require(pattern.length <= 256 && result.studioText().length <= 65_536) { "Text operation too large" }
            result =
                JsonPrimitive(
                    com.google.re2j.Pattern.compile(
                        pattern,
                    ).matcher(result.studioText()).replaceAll(rule["replacement"]?.studioText().orEmpty()),
                )
        }
        (rule["mapping"] as? JsonObject)?.let { mapping -> result = mapping[result.studioText()] ?: result }
        if (rule["url"]?.jsonPrimitive?.booleanOrNull == true && result.studioText().isNotBlank()) {
            result = JsonPrimitive(URI(base).resolve(result.studioText()).toASCIIString())
        }
        return when (rule["type"]?.studioText()) {
            "number" -> result.studioText().toDoubleOrNull()?.takeIf { it.isFinite() }?.let(::JsonPrimitive) ?: JsonNull
            "boolean" -> JsonPrimitive(result.studioText().equals("true", ignoreCase = true))
            else -> result
        }
    }
}
