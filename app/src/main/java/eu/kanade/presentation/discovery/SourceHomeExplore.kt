package eu.kanade.presentation.discovery

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.motion.ModernMotion
import eu.kanade.presentation.motion.modernMotionEnabled
import eu.kanade.tachiyomi.R
import tachiyomi.domain.discovery.SourceHomeFilter
import tachiyomi.domain.discovery.SourceHomeGroup
import androidx.compose.ui.res.stringResource as androidStringResource

@Composable
fun SourceHomeExploreBar(
    categories: List<SourceHomeGroup.Section>,
    onBrowse: () -> Unit,
    onCategory: (SourceHomeGroup.Section) -> Unit,
) {
    var genresOpen by remember { mutableStateOf(false) }
    var genreQuery by remember { mutableStateOf("") }
    if (genresOpen) {
        ModalBottomSheet(onDismissRequest = { genresOpen = false }) {
            Column(Modifier.imePadding().padding(horizontal = 20.dp)) {
                Text(androidStringResource(R.string.home_all_genres), style = MaterialTheme.typography.headlineSmall)
                OutlinedTextField(
                    value = genreQuery,
                    onValueChange = { genreQuery = it },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    placeholder = { Text(androidStringResource(R.string.home_search_genre)) },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                )
                LazyColumn(contentPadding = PaddingValues(bottom = 36.dp)) {
                    items(
                        categories.filter {
                            it.title.contains(genreQuery.trim(), ignoreCase = true)
                        },
                        key = { it.id },
                    ) { category ->
                        TextButton(onClick = {
                            genresOpen = false
                            onCategory(category)
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text(category.title, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            HomeExploreAction(onBrowse)
        }
        if (categories.size > 8) {
            item {
                HomeGenreChip(androidStringResource(R.string.home_all_genres)) { genresOpen = true }
            }
        }
        items(categories, key = { it.id }) { category ->
            HomeGenreChip(category.title) { onCategory(category) }
        }
    }
}

@Composable
fun HomeExploreAction(onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(androidStringResource(R.string.home_explore)) },
        leadingIcon = { Icon(Icons.Outlined.Tune, null) },
    )
}

@Composable
fun HomeGenreChip(title: String, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    )
}

@Composable
fun HomeSelectionChip(title: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = Modifier.semantics {
            role = Role.Tab
            this.selected = selected
        },
        label = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    )
}

@Composable
fun SourceHomeActiveFilters(
    values: Map<String, List<String>>,
    onRemove: (String) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(values.entries.toList(), key = { it.key }) { (name, selection) ->
            FilterChip(
                selected = true,
                onClick = { onRemove(name) },
                label = {
                    Text(
                        "$name: ${selection.joinToString().ifBlank { androidStringResource(R.string.home_all) }}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillParentMaxWidth(.72f),
                    )
                },
                trailingIcon = { Icon(Icons.Outlined.Close, androidStringResource(R.string.home_remove_filter, name)) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceHomeFilterSheet(
    definitions: List<SourceHomeFilter>,
    applied: Map<String, List<String>>,
    onDismiss: () -> Unit,
    onApply: (Map<String, List<String>>) -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        SourceHomeFilterContent(definitions, applied, onApply = {
            onApply(it)
            onDismiss()
        })
    }
}

@Composable
fun SourceHomeFilterContent(
    definitions: List<SourceHomeFilter>,
    applied: Map<String, List<String>>,
    onApply: (Map<String, List<String>>) -> Unit,
) {
    var draft by remember(definitions) { mutableStateOf(applied) }
    var expanded by remember { mutableStateOf(definitions.firstOrNull()?.name) }
    val motion = modernMotionEnabled()
    Column(Modifier.imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(androidStringResource(R.string.home_next_story), style = MaterialTheme.typography.titleLarge)
                Text(
                    androidStringResource(R.string.home_filter_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { draft = emptyMap() }) { Text(androidStringResource(R.string.home_reset)) }
        }
        LazyColumn(
            Modifier.weight(1f, fill = false),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(definitions, key = { it.name }) { filter ->
                val selected = draft[filter.name] ?: filter.defaults
                val open = expanded == filter.name
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column {
                        Row(
                            Modifier.fillMaxWidth().clickable { expanded = if (open) null else filter.name }
                                .heightIn(min = 64.dp).padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(filter.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    selected.joinToString().ifBlank { androidStringResource(R.string.home_all) },
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(
                                if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                if (open) {
                                    androidStringResource(
                                        R.string.home_close_filter,
                                        filter.name,
                                    )
                                } else {
                                    androidStringResource(R.string.home_choose_filter, filter.name)
                                },
                            )
                        }
                        AnimatedVisibility(
                            open,
                            enter = if (motion) {
                                expandVertically(tween(ModernMotion.RESIZE_MILLIS)) + ModernMotion.enter()
                            } else {
                                EnterTransition.None
                            },
                            exit = if (motion) {
                                shrinkVertically(tween(ModernMotion.EXIT_MILLIS)) + ModernMotion.exit()
                            } else {
                                ExitTransition.None
                            },
                        ) {
                            SourceHomeFilterOptions(filter, selected) { values ->
                                draft = if (values == filter.defaults) {
                                    draft - filter.name
                                } else {
                                    draft + (filter.name to values)
                                }
                            }
                        }
                    }
                }
            }
        }
        Button(
            onClick = {
                onApply(draft)
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).heightIn(min = 52.dp),
        ) {
            Text(androidStringResource(R.string.home_results))
        }
    }
}

@Composable
private fun SourceHomeFilterOptions(
    filter: SourceHomeFilter,
    selected: List<String>,
    onSelect: (List<String>) -> Unit,
) {
    var search by remember(filter.name) { mutableStateOf("") }
    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
        if (filter.kind == SourceHomeFilter.Kind.TEXT) {
            OutlinedTextField(
                selected.firstOrNull().orEmpty(),
                { onSelect(listOf(it.take(300))) },
                Modifier.fillMaxWidth(),
                label = { Text(filter.name) },
                singleLine = true,
            )
        } else {
            if (filter.options.size > 16) {
                OutlinedTextField(
                    search,
                    { search = it },
                    Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(androidStringResource(R.string.home_search_options)) },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                )
            }
            if (filter.kind == SourceHomeFilter.Kind.MULTIPLE) {
                TextButton(onClick = { onSelect(emptyList()) }) { Text(androidStringResource(R.string.home_unlimited)) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                filter.options.filter { it.contains(search, ignoreCase = true) }.forEach { option ->
                    FilterChip(selected = option in selected, onClick = {
                        onSelect(
                            if (filter.kind == SourceHomeFilter.Kind.SINGLE) {
                                listOf(option)
                            } else if (option in selected) {
                                selected - option
                            } else {
                                selected + option
                            },
                        )
                    }, label = { Text(option) })
                }
            }
        }
    }
}
