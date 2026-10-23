package it.lorelore.nyanime.studio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.CallSplit
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FindInPage
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.tachiyomi.R
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

internal fun studioNodeColor(kind: StudioKind): Color =
    when (kind) {
        StudioKind.INPUT,
        StudioKind.CONSTANT,
        -> Color(0xFF7D9DFF)
        StudioKind.TELEGRAM_HISTORY,
        StudioKind.TELEGRAM_MESSAGE,
        StudioKind.TELEGRAM_DOCUMENT,
        -> Color(0xFF5CC4F0)
        StudioKind.DOCUMENT_RESOLVE,
        StudioKind.DOCUMENT_LIST,
        StudioKind.DOCUMENT_TEXT,
        -> Color(0xFFBEA3FF)
        StudioKind.NOVEL_BODY -> Color(0xFF64D6AB)
        StudioKind.HTTP -> Color(0xFF42BCE5)
        StudioKind.HTML,
        StudioKind.JSON,
        StudioKind.SELECT,
        -> Color(0xFF64D6AB)
        StudioKind.MAP,
        StudioKind.TEXT,
        StudioKind.URL,
        StudioKind.LIST,
        -> Color(0xFFFFBE68)
        StudioKind.CONDITION,
        StudioKind.CHOOSE,
        StudioKind.FALLBACK,
        -> Color(0xFFC5A0FF)
        StudioKind.SCRIPT,
        StudioKind.SUBGRAPH,
        -> Color(0xFFFF92C2)
        StudioKind.OUTPUT -> Color(0xFF8CDB75)
    }

internal fun studioNodeIcon(kind: StudioKind): ImageVector =
    when (kind) {
        StudioKind.INPUT,
        StudioKind.CONSTANT,
        -> Icons.Outlined.Keyboard
        StudioKind.TELEGRAM_HISTORY,
        StudioKind.TELEGRAM_MESSAGE,
        StudioKind.TELEGRAM_DOCUMENT,
        -> Icons.Outlined.Public
        StudioKind.DOCUMENT_RESOLVE,
        StudioKind.DOCUMENT_LIST,
        StudioKind.DOCUMENT_TEXT,
        -> Icons.Outlined.FindInPage
        StudioKind.NOVEL_BODY -> Icons.Outlined.FindInPage
        StudioKind.HTTP -> Icons.Outlined.Public
        StudioKind.HTML,
        StudioKind.JSON,
        StudioKind.SELECT,
        -> Icons.Outlined.FindInPage
        StudioKind.MAP,
        StudioKind.TEXT,
        StudioKind.URL,
        StudioKind.LIST,
        -> Icons.Outlined.AutoFixHigh
        StudioKind.CONDITION,
        StudioKind.CHOOSE,
        StudioKind.FALLBACK,
        -> Icons.Outlined.CallSplit
        StudioKind.SCRIPT,
        StudioKind.SUBGRAPH,
        -> Icons.Outlined.Code
        StudioKind.OUTPUT -> Icons.Outlined.TaskAlt
    }

@Composable
internal fun StudioField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    multiline: Boolean = false,
) {
    OutlinedTextField(
        value,
        onChange,
        modifier.fillMaxWidth().padding(vertical = 5.dp),
        label = {
            Text(label)
        },
        singleLine = !multiline,
        minLines = if (multiline) 3 else 1,
        maxLines = if (multiline) 12 else 1,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    )
}

