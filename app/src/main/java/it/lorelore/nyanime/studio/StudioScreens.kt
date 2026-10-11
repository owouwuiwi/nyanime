package it.lorelore.nyanime.studio

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.browse.anime.source.browse.BrowseAnimeSourceScreen
import eu.kanade.tachiyomi.ui.browse.manga.source.browse.BrowseMangaSourceScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

class StudioLibraryScreen : Screen() {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val repository = remember { SourceStudioRepository.get(context) }
        val records by repository.records.collectAsState()
        val loadError by repository.error.collectAsState()
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val snackbar = remember { SnackbarHostState() }
        var creating by remember { mutableStateOf(false) }
        var medium by remember { mutableStateOf(StudioMedium.VIDEO) }
        var assisted by remember { mutableStateOf(true) }
        var export by remember { mutableStateOf<StudioSource?>(null) }
        var deleting by remember { mutableStateOf<StudioRecord?>(null) }
        var incoming by remember { mutableStateOf<StudioSource?>(null) }
        val onError: (Exception) -> Unit = { error ->
            scope.launch { snackbar.showSnackbar(error.message ?: context.getString(R.string.studio_network_error)) }
        }
        val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                scope.launch {
                    try {
                        val text =
                            withContext(Dispatchers.IO) {
                                context.contentResolver.openInputStream(uri)!!.use { input ->
                                    val buffer = ByteArray(8192)
                                    val output = java.io.ByteArrayOutputStream()
                                    while (true) {
                                        val count = input.read(buffer)
                                        if (count <
                                            0
                                        ) {
                                            break
                                        }
                                        require(output.size() + count <= StudioDocuments.MAX_DOCUMENT_BYTES) {
                                            "Source file too large"
                                        }
                                        output.write(buffer, 0, count)
                                    }
                                    val bytes = output.toByteArray()
                                    require(bytes.size <= StudioDocuments.MAX_DOCUMENT_BYTES) {
                                        "Source file too large"
                                    }
                                    bytes.toString(Charsets.UTF_8)
                                }
                            }
                        val document = withContext(Dispatchers.Default) { StudioDocuments.decode(text) }
                        if (records.any { it.draft.uuid == document.uuid }) {
                            incoming = document
                        } else {
                            navigator.push(StudioEditorScreen(repository.importDocument(text)))
                        }
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (error: Exception) {
                        onError(error)
                    }
                }
            }
        }
        val exporter =
            rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
                val definition = export
                export = null
                if (uri != null && definition != null) {
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                context.contentResolver.openOutputStream(uri, "wt")!!.use {
                                    it.write(StudioDocuments.encode(definition).toByteArray())
                                }
                            }
                        } catch (
                            cancel: CancellationException,
                        ) {
                            throw cancel
                        } catch (error: Exception) {
                            onError(error)
                        }
                    }
                }
            }
        Scaffold(topBar = {
            TopAppBar(title = { Text(stringResource(R.string.studio_title)) }, navigationIcon = {
                IconButton({
                    navigator.pop()
                }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.studio_close)) }
            })
        }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    Text(stringResource(R.string.studio_editor), style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(R.string.studio_description),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilledTonalButton({ creating = true }, Modifier.weight(1f)) {
                            Icon(Icons.Outlined.Add, null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.studio_create))
                        }
                        OutlinedButton({
                            importer.launch(arrayOf("application/json", "application/octet-stream", "*/*"))
                        }, Modifier.weight(1f)) { Text(stringResource(R.string.studio_import)) }
                    }
                }
                if (loadError != null) item { Text(loadError.orEmpty(), color = MaterialTheme.colorScheme.error) }
                item {
                    OutlinedButton({
                        navigator.push(it.lorelore.nyanime.exchange.SourceExchangeScreen())
                    }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.exchange_title)) }
                }
                if (records.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.studio_empty),
                            Modifier.padding(vertical = 32.dp),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
                items(records, key = { it.draft.uuid }) { record ->
                    var menu by remember { mutableStateOf(false) }
                    ElevatedCard(
                        Modifier.fillMaxWidth().clickable {
                            navigator.push(StudioEditorScreen(record.draft.uuid))
                        },
                        shape = RoundedCornerShape(24.dp),
                    ) {
                        Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    record.draft.name.ifBlank {
                                        stringResource(R.string.studio_draft)
                                    },
                                    style = MaterialTheme.typography.titleLarge,
                                )
                                Text(
                                    stringResource(
                                        if (record.pending) {
                                            R.string.studio_pending
                                        } else if (record.enabled) {
                                            R.string.studio_active
                                        } else {
                                            R.string.studio_draft
                                        },
                                    ),
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(record.draft.baseUrl, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                            }
                            Box {
                                IconButton({
                                    menu = true
                                }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.studio_inspector)) }
                                DropdownMenu(menu, { menu = false }) {
                                    if (record.enabled && record.active != null) {
                                        DropdownMenuItem({ Text(stringResource(R.string.store_open)) }, {
                                            menu = false
                                            navigator.push(
                                                if (record.active.medium == StudioMedium.VIDEO) {
                                                    BrowseAnimeSourceScreen(record.active.sourceId, null)
                                                } else {
                                                    BrowseMangaSourceScreen(record.active.sourceId, null)
                                                },
                                            )
                                        })
                                    }
                                    DropdownMenuItem({ Text(stringResource(R.string.studio_export)) }, {
                                        menu = false
                                        export =
                                            record.draft
                                        exporter.launch(
                                            "${record.draft.name.replace(
                                                Regex("[^\\p{L}\\p{N}._-]"),
                                                "-",
                                            ).take(70).ifBlank {
                                                "source"
                                            }}.nyastudio",
                                        )
                                    })
                                    DropdownMenuItem({ Text(stringResource(R.string.studio_duplicate)) }, {
                                        menu = false
                                        scope.launch {
                                            try {
                                                val copy = record.draft.duplicate()
                                                repository.saveDraft(copy)
                                                navigator.push(StudioEditorScreen(copy.uuid))
                                            } catch (
                                                error: Exception,
                                            ) {
                                                onError(error)
                                            }
                                        }
                                    })
                                    DropdownMenuItem({ Text(stringResource(R.string.exchange_send)) }, {
                                        menu = false
                                        navigator.push(
                                            it.lorelore.nyanime.exchange.SourceExchangeScreen(
                                                studioUuid = record.draft.uuid,
                                            ),
                                        )
                                    })
                                    if (record.enabled) {
                                        DropdownMenuItem({ Text(stringResource(R.string.studio_disable)) }, {
                                            menu =
                                                false
                                            scope.launch {
                                                try {
                                                    repository.disable(record.draft.uuid)
                                                } catch (
                                                    error: Exception,
                                                ) {
                                                    onError(error)
                                                }
                                            }
                                        })
                                    }
                                    if (record.previous !=
                                        null
                                    ) {
                                        DropdownMenuItem({ Text(stringResource(R.string.studio_rollback)) }, {
                                            menu =
                                                false
                                            scope.launch {
                                                try {
                                                    repository.rollback(record.draft.uuid)
                                                } catch (
                                                    error: Exception,
                                                ) {
                                                    onError(error)
                                                }
                                            }
                                        })
                                    }
                                    DropdownMenuItem({ Text(stringResource(R.string.studio_remove)) }, {
                                        menu = false
                                        deleting =
                                            record
                                    })
                                }
                            }
                        }
                    }
                }
            }
        }
        if (creating) {
            AlertDialog(onDismissRequest = {
                creating = false
            }, title = { Text(stringResource(R.string.studio_create)) }, text = {
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilterChip(assisted, { assisted = true }, { Text(stringResource(R.string.studio_assist)) })
                        FilterChip(!assisted, { assisted = false }, { Text(stringResource(R.string.studio_canvas)) })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StudioMedium.entries.forEach { option ->
                            FilterChip(medium == option, { medium = option }, {
                                Text(
                                    stringResource(
                                        if (option ==
                                            StudioMedium.VIDEO
                                        ) {
                                            R.string.studio_video
                                        } else {
                                            R.string.studio_manga
                                        },
                                    ),
                                )
                            })
                        }
                    }
                }
            }, confirmButton = {
                TextButton({
                    creating = false
                    scope.launch {
                        try {
                            val source = StudioBlueprints.source(medium)
                            repository.saveDraft(source)
                            navigator.push(StudioEditorScreen(source.uuid, startAssisted = assisted))
                        } catch (
                            error: Exception,
                        ) {
                            onError(error)
                        }
                    }
                }) { Text(stringResource(R.string.studio_create)) }
            }, dismissButton = {
                TextButton({
                    creating =
                        false
                }) { Text(stringResource(R.string.studio_cancel)) }
            })
        }
        deleting?.let { record ->
            AlertDialog(onDismissRequest = {
                deleting = null
            }, title = {
                Text(record.draft.name)
            }, text = { Text(stringResource(R.string.studio_remove_question)) }, confirmButton = {
                TextButton({
                    deleting =
                        null
                    scope.launch {
                        try {
                            repository.remove(record.draft.uuid)
                        } catch (
                            error: Exception,
                        ) {
                            onError(error)
                        }
                    }
                }) { Text(stringResource(R.string.studio_remove)) }
            }, dismissButton = {
                TextButton({
                    deleting =
                        null
                }) { Text(stringResource(R.string.studio_cancel)) }
            })
        }
        incoming?.let { document ->
            fun finish(duplicate: Boolean) {
                incoming = null
                scope.launch {
                    try {
                        navigator.push(
                            StudioEditorScreen(repository.importDocument(StudioDocuments.encode(document), duplicate)),
                        )
                    } catch (
                        cancel: CancellationException,
                    ) {
                        throw cancel
                    } catch (error: Exception) {
                        onError(error)
                    }
                }
            }
            AlertDialog(
                onDismissRequest = {
                    incoming = null
                },
                title = { Text(stringResource(R.string.studio_import_existing)) },
                text = { Text(stringResource(R.string.studio_import_existing_body)) },
                confirmButton = {
                    TextButton({ finish(false) }) { Text(stringResource(R.string.studio_import_update)) }
                },
                dismissButton = { TextButton({ finish(true) }) { Text(stringResource(R.string.studio_import_copy)) } },
            )
        }
    }
}

