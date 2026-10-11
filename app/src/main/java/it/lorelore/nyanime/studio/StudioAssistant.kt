package it.lorelore.nyanime.studio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.presentation.motion.appMotionEnabled
import eu.kanade.tachiyomi.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.util.UUID

internal enum class StudioAssistTask(val flow: StudioFlow) {
    SEARCH(StudioFlow.SEARCH),
    DETAILS(StudioFlow.DETAILS),
    ENTRIES(StudioFlow.ENTRIES),
    CONTENT(StudioFlow.CONTENT),
    HOME(StudioFlow.SEARCH),
    FILTER(StudioFlow.SEARCH),
}

internal enum class StudioAssistError { EXAMPLE, OTHER_PAGE }

internal data class StudioAssistState(
    val task: StudioAssistTask? = null,
    val query: String = "",
    val searchExample: String = "",
    val page: String = "",
    val label: String = "",
    val group: String = "",
    val list: StudioPickedElement? = null,
    val fields: Map<String, StudioPickedElement> = emptyMap(),
    val next: StudioPickedElement? = null,
    val option: StudioPickedElement? = null,
    val picking: String? = null,
    val running: Boolean = false,
    val result: JsonElement? = null,
    val proposal: StudioSource? = null,
    val baseline: String? = null,
    val error: StudioAssistError? = null,
    val saved: Set<StudioAssistTask> = emptySet(),
)

/** Transient evidence stays in this screen model. Only approved, ordinary graphs reach the repository. */
internal class StudioAssistantModel(private val repository: SourceStudioRepository) : ScreenModel {
    var state by mutableStateOf(StudioAssistState())
        private set
    private var job: Job? = null
    private var generation = 0L

    fun edit(transform: (StudioAssistState) -> StudioAssistState) {
        stop()
        state = transform(state).copy(proposal = null, result = null, baseline = null, error = null)
    }

    fun choose(task: StudioAssistTask?) = edit {
        StudioAssistState(
            task = task,
            query = it.query,
            searchExample = it.searchExample,
            page = if (task in
                setOf(StudioAssistTask.SEARCH, StudioAssistTask.FILTER)
            ) {
                it.searchExample.ifBlank { it.page }
            } else {
                it.page
            },
            saved = it.saved,
        )
    }

    fun stop() {
        generation++
        job?.cancel()
        state = state.copy(running = false)
    }

    fun picked(slot: String, item: StudioPickedElement) {
        if (slot !in setOf("list", "next", "option") &&
            (
                (state.list != null && state.list?.pageUrl != item.pageUrl) ||
                    (state.task == StudioAssistTask.DETAILS && state.fields.isNotEmpty() && state.page != item.pageUrl)
                )
        ) {
            edit { it.copy(picking = null) }
            state = state.copy(error = StudioAssistError.OTHER_PAGE)
            return
        }
        edit { old ->
            when (slot) {
                "list" -> old.copy(list = item, page = item.pageUrl, fields = emptyMap(), next = null)
                "next" -> old.copy(next = item)
                "option" -> old.copy(option = item, label = item.text.take(100))
                else -> old.copy(fields = old.fields + (slot to item), page = item.pageUrl)
            }.copy(picking = null)
        }
    }

    fun saved() = edit { old ->
        val nextTask = when (old.task) {
            StudioAssistTask.SEARCH -> StudioAssistTask.DETAILS
            StudioAssistTask.DETAILS -> StudioAssistTask.ENTRIES
            StudioAssistTask.ENTRIES -> StudioAssistTask.CONTENT
            else -> null
        }
        val rows = (old.result as? JsonArray) ?: ((old.result as? JsonObject)?.get("items") as? JsonArray)
        fun normalized(value: String?) = value.orEmpty().trim().replace(Regex("\\s+"), " ")
        val selectedUrl = old.fields["url"]?.let { item ->
            runCatching { URI(item.pageUrl).resolve(item.value).toASCIIString() }.getOrNull()
        }
        val chosen = rows?.filterIsInstance<JsonObject>()?.filter {
            normalized(it["title"]?.studioText()) == normalized(old.fields["title"]?.value) &&
                (selectedUrl == null || it["url"]?.studioText() == selectedUrl)
        }?.singleOrNull()
        val page = when (old.task) {
            StudioAssistTask.SEARCH -> chosen?.get("url")?.studioText().orEmpty()
            StudioAssistTask.ENTRIES -> old.list?.let { item ->
                runCatching { URI(item.pageUrl).resolve(item.value).toASCIIString() }.getOrNull()
            } ?: old.page
            else -> old.page
        }
        StudioAssistState(
            saved = old.saved + requireNotNull(old.task),
            task = nextTask,
            query = old.query,
            page = page,
            searchExample = if (old.task == StudioAssistTask.SEARCH) old.page else old.searchExample,
        )
    }