@Composable
internal fun StudioBasics(source: StudioSource, change: (StudioSource) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StudioField(
            stringResource(R.string.studio_name),
            source.name,
            { change(source.copy(name = it)) },
        )
        if (source.medium == StudioMedium.NOVEL) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                androidx.compose.material3.FilterChip(
                    source.transport == StudioTransport.WEB,
                    {
                        change(
                            source.copy(
                                schema = 2,
                                transport = StudioTransport.WEB,
                                telegramChannels = emptyList(),
                            ),
                        )
                    },
                    { Text(stringResource(R.string.studio_transport_web)) },
                )
                androidx.compose.material3.FilterChip(
                    source.transport == StudioTransport.TELEGRAM,
                    { change(source.copy(schema = 2, transport = StudioTransport.TELEGRAM)) },
                    { Text("Telegram") },
                )
            }
        }
        if (source.transport == StudioTransport.WEB) {
            StudioField(
                stringResource(R.string.studio_address),
                source.baseUrl,
                {
                    change(source.copy(baseUrl = it.trim()))
                },
            )
        } else {
            Text(
                stringResource(R.string.studio_telegram_channels_hint),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            StudioField(
                stringResource(R.string.studio_telegram_channels),
                source.telegramChannels.joinToString("\n"),
                {
                    change(
                        source.copy(
                            schema = 2,
                            telegramChannels =
                            it.lines().map(String::trim).filter(String::isNotBlank),
                        ),
                    )
                },
                multiline = true,
            )
        }
        StudioDocumentResolverSelection(source.documentResolvers) {
            change(source.copy(schema = 2, documentResolvers = it))
        }
        StudioField(
            stringResource(R.string.studio_language),
            source.language,
            {
                change(source.copy(language = it.trim()))
            },
        )
        StudioField(
            stringResource(R.string.studio_origins),
            source.origins.joinToString("\n"),
            {
                change(
                    source.copy(
                        origins = it.lines().map(String::trim).filter(String::isNotBlank).toSet(),
                    ),
                )
            },
            multiline = true,
        )
    }
}

@Composable
internal fun StudioHomeEditor(
    source: StudioSource,
    change: (StudioSource) -> Unit,
    edit: (String) -> Unit,
) {
    val home = source.home ?: StudioHome(source.name)
    fun update(next: StudioHome) = change(source.copy(home = next))
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            stringResource(R.string.studio_home_hint),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        StudioField(
            stringResource(R.string.studio_section_title),
            home.title,
            { update(home.copy(title = it)) },
        )
        StudioField(
            stringResource(R.string.studio_category_filter),
            home.categoryFilter.orEmpty(),
            {
                update(home.copy(categoryFilter = it.takeIf(String::isNotBlank)))
            },
        )
        Row {
            Checkbox(home.primary, { update(home.copy(primary = it)) })
            Text(stringResource(R.string.studio_primary), Modifier.padding(top = 12.dp))
        }
        home.sections.forEach { section ->
            OutlinedCard {
                Column(Modifier.padding(16.dp)) {
                    StudioField(
                        stringResource(R.string.studio_section_title),
                        section.title,
                        { title ->
                            update(
                                home.copy(
                                    sections =
                                    home.sections.map {
                                        if (it.id == section.id) {
                                            it.copy(title = title)
                                        } else {
                                            it
                                        }
                                    },
                                ),
                            )
                        },
                    )
                    StudioChoice(
                        stringResource(R.string.studio_layout),
                        section.layout,
                        if (source.medium == StudioMedium.MANGA) {
                            listOf("posters", "featured", "ranking", "chapters", "updates")
                        } else {
                            listOf("posters", "featured", "ranking")
                        },
                    ) { layout ->
                        update(
                            home.copy(
                                sections =
                                home.sections.map {
                                    if (it.id == section.id) {
                                        it.copy(layout = layout)
                                    } else {
                                        it
                                    }
                                },
                            ),
                        )
                    }
                    Text(section.id, style = MaterialTheme.typography.labelSmall)
                    TextButton({ edit(section.id) }) {
                        Text(stringResource(R.string.studio_edit_section))
                    }
                    var advanced by remember(section.id) { mutableStateOf(false) }
                    TextButton({ advanced = !advanced }) {
                        Text(stringResource(R.string.studio_filters))
                    }
                    if (advanced) {
                        StudioMapEditor(section.filters) { filters ->
                            update(
                                home.copy(
                                    sections =
                                    home.sections.map {
                                        if (it.id == section.id) {
                                            it.copy(filters = filters)
                                        } else {
                                            it
                                        }
                                    },
                                ),
                            )
                        }
                        StudioField(
                            stringResource(R.string.studio_group_id),
                            section.groupId.orEmpty(),
                            { value ->
                                update(
                                    home.copy(
                                        sections =
                                        home.sections.map {
                                            if (it.id == section.id) {
                                                it.copy(
                                                    groupId = value.takeIf(String::isNotBlank),
                                                )
                                            } else {
                                                it
                                            }
                                        },
                                    ),
                                )
                            },
                        )
                        StudioField(
                            stringResource(R.string.studio_group_title),
                            section.groupTitle.orEmpty(),
                            { value ->
                                update(
                                    home.copy(
                                        sections =
                                        home.sections.map {
                                            if (it.id == section.id) {
                                                it.copy(
                                                    groupTitle =
                                                    value.takeIf(String::isNotBlank),
                                                )
                                            } else {
                                                it
                                            }
                                        },
                                    ),
                                )
                            },
                        )
                        StudioField(
                            stringResource(R.string.studio_group_tab),
                            section.groupTab.orEmpty(),
                            { value ->
                                update(
                                    home.copy(
                                        sections =
                                        home.sections.map {
                                            if (it.id == section.id) {
                                                it.copy(
                                                    groupTab = value.takeIf(String::isNotBlank),
                                                )
                                            } else {
                                                it
                                            }
                                        },
                                    ),
                                )
                            },
                        )
                        StudioField(
                            stringResource(R.string.studio_date_filter),
                            section.dateFilter.orEmpty(),
                            { value ->
                                update(
                                    home.copy(
                                        sections =
                                        home.sections.map {
                                            if (it.id == section.id) {
                                                it.copy(
                                                    dateFilter =
                                                    value.takeIf(String::isNotBlank),
                                                )
                                            } else {
                                                it
                                            }
                                        },
                                    ),
                                )
                            },
                        )
                        StudioMapEditor(section.moreFilters.orEmpty()) { values ->
                            update(
                                home.copy(
                                    sections =
                                    home.sections.map {
                                        if (it.id == section.id) {
                                            it.copy(
                                                moreFilters =
                                                values.takeIf(
                                                    Map<String, String>::isNotEmpty,
                                                ),
                                            )
                                        } else {
                                            it
                                        }
                                    },
                                ),
                            )
                        }
                    }
                    TextButton({
                        change(
                            source.copy(
                                home = home.copy(sections = home.sections - section),
                                homeFlows = source.homeFlows - section.id,
                            ),
                        )
                    }) {
                        Text(stringResource(R.string.studio_remove))
                    }
                }
            }
        }
        OutlinedButton({
            val id = "section-${UUID.randomUUID().toString().take(8)}"
            change(
                source.copy(
                    home = home.copy(sections = home.sections + StudioHomeSection(id, "")),
                    homeFlows =
                    source.homeFlows +
                        (id to StudioBlueprints.graph(StudioFlow.SEARCH, source.medium)),
                ),
            )
        }) {
            Text(stringResource(R.string.studio_add_section))
        }
    }
}

