package it.lorelore.nyanime.studio

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.long
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

@Serializable
enum class StudioMedium { VIDEO, MANGA }

@Serializable
enum class StudioFlow { SEARCH, POPULAR, LATEST, DETAILS, ENTRIES, CONTENT }

@Serializable
enum class StudioType { TEXT, NUMBER, BOOLEAN, HTML, JSON, LIST, OBJECT, ANY }

@Serializable
enum class StudioKind {
    INPUT,
    CONSTANT,
    HTTP,
    HTML,
    JSON,
    SELECT,
    MAP,
    TEXT,
    URL,
    LIST,
    CONDITION,
    CHOOSE,
    FALLBACK,
    SCRIPT,
    SUBGRAPH,
    OUTPUT,
}

@Serializable
data class StudioNode(
    val id: String = UUID.randomUUID().toString(),
    val kind: StudioKind,
    val label: String = "",
    val x: Float = 40f,
    val y: Float = 40f,
    val parameters: Map<String, String> = emptyMap(),
)

@Serializable
data class StudioWire(val from: String, val output: String, val to: String, val input: String)

@Serializable
data class StudioGraph(val nodes: List<StudioNode> = emptyList(), val wires: List<StudioWire> = emptyList())

@Serializable
data class StudioFunction(val id: String, val name: String, val graph: StudioGraph)

@Serializable
data class StudioFilter(
    val id: String,
    val title: String,
    val kind: String = "single",
    val options: List<String> = emptyList(),
    val initial: String = "",
    val values: Map<String, String> = emptyMap(),
)

@Serializable
data class StudioHomeSection(
    val id: String,
    val title: String,
    val layout: String = "posters",
    val filters: Map<String, String> = emptyMap(),
    val groupId: String? = null,
    val groupTitle: String? = null,
    val groupTab: String? = null,
    val dateFilter: String? = null,
    val moreFilters: Map<String, String>? = null,
)

@Serializable
data class StudioHome(
    val title: String,
    val sections: List<StudioHomeSection> = emptyList(),
    val categoryFilter: String? = null,
    val excludedCategories: List<String> = emptyList(),
    val primary: Boolean = false,
    val randomFlow: String? = null,
    val randomEntryFlow: String? = null,
)

/** Only user-authored data. This document has no provider defaults or executable Android code. */
@Serializable
data class StudioSource(
    val schema: Int = 1,
    val uuid: String = UUID.randomUUID().toString(),
    val revision: Long = 1,
    val name: String = "",
    val language: String = "it",
    val medium: StudioMedium = StudioMedium.VIDEO,
    val baseUrl: String = "",
    val origins: Set<String> = emptySet(),
    val flows: Map<StudioFlow, StudioGraph> = emptyMap(),
    val functions: List<StudioFunction> = emptyList(),
    val adult: Boolean = false,
    val filters: List<StudioFilter> = emptyList(),
    val home: StudioHome? = null,
    val homeFlows: Map<String, StudioGraph> = emptyMap(),
) {
    val sourceId: Long
        get() = ByteBuffer.wrap(
            MessageDigest.getInstance("SHA-256").digest("studio-v1:$uuid:$medium".toByteArray(Charsets.UTF_8)),
        ).long.and(Long.MAX_VALUE).coerceAtLeast(1)

    fun duplicate(): StudioSource = copy(uuid = UUID.randomUUID().toString(), revision = 1)
}

data class StudioPort(val key: String, val type: StudioType, val required: Boolean = true)

object StudioPorts {
    fun inputs(node: StudioNode): List<StudioPort> = when (node.kind) {
        StudioKind.INPUT, StudioKind.CONSTANT -> emptyList()
        StudioKind.HTTP -> listOf(StudioPort("url", StudioType.TEXT, false))
        StudioKind.HTML, StudioKind.JSON -> listOf(StudioPort("text", StudioType.TEXT))
        StudioKind.SELECT -> listOf(
            StudioPort(
                "document",
                if (node.parameters["mode"] ==
                    "json"
                ) {
                    StudioType.JSON
                } else {
                    StudioType.HTML
                },
            ),
        )
        StudioKind.MAP -> listOf(StudioPort("items", StudioType.LIST))
        StudioKind.TEXT -> listOf(StudioPort("value", StudioType.ANY), StudioPort("context", StudioType.ANY, false))
        StudioKind.URL -> listOf(StudioPort("value", StudioType.TEXT))
        StudioKind.LIST -> listOf(StudioPort("value", StudioType.ANY))
        StudioKind.CONDITION -> listOf(StudioPort("value", StudioType.ANY))
        StudioKind.CHOOSE -> listOf(
            StudioPort("condition", StudioType.BOOLEAN),
            StudioPort("yes", StudioType.ANY),
            StudioPort("no", StudioType.ANY),
        )
        StudioKind.FALLBACK -> listOf(StudioPort("value", StudioType.ANY), StudioPort("fallback", StudioType.ANY))
        StudioKind.SCRIPT, StudioKind.SUBGRAPH -> listOf(StudioPort("value", StudioType.ANY, false))
        StudioKind.OUTPUT -> listOf(
            StudioPort(
                "value",
                if (node.parameters["structured"] ==
                    "true"
                ) {
                    StudioType.JSON
                } else {
                    StudioType.ANY
                },
            ),
            StudioPort("hasNext", StudioType.BOOLEAN, false),
            StudioPort("cursor", StudioType.TEXT, false),
        )
    }