    fun test(source: StudioSource, allLabel: String) {
        stop()
        val ticket = generation
        val input = state
        state = input.copy(running = true, result = null, proposal = null, error = null)
        job = screenModelScope.launch {
            try {
                val task = requireNotNull(input.task)
                val example = input.page
                StudioAssist.address(example)
                val proposed: StudioSource
                val graph: StudioGraph
                if (task == StudioAssistTask.FILTER) {
                    val option = requireNotNull(input.option)
                    val target = URI(option.pageUrl).resolve(option.value).toASCIIString()
                    proposed = StudioAssist.filter(source, input.group, input.label, example, target, allLabel)
                    graph = proposed.flows.getValue(StudioFlow.SEARCH)
                } else {
                    val fields = input.fields.mapValues { (_, selected) ->
                        StudioAssist.Field(selected.selector, selected.attribute)
                    }.toMutableMap()
                    val selector = if (task ==
                        StudioAssistTask.DETAILS
                    ) {
                        "body"
                    } else {
                        requireNotNull(input.list).similarSelector
                    }
                    if (task == StudioAssistTask.ENTRIES) {
                        val tag = "a[href]"
                        fields.putIfAbsent("title", StudioAssist.Field(tag))
                        fields.putIfAbsent("url", StudioAssist.Field(tag, "href"))
                    }
                    if (task == StudioAssistTask.CONTENT) {
                        val item = requireNotNull(input.list)
                        require(item.attribute in setOf("src", "data-src") && item.value.isNotBlank())
                        fields["url"] = StudioAssist.Field(
                            if (source.medium == StudioMedium.MANGA) "img" else "video[src], source[src]",
                            item.attribute,
                        )
                    }
                    var request = if (task ==
                        StudioAssistTask.SEARCH
                    ) {
                        StudioAssist.preserveFilters(StudioAssist.searchTemplate(example, input.query), source)
                    } else if (task ==
                        StudioAssistTask.HOME
                    ) {
                        example
                    } else {
                        "{{url}}"
                    }
                    if (input.next != null) {
                        val next = URI(input.next.pageUrl).resolve(input.next.value).toASCIIString()
                        request = StudioAssist.pagination(request, example, next)
                    }
                    graph =
                        StudioAssist.graph(
                            task.flow,
                            source.medium,
                            request,
                            selector,
                            fields,
                            input.next?.selector.orEmpty(),
                        )
                    proposed = if (task == StudioAssistTask.HOME) {
                        require(input.label.isNotBlank())
                        val id = "section-${UUID.randomUUID().toString().take(8)}"
                        val home = source.home ?: StudioHome(source.name)
                        require(home.sections.size < 16)
                        source.copy(
                            home = home.copy(sections = home.sections + StudioHomeSection(id, input.label)),
                            homeFlows = source.homeFlows + (id to graph),
                        )
                    } else {
                        source.copy(flows = source.flows + (task.flow to graph))
                    }
                }
                require(StudioValidation.graph(graph).isEmpty())
                val variables = buildJsonObject {
                    put("url", example)
                    put("page", 1)
                    put("query", input.query)
                    proposed.filters.forEach { field ->
                        val label = if (task == StudioAssistTask.FILTER &&
                            field.title == input.group
                        ) {
                            input.label
                        } else {
                            field.initial
                        }
                        put("filter.${field.id}", StudioAssist.filterValue(field, label))
                    }
                }
                val result = repository.execute(proposed, task.flow, variables, overrideGraph = graph)
                StudioAssist.verify(result.value, task.flow, input.fields["title"]?.value)
                if (ticket ==
                    generation
                ) {
                    state =
                        state.copy(
                            result = result.value,
                            proposal = proposed,
                            baseline = StudioDocuments.fingerprint(source),
                        )
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                if (ticket == generation) state = state.copy(error = StudioAssistError.EXAMPLE)
            } finally {
                if (ticket == generation) state = state.copy(running = false)
            }
        }
    }

    override fun onDispose() = stop()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StudioAssistant(
    source: StudioSource,
    model: StudioAssistantModel,
    change: (StudioSource) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = model.state
    val listState = rememberLazyListState()
    val motion = appMotionEnabled()
    LaunchedEffect(state.task) { listState.scrollToItem(0) }
    LaunchedEffect(state.result) {
        if (state.result != null) {
            withFrameNanos { }
            val index = (listState.layoutInfo.totalItemsCount - 2).coerceAtLeast(0)
            if (motion) listState.animateScrollToItem(index) else listState.scrollToItem(index)
        }
    }
    val task = state.task
    val addressReady = runCatching {
        StudioAssist.address(source.baseUrl)
        source.name.isNotBlank()
    }.getOrDefault(false)
    val allLabel = stringResource(R.string.studio_assist_all)
    val close = {
        model.stop()
        onDismiss()
    }
    val back = { if (task == null) close() else model.choose(null) }
    Dialog(onDismissRequest = back, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(topBar = {
            TopAppBar(title = { Text(stringResource(R.string.studio_assist)) }, navigationIcon = {
                IconButton(back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.studio_close)) }
            }, actions = {
                TextButton(close) { Text(stringResource(R.string.studio_assist_editor)) }
            })
        }, bottomBar = {
            if (task != null) {
                Surface(tonalElevation = 3.dp) {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(16.dp)) {
                        if (state.running) LinearProgressIndicator(Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton({
                                if (state.running) model.stop() else model.test(source, allLabel)
                            }, Modifier.weight(1f)) {
                                Text(
                                    stringResource(
                                        if (state.running) R.string.studio_stop else R.string.studio_assist_preview,
                                    ),
                                )
                            }
                            Button(
                                {
                                    state.proposal?.let(change)
                                    model.saved()
                                },
                                Modifier.weight(1f),
                                enabled =
                                state.proposal != null &&
                                    !state.running &&
                                    state.baseline == StudioDocuments.fingerprint(source),
                            ) {
                                Text(stringResource(R.string.studio_assist_approve))
                            }
                        }
                    }
                }
            }
        }) { padding ->
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                state = listState,
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (task == null) {
                    item {
                        Icon(Icons.Outlined.AutoFixHigh, null, tint = MaterialTheme.colorScheme.primary)
                        Text(
                            stringResource(R.string.studio_assist_intro_title),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            stringResource(R.string.studio_assist_intro),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    item {
                        StudioField(stringResource(R.string.studio_name), source.name, {
                            change(source.copy(name = it))
                        })
                        StudioField(stringResource(R.string.studio_address), source.baseUrl, {
                            change(source.copy(baseUrl = it))
                        })
                    }
                    StudioAssistTask.entries.forEachIndexed { index, step ->
                        item {
                            Card(
                                Modifier.fillMaxWidth().clickable(enabled = addressReady) {
                                    model.choose(step)
                                },
                                shape = RoundedCornerShape(22.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                ),
                            ) {
                                Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                    Icon(
                                        if (step in
                                            state.saved
                                        ) {
                                            Icons.Outlined.CheckCircle
                                        } else {
                                            studioNodeIcon(
                                                if (index <
                                                    4
                                                ) {
                                                    StudioKind.SELECT
                                                } else {
                                                    StudioKind.INPUT
                                                },
                                            )
                                        },
                                        null,
                                        tint = studioNodeColor(
                                            if (index <
                                                4
                                            ) {
                                                StudioKind.SELECT
                                            } else {
                                                StudioKind.INPUT
                                            },
                                        ),
                                    )
                                    Column {
                                        Text(
                                            "${index + 1}. ${assistTaskName(step)}",
                                            style = MaterialTheme.typography.titleMedium,
                                        )
                                        Text(
                                            stringResource(
                                                if (index <
                                                    4
                                                ) {
                                                    R.string.studio_assist_required_step
                                                } else {
                                                    R.string.studio_assist_optional_step
                                                },
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Text(
                            stringResource(R.string.studio_assist_draft),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    item {
                        Text(assistTaskName(task), style = MaterialTheme.typography.headlineSmall)
                        Text(
                            stringResource(
                                when (task) {
                                    StudioAssistTask.SEARCH -> R.string.studio_assist_search_hint
                                    StudioAssistTask.DETAILS -> R.string.studio_assist_details_hint
                                    StudioAssistTask.ENTRIES -> R.string.studio_assist_entries_hint
                                    StudioAssistTask.CONTENT -> if (source.medium ==
                                        StudioMedium.MANGA
                                    ) {
                                        R.string.studio_assist_pages_hint
                                    } else {
                                        R.string.studio_assist_video_hint
                                    }
                                    StudioAssistTask.HOME -> R.string.studio_assist_home_hint
                                    StudioAssistTask.FILTER -> R.string.studio_assist_filter_hint
                                },
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (task in setOf(StudioAssistTask.SEARCH, StudioAssistTask.FILTER)) {
                        item {
                            StudioField(stringResource(R.string.studio_test_query), state.query, { value ->
                                model.edit { it.copy(query = value) }
                            })
                        }
                    }
                    if (task == StudioAssistTask.HOME) {
                        item {
                            StudioField(stringResource(R.string.studio_assist_home_title), state.label, { value ->
                                model.edit { it.copy(label = value) }
                            })
                        }
                    }
                    if (task == StudioAssistTask.FILTER) {
                        item {
                            StudioField(stringResource(R.string.studio_filter_label), state.group, { value ->
                                model.edit { it.copy(group = value) }
                            })
                            StudioField(stringResource(R.string.studio_assist_filter_option), state.label, { value ->
                                model.edit { it.copy(label = value) }
                            })
                            StudioField(stringResource(R.string.studio_assist_search_example), state.page, { value ->
                                model.edit { it.copy(page = value) }
                            })
                        }
                        item {
                            AssistPickButton(stringResource(R.string.studio_assist_filter_pick), state.option) {
                                model.edit { it.copy(picking = "option") }
                            }
                        }
                    } else {
                        if (task != StudioAssistTask.DETAILS) {
                            item {
                                AssistPickButton(
                                    stringResource(
                                        when (task) {
                                            StudioAssistTask.ENTRIES -> R.string.studio_assist_entry_pick
                                            StudioAssistTask.CONTENT -> if (source.medium ==
                                                StudioMedium.MANGA
                                            ) {
                                                R.string.studio_assist_page_pick
                                            } else {
                                                R.string.studio_assist_media_pick
                                            }
                                            else -> R.string.studio_assist_card_pick
                                        },
                                    ),
                                    state.list,
                                ) { model.edit { it.copy(picking = "list") } }
                            }
                        }
                        if (task in setOf(StudioAssistTask.SEARCH, StudioAssistTask.DETAILS, StudioAssistTask.HOME)) {
                            val keys = if (task ==
                                StudioAssistTask.DETAILS
                            ) {
                                listOf("title", "cover", "description")
                            } else {
                                listOf("title", "url", "cover")
                            }
                            keys.forEach { key ->
                                item {
                                    AssistPickButton(
                                        studioTerm(key),
                                        state.fields[key],
                                        enabled =
                                        task == StudioAssistTask.DETAILS || state.list != null,
                                    ) {
                                        model.edit { it.copy(picking = key) }
                                    }
                                }
                            }
                        }
                        if (task in setOf(StudioAssistTask.SEARCH, StudioAssistTask.HOME)) {
                            item {
                                AssistPickButton(
                                    stringResource(R.string.studio_assist_next_pick),
                                    state.next,
                                    enabled =
                                    state.list != null,
                                ) { model.edit { it.copy(picking = "next") } }
                                if (state.next !=
                                    null
                                ) {
                                    TextButton({
                                        model.edit { it.copy(next = null) }
                                    }) { Text(stringResource(R.string.studio_remove)) }
                                }
                            }
                        }
                        if (state.page.isNotBlank()) {
                            item {
                                Text(state.page, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                            }
                        }
                    }
                    if (state.error != null) {
                        item {
                            Text(
                                stringResource(
                                    if (state.error ==
                                        StudioAssistError.OTHER_PAGE
                                    ) {
                                        R.string.studio_assist_same_page
                                    } else {
                                        R.string.studio_assist_failed
                                    },
                                ),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    state.result?.let { value ->
                        item {
                            Text(
                                stringResource(R.string.studio_assist_review),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            StudioResultPreview(value)
                        }
                    }
                    item {
                        Text(
                            stringResource(R.string.studio_assist_limits),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
    state.picking?.let { slot ->
        val type = when {
            slot == "option" -> "filter"
            slot != "list" -> slot
            task == StudioAssistTask.ENTRIES -> "entry"
            task == StudioAssistTask.CONTENT -> if (source.medium == StudioMedium.MANGA) "cover" else "media"
            else -> "card"
        }
        val boundary = if (slot in setOf("title", "url", "cover", "description") &&
            task != StudioAssistTask.DETAILS
        ) {
            state.list?.similarSelector
        } else {
            null
        }
        StudioPagePicker(
            source,
            boundary,
            state.page.ifBlank { source.baseUrl },
            type,
            instruction = stringResource(R.string.studio_assist_browser_hint),
            startPicking = slot !in setOf("list", "option") &&
                (state.list != null || (task == StudioAssistTask.DETAILS && state.fields.isNotEmpty())),
            onDismiss = { model.edit { it.copy(picking = null) } },
            onPick = { model.picked(slot, it) },
        )
    }
}

@Composable private fun assistTaskName(task: StudioAssistTask): String = when (task) {
    StudioAssistTask.HOME -> stringResource(R.string.studio_home)
    StudioAssistTask.FILTER -> stringResource(R.string.studio_filters)
    else -> studioFlowName(task.flow)
}

@Composable private fun AssistPickButton(
    label: String,
    picked: StudioPickedElement?,
    enabled: Boolean = true,
    click: () -> Unit,
) {
    FilledTonalButton(click, Modifier.fillMaxWidth(), enabled = enabled, shape = RoundedCornerShape(18.dp)) {
        Icon(if (picked == null) Icons.Outlined.TouchApp else Icons.Outlined.CheckCircle, null)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            if (picked !=
                null
            ) {
                Text(
                    picked.text.ifBlank {
                        picked.value
                    }.ifBlank {
                        picked.similarSelector
                    }.take(100),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                )
            }
        }
    }
}