@Composable
internal fun StudioFiltersEditor(source: StudioSource, change: (StudioSource) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        source.filters.forEachIndexed { index, filter ->
            fun update(next: StudioFilter) =
                change(
                    source.copy(
                        filters =
                        source.filters.mapIndexed { i, item ->
                            if (i == index) {
                                next
                            } else {
                                item
                            }
                        },
                    ),
                )
            OutlinedCard {
                Column(Modifier.padding(16.dp)) {
                    StudioField(
                        stringResource(R.string.studio_filter_label),
                        filter.title,
                        {
                            update(filter.copy(title = it))
                        },
                    )
                    StudioField(
                        stringResource(R.string.studio_filter_id),
                        filter.id,
                        { update(filter.copy(id = it)) },
                    )
                    StudioChoice(
                        stringResource(R.string.studio_filter_kind),
                        filter.kind,
                        listOf("single", "multiple", "text"),
                    ) {
                        update(filter.copy(kind = it))
                    }
                    if (filter.kind != "text") {
                        StudioField(
                            stringResource(R.string.studio_filter_options),
                            filter.options.joinToString("\n"),
                            {
                                update(filter.copy(options = it.lines()))
                            },
                            multiline = true,
                        )
                        if (filter.values.isNotEmpty()) {
                            Text(
                                stringResource(R.string.studio_assist_values),
                                style = MaterialTheme.typography.labelMedium,
                            )
                            StudioMapEditor(filter.values) { values ->
                                update(filter.copy(values = values))
                            }
                        }
                    }
                    StudioField(
                        stringResource(R.string.studio_filter_initial),
                        filter.initial,
                        {
                            update(filter.copy(initial = it))
                        },
                    )
                    Text("{{filter.${filter.id}}}", style = MaterialTheme.typography.labelSmall)
                    TextButton({
                        change(
                            source.copy(
                                filters = source.filters.filterIndexed { i, _ -> i != index },
                            ),
                        )
                    }) {
                        Text(stringResource(R.string.studio_remove))
                    }
                }
            }
        }
        OutlinedButton({
            change(
                source.copy(
                    filters = source.filters + StudioFilter("filter${source.filters.size + 1}", ""),
                ),
            )
        }) {
            Text(stringResource(R.string.studio_add_filter))
        }
    }
}

