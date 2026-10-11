package it.lorelore.nyanime.studio

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.motion.ModernMotion
import eu.kanade.presentation.motion.appMotionEnabled
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

class StudioEditorScreen(private val uuid: String, private val startAssisted: Boolean = false) : Screen() {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val model = rememberScreenModel { StudioEditorModel(SourceStudioRepository.get(context), uuid) }
        val assistantModel =
            rememberScreenModel(tag = "assistant") { StudioAssistantModel(SourceStudioRepository.get(context)) }
        var assistant by rememberSaveable { mutableStateOf(startAssisted) }
        val source by model.source.collectAsState()
        val error by model.error.collectAsState()
        val traces by model.traces.collectAsState()
        val result by model.result.collectAsState()
        val running by model.running.collectAsState()
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val snackbar = remember { SnackbarHostState() }
        var tab by rememberSaveable { mutableStateOf("basics") }
        var canvas by rememberSaveable { mutableStateOf(false) }
        var selected by remember { mutableStateOf<String?>(null) }
        var palette by remember { mutableStateOf(false) }
        var preview by remember { mutableStateOf(false) }
        var query by rememberSaveable { mutableStateOf("") }
        var testUrl by rememberSaveable { mutableStateOf("") }
        var approval by remember { mutableStateOf<(() -> Unit)?>(null) }
        val motion = appMotionEnabled()
        val wide = LocalConfiguration.current.screenWidthDp >= 840
        val compact = LocalConfiguration.current.screenHeightDp < 480
        val canvasStates = rememberSaveableStateHolder()
        val back: () -> Unit = {
            scope.launch {
                try {
                    model.flush()
                    navigator.pop()
                } catch (
                    failure: Exception,
                ) {
                    model.error.value =
                        failure.message
                }
            }
        }
        BackHandler(onBack = back)
        val document = source
        if (document == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                if (error == null) {
                    CircularProgressIndicator()
                } else {
                    Column(Modifier.padding(24.dp)) {
                        Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                        TextButton(back) { Text(stringResource(R.string.studio_close)) }
                    }
                }
            }
            return
        }
        val flow =
            tab.removePrefix("flow:").let { name -> StudioFlow.entries.find { it.name == name } } ?: StudioFlow.SEARCH
        val graph = when {
            tab.startsWith("home:") -> document.homeFlows[tab.removePrefix("home:")]
            tab.startsWith("function:") -> document.functions.find { it.id == tab.removePrefix("function:") }?.graph
            tab.startsWith("flow:") -> document.flows[flow]
            else -> null
        }
        fun changeGraph(next: StudioGraph) {
            model.change(
                when {
                    tab.startsWith("home:") -> document.copy(
                        homeFlows =
                        document.homeFlows + (tab.removePrefix("home:") to next),
                    )
                    tab.startsWith("function:") -> document.copy(
                        functions = document.functions.map {
                            if (it.id ==
                                tab.removePrefix("function:")
                            ) {
                                it.copy(graph = next)
                            } else {
                                it
                            }
                        },
                    )
                    else -> document.copy(flows = document.flows + (flow to next))
                },
            )
        }
        fun authorize(action: (Boolean) -> Unit) {
            if (StudioBlueprints.hasScripts(document)) {
                approval =
                    { action(true) }
            } else {
                action(false)
            }
        }
        fun test(until: String? = null) {
            if (graph == null) return
            authorize { allowed ->
                selected = null
                model.run(
                    flow,
                    graph,
                    buildJsonObject {
                        put("query", query)
                        put("page", 1)
                        put("url", testUrl.ifBlank { document.baseUrl })
                        document.filters.forEach { put("filter.${it.id}", StudioAssist.filterValue(it, it.initial)) }
                    },
                    until,
                    allowed,
                )
                preview = true
            }
        }
        LaunchedEffect(tab) {
            selected = null
            model.stop()
            model.result.value = null
            model.traces.value = emptyMap()
        }
        fun activate() {
            authorize { allowed ->
                scope.launch {
                    try {
                        model.flush()
                        val publication = model.repository.activate(uuid, allowed)
                        model.alignRevision(publication.draft.revision)
                        snackbar.showSnackbar(
                            context.getString(
                                if (publication.pending) R.string.studio_pending else R.string.studio_ready,
                            ),
                        )
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (failure: Exception) {
                        model.error.value = failure.message
                    }
                }
            }
        }
        val graphControls: @Composable () -> Unit = {
            Row(
                Modifier.padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                FilterChip(!canvas, { canvas = false }, { Text(stringResource(R.string.studio_guided)) })
                FilterChip(canvas, { canvas = true }, { Text(stringResource(R.string.studio_canvas)) })
                StudioIconAction(stringResource(R.string.studio_add_block), Icons.Outlined.Add) { palette = true }
                StudioIconAction(stringResource(R.string.studio_result), Icons.Outlined.Visibility) { preview = true }
            }
        }
        Scaffold(topBar = {
            TopAppBar(title = {
                Text(document.name.ifBlank { stringResource(R.string.studio_editor) }, maxLines = 1)
            }, navigationIcon = {
                IconButton(back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.studio_close)) }
            }, actions = {
                IconButton(model::undo, enabled = model.history?.canUndo == true) {
                    Icon(Icons.AutoMirrored.Outlined.Undo, stringResource(R.string.studio_undo))
                }
                IconButton(model::redo, enabled = model.history?.canRedo == true) {
                    Icon(Icons.AutoMirrored.Outlined.Redo, stringResource(R.string.studio_redo))
                }
                if (compact) {
                    if (graph != null) {
                        StudioIconAction(
                            stringResource(if (running) R.string.studio_stop else R.string.studio_test),
                            if (running) Icons.Outlined.Stop else Icons.Outlined.PlayArrow,
                        ) { if (running) model.stop() else test() }
                    }
                    StudioIconAction(
                        stringResource(R.string.studio_activate),
                        Icons.Outlined.Check,
                        enabled = !running,
                    ) {
                        activate()
                    }
                }
            }, expandedHeight = if (compact) 48.dp else 64.dp)
        }, snackbarHost = { SnackbarHost(snackbar) }, bottomBar = {
            if (!compact) {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (graph !=
                            null
                        ) {
                            OutlinedButton({
                                if (running) model.stop() else test()
                            }, Modifier.weight(1f)) {
                                Text(stringResource(if (running) R.string.studio_stop else R.string.studio_test))
                            }
                        }
                        Button(::activate, Modifier.weight(1f), enabled = !running) {
                            Text(stringResource(R.string.studio_activate))
                        }
                    }
                }
            }
        }) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                Column(Modifier.weight(1f)) {
                    var destinationMenu by remember { mutableStateOf(false) }
                    val destinations = listOf(
                        "basics" to stringResource(R.string.studio_basics),
                        "home" to stringResource(R.string.studio_home),
                        "filters" to stringResource(R.string.studio_filters),
                        "secret" to stringResource(R.string.studio_secret),
                        "functions" to stringResource(R.string.studio_reusable),
                    ) +
                        StudioFlow.entries.map { "flow:${it.name}" to studioFlowName(it) } +
                        document.homeFlows.keys.map { key ->
                            "home:$key" to (document.home?.sections?.find { it.id == key }?.title ?: key)
                        } +
                        document.functions.map { "function:${it.id}" to it.name }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Box(Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 6.dp)) {
                            FilledTonalButton({
                                destinationMenu = true
                            }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                                Text(
                                    destinations.find { it.first == tab }?.second.orEmpty(),
                                    Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                                Icon(Icons.Outlined.ExpandMore, null)
                            }
                            DropdownMenu(destinationMenu, {
                                destinationMenu = false
                            }, Modifier.heightIn(max = 440.dp)) {
                                destinations.forEach { (key, title) ->
                                    DropdownMenuItem({ Text(title) }, {
                                        destinationMenu = false
                                        if (key.startsWith("flow:")) {
                                            val chosen = StudioFlow.valueOf(key.removePrefix("flow:"))
                                            if (chosen !in document.flows) {
                                                model.change(
                                                    document.copy(
                                                        flows =
                                                        document.flows +
                                                            (chosen to StudioBlueprints.graph(chosen, document.medium)),
                                                    ),
                                                )
                                            }
                                        }
                                        tab = key
                                    })
                                }
                            }
                        }
                        if (compact && graph != null) graphControls()
                    }
                    if (error !=
                        null
                    ) {
                        Text(
                            error.orEmpty(),
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (graph != null) {
                        if (!compact) graphControls()
                        if (canvas) {
                            Box(Modifier.weight(1f)) {
                                canvasStates.SaveableStateProvider(tab) {
                                    StudioCanvas(graph, selected, traces, {
                                        selected =
                                            it
                                    }, ::changeGraph)
                                }
                            }
                        } else {
                            LazyColumn(
                                Modifier.weight(1f),
                                contentPadding = PaddingValues(20.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                item {
                                    Text(
                                        stringResource(R.string.studio_flow_guide),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (flow ==
                                        StudioFlow.SEARCH
                                    ) {
                                        StudioField(stringResource(R.string.studio_test_query), query, {
                                            query =
                                                it
                                        })
                                    } else {
                                        StudioField(stringResource(R.string.studio_test_url), testUrl, { testUrl = it })
                                    }
                                }
                                items(
                                    runCatching {
                                        StudioValidation.order(graph)
                                    }.getOrDefault(graph.nodes),
                                    key = { it.id },
                                ) { node ->
                                    Card(
                                        Modifier.fillMaxWidth().clickable {
                                            selected = node.id
                                        },
                                        shape = RoundedCornerShape(20.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = studioNodeColor(node.kind).copy(alpha = .12f),
                                        ),
                                    ) {
                                        Column(
                                            Modifier.padding(18.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                Icon(
                                                    studioNodeIcon(node.kind),
                                                    null,
                                                    Modifier.size(22.dp),
                                                    tint = studioNodeColor(node.kind),
                                                )
                                                Text(
                                                    node.label.ifBlank {
                                                        studioNodeName(node.kind)
                                                    },
                                                    style = MaterialTheme.typography.titleMedium,
                                                )
                                            }
                                            Text(
                                                node.parameters["url"] ?: node.parameters["selector"]
                                                    ?: node.parameters["operation"]
                                                    ?: node.parameters["function"].orEmpty(),
                                                maxLines = 2,
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                            traces[node.id]?.let { trace ->
                                                if (trace.running) {
                                                    LinearProgressIndicator(Modifier.fillMaxWidth())
                                                } else {
                                                    Text(
                                                        trace.error ?: "${trace.elapsedMillis} ms",
                                                        color = if (trace.error !=
                                                            null
                                                        ) {
                                                            MaterialTheme.colorScheme.error
                                                        } else {
                                                            MaterialTheme.colorScheme.primary
                                                        },
                                                        style = MaterialTheme.typography.labelSmall,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                item {
                                    Text(
                                        stringResource(R.string.studio_test_hint),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    } else {
                        AnimatedContent(tab, transitionSpec = {
                            ModernMotion.transform(motion)
                        }, label = "studio-section") { section ->
                            LazyColumn(
                                contentPadding = PaddingValues(20.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                when (section) {
                                    "basics" -> item {
                                        FilledTonalButton({ assistant = true }, Modifier.fillMaxWidth()) {
                                            Text(stringResource(R.string.studio_assist_start))
                                        }
                                        StudioBasics(document, model::change)
                                    }
                                    "home" -> item { StudioHomeEditor(document, model::change, { tab = "home:$it" }) }
                                    "filters" -> item { StudioFiltersEditor(document, model::change) }
                                    "secret" -> item { StudioSecretsEditor(document.uuid, model.repository) }
                                    "functions" -> {
                                        items(document.functions, key = { it.id }) { function ->
                                            OutlinedCard(
                                                Modifier.fillMaxWidth().clickable {
                                                    tab =
                                                        "function:${function.id}"
                                                },
                                            ) { Text(function.name, Modifier.padding(20.dp)) }
                                        }
                                        item {
                                            OutlinedButton({
                                                val id = UUID.randomUUID().toString()
                                                val input =
                                                    StudioNode(
                                                        kind = StudioKind.INPUT,
                                                        parameters = mapOf("key" to "value"),
                                                    )
                                                val output = StudioNode(kind = StudioKind.OUTPUT, x = 340f)
                                                model.change(
                                                    document.copy(
                                                        functions =
                                                        document.functions +
                                                            StudioFunction(
                                                                id,
                                                                context.getString(
                                                                    R.string.studio_new_function,
                                                                    document.functions.size + 1,
                                                                ),
                                                                StudioGraph(
                                                                    listOf(input, output),
                                                                    listOf(
                                                                        StudioWire(
                                                                            input.id,
                                                                            "value",
                                                                            output.id,
                                                                            "value",
                                                                        ),
                                                                    ),
                                                                ),
                                                            ),
                                                    ),
                                                )
                                                tab = "function:$id"
                                            }) { Text(stringResource(R.string.studio_add_function)) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (wide && graph != null) {
                    selected?.let { id ->
                        graph.nodes.find { it.id == id }?.let { node ->
                            Surface(Modifier.width(360.dp).fillMaxHeight(), tonalElevation = 2.dp) {
                                Column {
                                    TextButton({
                                        selected = null
                                    }, Modifier.padding(horizontal = 12.dp)) {
                                        Text(stringResource(R.string.studio_close))
                                    }
                                    StudioNodeInspector(document, graph, node, traces[node.id], { updated ->
                                        changeGraph(
                                            graph.copy(
                                                nodes = graph.nodes.map {
                                                    if (it.id ==
                                                        node.id
                                                    ) {
                                                        updated
                                                    } else {
                                                        it
                                                    }
                                                },
                                            ),
                                        )
                                    }, ::changeGraph, { test(node.id) }, testUrl.ifBlank { document.baseUrl })
                                }
                            }
                        }
                    }
                }
            }
        }
        if (assistant) {
            StudioAssistant(document, assistantModel, model::change) { assistant = false }
        }
        if (palette && graph != null) {
            ModalBottomSheet(onDismissRequest = { palette = false }) {
                var find by remember { mutableStateOf("") }
                StudioField(stringResource(R.string.studio_add_block), find, {
                    find = it
                }, modifier = Modifier.padding(horizontal = 20.dp))
                val matchingKinds = StudioKind.entries.filter { kind ->
                    studioNodeName(kind).contains(find, true) || kind.name.contains(find, true)
                }
                LazyColumn(contentPadding = PaddingValues(20.dp)) {
                    items(matchingKinds) { kind ->
                        ListItem(
                            headlineContent = { Text(studioNodeName(kind)) },
                            modifier = Modifier.clickable {
                                val node = StudioNode(kind = kind, x = (graph.nodes.maxOfOrNull { it.x } ?: 0f) + 268f)
                                changeGraph(graph.copy(nodes = graph.nodes + node))
                                selected = node.id
                                palette = false
                            },
                        )
                    }
                }
            }
        }
        if (!wide) {
            selected?.let { id ->
                graph?.nodes?.find { it.id == id }?.let { node ->
                    ModalBottomSheet(onDismissRequest = {
                        selected = null
                    }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                        StudioNodeInspector(document, graph, node, traces[node.id], { updated ->
                            changeGraph(
                                graph.copy(
                                    nodes = graph.nodes.map {
                                        if (it.id ==
                                            node.id
                                        ) {
                                            updated
                                        } else {
                                            it
                                        }
                                    },
                                ),
                            )
                        }, ::changeGraph, { test(node.id) }, testUrl.ifBlank { document.baseUrl })
                    }
                }
            }
        }
        if (preview) {
            ModalBottomSheet(onDismissRequest = {
                preview = false
            }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                var showTrace by remember { mutableStateOf(false) }
                LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Text(stringResource(R.string.studio_result), style = MaterialTheme.typography.titleLarge)
                        StudioField(stringResource(R.string.studio_test_query), query, {
                            query =
                                it
                        })
                        StudioField(stringResource(R.string.studio_test_url), testUrl, { testUrl = it })
                    }
                    item {
                        if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
                        TextButton({
                            if (running) model.stop() else test()
                        }) { Text(stringResource(if (running) R.string.studio_stop else R.string.studio_test)) }
                    }
                    if (error != null) item { Text(error.orEmpty(), color = MaterialTheme.colorScheme.error) }
                    result?.let { value ->
                        item {
                            StudioResultPreview(value) { row ->
                                if (flow !=
                                    StudioFlow.CONTENT
                                ) {
                                    row["url"]?.studioText()?.takeIf { it.isNotBlank() }?.let { address ->
                                        testUrl = java.net.URI(document.baseUrl).resolve(address).toASCIIString()
                                        tab =
                                            when (flow) {
                                                StudioFlow.ENTRIES -> "flow:CONTENT"
                                                StudioFlow.DETAILS -> "flow:ENTRIES"
                                                else -> "flow:DETAILS"
                                            }
                                        preview = false
                                    }
                                }
                            }
                        }
                    }
                    if (traces.isNotEmpty()) {
                        item {
                            TextButton({ showTrace = !showTrace }) { Text(stringResource(R.string.studio_advanced)) }
                        }
                    }
                    items(if (showTrace) traces.values.toList() else emptyList(), key = { it.node }) { trace ->
                        Column {
                            Text(
                                "${trace.node} · ${trace.elapsedMillis} ms",
                                style = MaterialTheme.typography.labelLarge,
                            )
                            trace.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            trace.outputs.forEach { (port, value) ->
                                Text(
                                    "$port: ${value.value.studioText().take(1200)}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }
        if (approval !=
            null
        ) {
            AlertDialog(onDismissRequest = {
                approval = null
            }, title = {
                Text(stringResource(R.string.studio_script_title))
            }, text = { Text(stringResource(R.string.studio_script_body)) }, confirmButton = {
                TextButton({
                    val action = approval
                    approval =
                        null
                    action?.invoke()
                }) { Text(stringResource(R.string.studio_authorize)) }
            }, dismissButton = {
                TextButton({
                    approval =
                        null
                }) { Text(stringResource(R.string.studio_cancel)) }
            })
        }
    }
}

@Composable internal fun studioFlowName(flow: StudioFlow): String = stringResource(
    when (flow) {
        StudioFlow.SEARCH -> R.string.studio_search
        StudioFlow.POPULAR -> R.string.studio_popular
        StudioFlow.LATEST -> R.string.studio_latest
        StudioFlow.DETAILS -> R.string.studio_details
        StudioFlow.ENTRIES -> R.string.studio_entries
        StudioFlow.CONTENT -> R.string.studio_content
    },
)

@Composable internal fun studioNodeName(kind: StudioKind): String = stringResource(
    when (kind) {
        StudioKind.INPUT -> R.string.studio_node_input
        StudioKind.CONSTANT -> R.string.studio_node_constant
        StudioKind.HTTP -> R.string.studio_node_http
        StudioKind.HTML -> R.string.studio_node_html
        StudioKind.JSON -> R.string.studio_node_json
        StudioKind.SELECT -> R.string.studio_node_select
        StudioKind.MAP -> R.string.studio_node_map
        StudioKind.TEXT -> R.string.studio_node_text
        StudioKind.URL -> R.string.studio_node_url
        StudioKind.LIST -> R.string.studio_node_list
        StudioKind.CONDITION -> R.string.studio_node_condition
        StudioKind.CHOOSE -> R.string.studio_node_choose
        StudioKind.FALLBACK -> R.string.studio_node_fallback
        StudioKind.SCRIPT -> R.string.studio_node_script
        StudioKind.SUBGRAPH -> R.string.studio_node_subgraph
        StudioKind.OUTPUT -> R.string.studio_node_output
    },
)