    fun outputs(node: StudioNode): List<StudioPort> = when (node.kind) {
        StudioKind.INPUT -> listOf(
            StudioPort(
                "value",
                if (node.parameters["key"] ==
                    "page"
                ) {
                    StudioType.NUMBER
                } else {
                    StudioType.TEXT
                },
            ),
        )
        StudioKind.CONSTANT -> listOf(StudioPort("value", StudioType.TEXT))
        StudioKind.HTTP -> listOf(
            StudioPort("body", StudioType.TEXT),
            StudioPort("url", StudioType.TEXT),
            StudioPort("status", StudioType.NUMBER),
        )
        StudioKind.HTML -> listOf(StudioPort("value", StudioType.HTML))
        StudioKind.JSON, StudioKind.SCRIPT, StudioKind.SUBGRAPH, StudioKind.CHOOSE, StudioKind.FALLBACK -> listOf(
            StudioPort("value", StudioType.JSON),
        )
        StudioKind.SELECT, StudioKind.MAP, StudioKind.LIST -> listOf(StudioPort("value", StudioType.LIST))
        StudioKind.CONDITION -> listOf(StudioPort("value", StudioType.BOOLEAN))
        StudioKind.TEXT, StudioKind.URL -> listOf(StudioPort("value", StudioType.TEXT))
        StudioKind.OUTPUT -> emptyList()
    }

    fun accepts(from: StudioType, to: StudioType): Boolean = to == StudioType.ANY ||
        from == to ||
        (to == StudioType.JSON && from in setOf(StudioType.LIST, StudioType.OBJECT))
}

data class StudioValue(val type: StudioType, val value: JsonElement = JsonNull, val baseUrl: String? = null)
data class StudioIssue(val node: String?, val field: String, val message: String)

/** Validation and import are strict. Unknown operations cannot turn into no-ops. */
object StudioDocuments {
    const val MAX_DOCUMENT_BYTES = 2 * 1024 * 1024
    val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    fun decode(text: String): StudioSource {
        require(text.toByteArray().size <= MAX_DOCUMENT_BYTES) { "Document too large" }
        return json.decodeFromString<StudioSource>(text).also {
            require(it.schema == 1) { "Unsupported source schema" }
            UUID.fromString(it.uuid)
            require(it.name.length <= 160 && it.functions.size <= 32 && it.revision > 0) { "Invalid source metadata" }
            require(
                it.flows.values.all { graph ->
                    graph.nodes.size <= 128 && graph.wires.size <= 512
                },
            ) { "Graph too large" }
            require(
                it.functions.all { fn ->
                    fn.graph.nodes.size <= 128 && fn.graph.wires.size <= 512
                },
            ) { "Function too large" }
            require(it.functions.map(StudioFunction::id).distinct().size == it.functions.size) { "Duplicate function" }
            require(it.homeFlows.size <= 24 && it.filters.size <= 24) { "Too many Home flows or filters" }
            require(
                it.homeFlows.values.all { graph ->
                    graph.nodes.size <= 128 && graph.wires.size <= 512
                },
            ) { "Home graph too large" }
        }
    }

    fun encode(source: StudioSource): String = json.encodeToString(source)
    fun fingerprint(source: StudioSource): String = MessageDigest.getInstance("SHA-256")
        .digest(encode(source).toByteArray()).joinToString("") { "%02x".format(it) }
}

class StudioHistory(initial: StudioSource) {
    private val past = ArrayDeque<StudioSource>()
    private val future = ArrayDeque<StudioSource>()
    var current: StudioSource = initial
        private set
    val canUndo get() = past.isNotEmpty()
    val canRedo get() = future.isNotEmpty()
    fun change(next: StudioSource) {
        if (next == current) return
        past.addLast(current)
        if (past.size > 80) past.removeFirst()
        future.clear()
        current = next
    }
    fun alignRevision(revision: Long) {
        current = current.copy(revision = maxOf(current.revision, revision))
    }
    fun undo(): StudioSource {
        if (past.isNotEmpty()) {
            future.addLast(current)
            current = past.removeLast().copy(revision = current.revision)
        }
        return current
    }
    fun redo(): StudioSource {
        if (future.isNotEmpty()) {
            past.addLast(current)
            current = future.removeLast().copy(revision = current.revision)
        }
        return current
    }
}

data class StudioTrace(
    val node: String,
    val running: Boolean = false,
    val elapsedMillis: Long = 0,
    val outputs: Map<String, StudioValue> = emptyMap(),
    val error: String? = null,
    val truncated: Boolean = false,
)

data class StudioResult(val value: JsonElement, val traces: List<StudioTrace>)

data class StudioHttpResponse(val body: String, val url: String, val status: Int)
fun interface StudioHttp {
    suspend fun request(
        source: StudioSource,
        node: StudioNode,
        url: String,
        variables: JsonObject,
    ): StudioHttpResponse
}
fun interface StudioScript {
    suspend fun evaluate(code: String, input: JsonElement): JsonElement
}