@Composable
internal fun StudioSecretsEditor(uuid: String, repository: SourceStudioRepository) {
    var name by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    Column {
        Text(stringResource(R.string.studio_secret_hint))
        StudioField(stringResource(R.string.studio_secret_name), name, { name = it })
        OutlinedTextField(
            value,
            {
                value = it
            },
            label = {
                Text(stringResource(R.string.studio_secret_value))
            },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Button({
            scope.launch {
                try {
                    repository.setSecret(uuid, name, value)
                    value = ""
                    message = context.getString(R.string.studio_applied)
                } catch (_: Exception) {
                    message = context.getString(R.string.studio_network_error)
                }
            }
        }) {
            Text(stringResource(R.string.studio_save))
        }
        Text(message)
    }
}

@Composable
internal fun StudioChoice(
    label: String,
    selected: String,
    options: List<String>,
    change: (String) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ menu = true }) { Text("$label · ${studioTerm(selected)}") }
        DropdownMenu(
            menu,
            {
                menu = false
            },
        ) {
            options.forEach { item ->
                DropdownMenuItem(
                    { Text(studioTerm(item)) },
                    {
                        menu = false
                        change(item)
                    },
                )
            }
        }
    }
}

@Composable
internal fun StudioMapEditor(values: Map<String, String>, change: (Map<String, String>) -> Unit) {
    var raw by
        remember(values) {
            mutableStateOf(
                StudioDocuments.json.encodeToString(
                    JsonObject.serializer(),
                    JsonObject(
                        values.mapValues {
                            JsonPrimitive(it.value)
                        },
                    ),
                ),
            )
        }
    var invalid by remember { mutableStateOf(false) }
    StudioField(
        "JSON",
        raw,
        { text ->
            raw = text
            val parsed = runCatching {
                StudioDocuments.json.parseToJsonElement(text).jsonObject.mapValues {
                    it.value.jsonPrimitive.content
                }
            }
            invalid = parsed.isFailure
            parsed.getOrNull()?.let(change)
        },
        multiline = true,
    )
    if (invalid) {
        Text(stringResource(R.string.studio_invalid), color = MaterialTheme.colorScheme.error)
    }
}