internal class StudioEditorModel(val repository: SourceStudioRepository, uuid: String) : ScreenModel {
    val source = MutableStateFlow<StudioSource?>(null)
    val error = MutableStateFlow<String?>(null)
    val traces = MutableStateFlow<Map<String, StudioTrace>>(emptyMap())
    val result = MutableStateFlow<JsonElement?>(null)
    val running = MutableStateFlow(false)
    var history: StudioHistory? = null
    private var saving: Job? = null
    private var execution: Job? = null
    private var generation = 0L
    private val traceLock = Any()
    init {
        screenModelScope.launch {
            try {
                val draft = repository.draft(uuid)
                history = StudioHistory(draft)
                source.value = draft
            } catch (
                cancel: CancellationException,
            ) {
                throw cancel
            } catch (failure: Exception) {
                error.value =
                    failure.message
            }
        }
    }
    fun change(next: StudioSource) {
        stop()
        history?.change(next)
        source.value = next
        scheduleSave()
    }
    fun alignRevision(revision: Long) {
        history?.alignRevision(revision)
        source.value = source.value?.let { it.copy(revision = maxOf(it.revision, revision)) }
    }
    fun undo() {
        stop()
        source.value = history?.undo()
        scheduleSave()
    }
    fun redo() {
        stop()
        source.value = history?.redo()
        scheduleSave()
    }
    private fun scheduleSave() {
        saving?.cancel()
        saving = screenModelScope.launch {
            delay(500)
            try {
                flush()
            } catch (
                cancel: CancellationException,
            ) {
                throw cancel
            } catch (failure: Exception) {
                error.value =
                    failure.message ?: "Unable to save the draft"
            }
        }
    }
    suspend fun flush() {
        source.value?.let { repository.saveDraft(it) }
    }
    fun stop() {
        synchronized(traceLock) {
            generation++
            // Superseded callbacks are ignored, so close the visible running state here.
            // Keep completed diagnostics without leaving animated nodes after cancellation.
            traces.update { current -> current.filterValues { !it.running } }
        }
        execution?.cancel()
        running.value = false
    }
    fun run(
        flow: StudioFlow,
        graph: StudioGraph,
        variables: JsonObject,
        until: String? = null,
        allowScripts: Boolean = false,
    ) {
        stop()
        val definition = source.value ?: return
        val request = generation
        traces.value = emptyMap()
        result.value = null
        error.value = null
        running.value = true
        execution = screenModelScope.launch {
            try {
                val output = repository.execute(definition, flow, variables, until, allowScripts, trace = { trace ->
                    synchronized(traceLock) {
                        if (generation == request) traces.update { it + (trace.node to trace) }
                    }
                }, overrideGraph = graph)
                if (generation == request) result.value = output.value
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                if (generation == request) error.value = failure.message ?: "Execution failed"
            } finally {
                if (generation == request) {
                    running.value = false
                    traces.update { current -> current.filterValues { !it.running } }
                }
            }
        }
    }
    override fun onDispose() {
        stop()
    }
}