@Composable
internal fun StudioNodeInspector(
    source: StudioSource,
    graph: StudioGraph,
    node: StudioNode,
    trace: StudioTrace?,
    change: (StudioNode) -> Unit,
    changeGraph: (StudioGraph) -> Unit,
    test: () -> Unit,
    previewUrl: String = source.baseUrl,
) {
    var picker by remember(node.id) { mutableStateOf<Pair<String, String>?>(null) }
    fun parameter(key: String, value: String) =
        change(node.copy(parameters = node.parameters + (key to value)))
    Column(
        Modifier.fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            studioNodeName(node.kind),
            style = MaterialTheme.typography.titleLarge,
            color = studioNodeColor(node.kind),
        )
        StudioField(
            stringResource(R.string.studio_block_label),
            node.label,
            { change(node.copy(label = it)) },
        )
        var connections by remember(node.id) { mutableStateOf(false) }
        if (StudioPorts.inputs(node).isNotEmpty()) {
            TextButton({
                connections = !connections
            }) {
                Text(stringResource(R.string.studio_connections))
            }
        }
        if (connections) {
            StudioPorts.inputs(node).forEach { port ->
                var connect by remember { mutableStateOf(false) }
                val wire = graph.wires.find { it.to == node.id && it.input == port.key }
                Box {
                    OutlinedButton({
                        connect = true
                    }) {
                        Text(
                            "${port.key} ← ${wire?.let { link ->
                                graph.nodes.find {
                                    it.id == link.from
                                }?.let { it.label.ifBlank { studioNodeName(it.kind) } }
                            } ?: "…"}",
                        )
                    }
                    DropdownMenu(connect, { connect = false }) {
                        if (wire != null) {
                            DropdownMenuItem(
                                { Text(stringResource(R.string.studio_disconnect)) },
                                {
                                    connect = false
                                    changeGraph(graph.copy(wires = graph.wires - wire))
                                },
                            )
                        }
                        graph.nodes
                            .filter { it.id != node.id }
                            .forEach { candidate ->
                                StudioPorts.outputs(candidate)
                                    .filter {
                                        StudioPorts.accepts(it.type, port.type)
                                    }
                                    .forEach { output ->
                                        DropdownMenuItem(
                                            {
                                                Text(
                                                    "${candidate.label.ifBlank {
                                                        studioNodeName(candidate.kind)
                                                    }} · ${output.key}",
                                                )
                                            },
                                            {
                                                connect = false
                                                changeGraph(
                                                    graph.copy(
                                                        wires =
                                                        graph.wires.filterNot {
                                                            it.to == node.id &&
                                                                it.input == port.key
                                                        } +
                                                            StudioWire(
                                                                candidate.id,
                                                                output.key,
                                                                node.id,
                                                                port.key,
                                                            ),
                                                    ),
                                                )
                                            },
                                        )
                                    }
                            }
                    }
                }
            }
        }
        when (node.kind) {
            StudioKind.TELEGRAM_HISTORY,
            StudioKind.TELEGRAM_MESSAGE,
            -> {
                StudioChoice(
                    stringResource(R.string.studio_telegram_channel),
                    node.parameters["channel"].orEmpty(),
                    source.telegramChannels,
                ) {
                    parameter("channel", it)
                }
                if (node.kind == StudioKind.TELEGRAM_HISTORY) {
                    StudioField(
                        stringResource(R.string.studio_telegram_cursor),
                        node.parameters["cursor"] ?: "{{cursor}}",
                        { parameter("cursor", it) },
                    )
                    StudioField(
                        stringResource(R.string.studio_count),
                        node.parameters["limit"] ?: "50",
                        { parameter("limit", it) },
                    )
                } else {
                    StudioField(
                        stringResource(R.string.studio_telegram_message_id),
                        node.parameters["message"].orEmpty(),
                        { parameter("message", it) },
                    )
                }
            }
            StudioKind.DOCUMENT_RESOLVE,
            StudioKind.DOCUMENT_LIST,
            -> {
                StudioDocumentResolverChoice(
                    source.documentResolvers,
                    node.parameters["resolver"].orEmpty(),
                ) {
                    parameter("resolver", it)
                }
            }
            StudioKind.TELEGRAM_DOCUMENT,
            StudioKind.DOCUMENT_TEXT,
            -> Text(stringResource(R.string.studio_document_connect))
            StudioKind.NOVEL_BODY -> {
                StudioChoice(
                    stringResource(R.string.studio_call_type),
                    node.parameters["format"] ?: "HTML",
                    listOf("HTML", "TEXT"),
                ) { parameter("format", it) }
                if (node.parameters["format"] != "TEXT") {
                    StudioField(
                        stringResource(R.string.studio_selector),
                        node.parameters["selector"] ?: "body",
                        { parameter("selector", it) },
                    )
                }
            }
            StudioKind.HTTP -> {
                StudioField(
                    stringResource(R.string.studio_address),
                    node.parameters["url"].orEmpty(),
                    {
                        parameter("url", it)
                    },
                )
                StudioChoice("HTTP", node.parameters["method"] ?: "GET", listOf("GET", "POST")) {
                    parameter("method", it)
                }
                var advanced by remember(node.id) { mutableStateOf(false) }
                TextButton({ advanced = !advanced }) {
                    Text(stringResource(R.string.studio_http_options))
                }
                if (advanced) {
                    StudioField(
                        stringResource(R.string.studio_headers),
                        node.parameters["headers"] ?: "{}",
                        {
                            parameter("headers", it)
                        },
                        multiline = true,
                    )
                    StudioField(
                        stringResource(R.string.studio_body),
                        node.parameters["body"].orEmpty(),
                        {
                            parameter("body", it)
                        },
                        multiline = true,
                    )
                    StudioField(
                        "Content-Type",
                        node.parameters["contentType"] ?: "application/json",
                        { parameter("contentType", it) },
                    )
                }
                Text(
                    "{{query|url}} · {{page}} · {{url}} · {{baseUrl}} · {{cursor}}",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            StudioKind.SELECT -> {
                StudioChoice(
                    stringResource(R.string.studio_format),
                    node.parameters["mode"] ?: "css",
                    listOf("css", "xpath", "json"),
                ) {
                    parameter("mode", it)
                }
                StudioField(
                    stringResource(R.string.studio_selector),
                    node.parameters["selector"].orEmpty(),
                    {
                        parameter("selector", it)
                    },
                )
                if (node.parameters["mode"] != "json") {
                    TextButton({ picker = "selector" to "" }) {
                        Text(stringResource(R.string.studio_pick))
                    }
                }
            }
            StudioKind.MAP -> {
                val fields = runCatching {
                    StudioDocuments.json
                        .parseToJsonElement(node.parameters["fields"].orEmpty())
                        .jsonObject
                }
                    .getOrDefault(JsonObject(emptyMap()))
                Text(
                    stringResource(R.string.studio_fields),
                    style = MaterialTheme.typography.titleMedium,
                )
                fields.forEach { (name, declaration) ->
                    val rule =
                        declaration as? JsonObject
                            ?: buildJsonObject { put("selector", declaration.studioText()) }
                    fun update(key: String, value: JsonElement) =
                        parameter(
                            "fields",
                            JsonObject(
                                fields +
                                    (
                                        name to
                                            JsonObject(
                                                rule +
                                                    mapOf(
                                                        key to value,
                                                        "enabled" to JsonPrimitive(true),
                                                    ),
                                            )
                                        ),
                            )
                                .toString(),
                        )
                    val enabled = (rule["enabled"] as? JsonPrimitive)?.booleanOrNull != false
                    var expanded by
                        remember(node.id, name) { mutableStateOf(name in setOf("title", "url")) }
                    OutlinedCard {
                        Column(Modifier.padding(14.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    enabled,
                                    { value ->
                                        parameter(
                                            "fields",
                                            JsonObject(
                                                fields +
                                                    (
                                                        name to
                                                            JsonObject(
                                                                rule +
                                                                    (
                                                                        "enabled" to
                                                                            JsonPrimitive(value)
                                                                        ),
                                                            )
                                                        ),
                                            )
                                                .toString(),
                                        )
                                        if (value) expanded = true
                                    },
                                )
                                Text(
                                    studioTerm(name),
                                    Modifier.weight(1f).clickable {
                                        expanded = !expanded
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                IconButton({
                                    expanded = !expanded
                                }) {
                                    Icon(
                                        if (expanded) {
                                            Icons.Outlined.ExpandLess
                                        } else {
                                            Icons.Outlined.ExpandMore
                                        },
                                        stringResource(R.string.studio_inspector),
                                    )
                                }
                            }
                            if (expanded) {
                                StudioChoice(
                                    stringResource(R.string.studio_format),
                                    rule["mode"]?.studioText() ?: "css",
                                    listOf("css", "xpath", "json", "constant", "value"),
                                ) {
                                    update("mode", JsonPrimitive(it))
                                }
                                if (rule["mode"]?.studioText() !in setOf("constant", "value")) {
                                    StudioField(
                                        stringResource(R.string.studio_selector),
                                        rule["selector"]?.studioText().orEmpty(),
                                        {
                                            update("selector", JsonPrimitive(it))
                                        },
                                    )
                                }
                                if (rule["mode"]?.studioText() == "constant") {
                                    StudioField(
                                        stringResource(R.string.studio_value),
                                        rule["value"]?.studioText().orEmpty(),
                                        {
                                            update("value", JsonPrimitive(it))
                                        },
                                    )
                                }
                                if (
                                    rule["mode"]?.studioText() !in
                                    setOf("json", "constant", "value")
                                ) {
                                    StudioField(
                                        stringResource(R.string.studio_attribute),
                                        rule["attribute"]?.studioText().orEmpty(),
                                        {
                                            update("attribute", JsonPrimitive(it))
                                        },
                                    )
                                }
                                Row {
                                    Checkbox(
                                        (rule["url"] as? JsonPrimitive)?.booleanOrNull == true,
                                        {
                                            update("url", JsonPrimitive(it))
                                        },
                                    )
                                    Text(
                                        stringResource(R.string.studio_url_field),
                                        Modifier.padding(top = 12.dp),
                                    )
                                }
                                if (
                                    rule["mode"]?.studioText() !in
                                    setOf("json", "constant", "value")
                                ) {
                                    TextButton({
                                        picker = name to "field"
                                    }) {
                                        Text(stringResource(R.string.studio_pick))
                                    }
                                }
                            }
                        }
                    }
                }
                var advanced by remember(node.id) { mutableStateOf(false) }
                TextButton({ advanced = !advanced }) {
                    Text(stringResource(R.string.studio_advanced))
                }
                if (advanced) {
                    StudioField(
                        stringResource(R.string.studio_field_rules),
                        node.parameters["fields"] ?: "{}",
                        {
                            parameter("fields", it)
                        },
                        multiline = true,
                    )
                }
            }
            StudioKind.INPUT -> {
                StudioField("query / page / url / value / filter.ID", node.parameters["key"] ?: "query", {
                    parameter("key", it)
                })
                if (node.parameters["key"] == "value") {
                    StudioChoice(
                        stringResource(R.string.studio_call_type),
                        node.parameters["type"] ?: "TEXT",
                        listOf("TEXT", "JSON", "LIST", "OBJECT", "NUMBER", "BOOLEAN"),
                    ) { parameter("type", it) }
                }
            }
            StudioKind.CONSTANT ->
                StudioField(
                    stringResource(R.string.studio_value),
                    node.parameters["value"].orEmpty(),
                    {
                        parameter("value", it)
                    },
                    multiline = true,
                )
            StudioKind.TEXT -> {
                val operation = node.parameters["operation"] ?: "trim"
                StudioChoice(
                    stringResource(R.string.studio_operation),
                    operation,
                    listOf(
                        "trim",
                        "extract",
                        "replace",
                        "first",
                        "number",
                        "date",
                        "uppercase",
                        "lowercase",
                        "decodeHtml",
                    ),
                ) {
                    parameter("operation", it)
                }
                val parameters =
                    when (operation) {
                        "extract" -> listOf("selector", "attribute")
                        "replace" -> listOf("pattern", "replacement")
                        "date" -> listOf("format")
                        else -> emptyList()
                    }
                (parameters + listOf("prefix", "suffix")).forEach { key ->
                    StudioField(
                        studioTerm(key),
                        node.parameters[key].orEmpty(),
                        { parameter(key, it) },
                    )
                }
            }
            StudioKind.URL ->
                StudioField(
                    stringResource(R.string.studio_address),
                    node.parameters["base"] ?: "{{baseUrl}}",
                    { parameter("base", it) },
                )
            StudioKind.LIST -> {
                StudioChoice(
                    stringResource(R.string.studio_operation),
                    node.parameters["operation"] ?: "wrap",
                    listOf("wrap", "reverse", "unique", "take", "flatten"),
                ) {
                    parameter("operation", it)
                }
                if (node.parameters["operation"] == "take") {
                    StudioField(
                        stringResource(R.string.studio_count),
                        node.parameters["count"] ?: "20",
                        {
                            parameter("count", it)
                        },
                    )
                }
            }
            StudioKind.CONDITION -> {
                StudioChoice(
                    stringResource(R.string.studio_operation),
                    node.parameters["operation"] ?: "exists",
                    listOf("exists", "equals", "contains"),
                ) {
                    parameter("operation", it)
                }
                if (node.parameters["operation"] in setOf("equals", "contains")) {
                    StudioField(
                        stringResource(R.string.studio_expected),
                        node.parameters["expected"].orEmpty(),
                        {
                            parameter("expected", it)
                        },
                    )
                }
            }
            StudioKind.SCRIPT ->
                StudioField(
                    "JavaScript · return …",
                    node.parameters["code"].orEmpty(),
                    {
                        parameter("code", it)
                    },
                    multiline = true,
                )
            StudioKind.SUBGRAPH -> StudioReusableFields(source, node, ::parameter)
            StudioKind.OUTPUT -> Text(stringResource(R.string.studio_next_hint))
            else -> Unit
        }
        StudioValidation.graph(graph, requireOutput = false)
            .filter {
                it.node == node.id
            }
            .forEach {
                Text(
                    it.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        TextButton(test) { Text(stringResource(R.string.studio_test_step)) }
        trace?.let {
            Text("${it.elapsedMillis} ms", style = MaterialTheme.typography.labelSmall)
            it.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            it.outputs.values.forEach { value ->
                if (value.type in setOf(StudioType.JSON, StudioType.LIST, StudioType.OBJECT)) {
                    StudioJsonTree(value.value) { path ->
                        val next =
                            graph.wires
                                .filter { it.from == node.id }
                                .firstNotNullOfOrNull { wire ->
                                    graph.nodes.find {
                                        it.id == wire.to &&
                                            it.kind == StudioKind.SELECT &&
                                            it.parameters["mode"] == "json"
                                    }
                                }
                        if (next != null) {
                            changeGraph(
                                graph.copy(
                                    nodes =
                                    graph.nodes.map {
                                        if (it.id == next.id) {
                                            it.copy(
                                                parameters =
                                                it.parameters + ("selector" to path),
                                            )
                                        } else {
                                            it
                                        }
                                    },
                                ),
                            )
                        }
                    }
                } else {
                    Text(
                        value.value.studioText().take(2000),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        TextButton({
            changeGraph(
                graph.copy(
                    nodes = graph.nodes.filterNot { it.id == node.id },
                    wires =
                    graph.wires.filterNot {
                        it.from == node.id || it.to == node.id
                    },
                ),
            )
        }) {
            Text(stringResource(R.string.studio_remove))
        }
    }
    picker?.let { (key, target) ->
        val selectionNode =
            graph.wires
                .find { it.to == node.id && it.input == "items" }
                ?.let { wire ->
                    graph.nodes.find {
                        it.id == wire.from
                    }
                }
        StudioPagePicker(
            source,
            if (target == "field") {
                selectionNode?.parameters?.get("selector")
            } else {
                null
            },
            previewUrl,
            if (target == "field") {
                key
            } else {
                null
            },
            onDismiss = { picker = null },
        ) { picked ->
            if (target == "field") {
                val fields = runCatching {
                    StudioDocuments.json
                        .parseToJsonElement(node.parameters["fields"].orEmpty())
                        .jsonObject
                }
                    .getOrDefault(JsonObject(emptyMap()))
                val old = fields[key] as? JsonObject ?: JsonObject(emptyMap())
                parameter(
                    "fields",
                    JsonObject(
                        fields +
                            (
                                key to
                                    JsonObject(
                                        old +
                                            mapOf(
                                                "selector" to JsonPrimitive(picked.selector),
                                                "attribute" to JsonPrimitive(picked.attribute),
                                                "enabled" to JsonPrimitive(true),
                                            ),
                                    )
                                ),
                    )
                        .toString(),
                )
            } else {
                parameter(key, picked.similarSelector)
            }
            picker = null
        }
    }
}

@Composable
internal fun StudioResultPreview(value: JsonElement, select: (JsonObject) -> Unit = {}) {
    val items = (value as? JsonArray) ?: ((value as? JsonObject)?.get("items") as? JsonArray)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items?.take(20)?.forEach { raw ->
            val item = raw as? JsonObject
            if (item != null) {
                Row(
                    Modifier.fillMaxWidth().clickable {
                        select(item)
                    },
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val cover =
                        item["cover"]?.studioText()?.takeIf {
                            it.startsWith("https://") || it.startsWith("http://")
                        }
                    if (cover != null) AsyncImage(cover, null, Modifier.width(54.dp).height(80.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            item["title"]?.studioText().orEmpty(),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            item["url"]?.studioText().orEmpty(),
                            maxLines = 2,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        var raw by remember { mutableStateOf(false) }
        if (items != null) {
            TextButton({ raw = !raw }) { Text(stringResource(R.string.studio_raw_result)) }
        }
        if (raw || items == null) StudioJsonTree(value) {}
    }
}

@Composable
internal fun StudioJsonTree(value: JsonElement, select: (String) -> Unit) {
    data class Entry(val path: String, val depth: Int, val value: JsonElement)
    val entries =
        remember(value) {
            val result = mutableListOf<Entry>()
            fun visit(item: JsonElement, path: String, depth: Int) {
                if (result.size >= 160 || depth > 12) return
                result += Entry(path, depth, item)
                when (item) {
                    is JsonObject ->
                        item.forEach { (key, child) ->
                            visit(
                                child,
                                "$path[${JsonPrimitive(key)}]",
                                depth + 1,
                            )
                        }
                    is JsonArray ->
                        item.take(20).forEachIndexed { index, child ->
                            visit(
                                child,
                                "$path[$index]",
                                depth + 1,
                            )
                        }
                    else -> Unit
                }
            }
            visit(value, "$", 0)
            result
        }
    Column {
        entries.forEach { entry ->
            Text(
                "${entry.path}: ${if (entry.value is JsonObject) {
                    "{…}"
                } else if (entry.value is JsonArray) {
                    "[…]"
                } else {
                    entry.value.studioText().take(160)
                }}",
                Modifier.fillMaxWidth()
                    .clickable { select(entry.path) }
                    .padding(
                        start = (entry.depth * 6).dp,
                        top = 5.dp,
                        bottom = 5.dp,
                    ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
