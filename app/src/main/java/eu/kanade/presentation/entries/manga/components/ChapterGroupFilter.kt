package eu.kanade.presentation.entries.manga.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.entries.details.TitleDetailsIcons
import eu.kanade.tachiyomi.R
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource as mokoStringResource

@Composable
internal fun ChapterGroupChip(
    groups: Map<String, Int>,
    hidden: Set<String>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val shown = remember(groups, hidden) { groups.keys.filterNot { it in hidden } }
    val filtered = shown.size != groups.size
    val label = when {
        !filtered -> stringResource(R.string.chapter_groups)
        shown.size == 1 -> stringResource(
            R.string.chapter_groups_single,
            shown.single().ifEmpty { stringResource(R.string.chapter_groups_unnamed) },
        )
        else -> stringResource(R.string.chapter_groups_selected, shown.size, groups.size)
    }
    FilterChip(
        selected = filtered,
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.widthIn(max = 320.dp),
        leadingIcon = { Icon(TitleDetailsIcons.Users, null, Modifier.size(18.dp)) },
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    )
}

@Composable
internal fun ChapterGroupFilterDialog(
    groups: Map<String, Int>,
    excludedScanlators: Set<String>,
    onDismissRequest: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    var excluded by rememberSaveable { mutableStateOf(ArrayList(excludedScanlators)) }
    var query by rememberSaveable { mutableStateOf("") }
    val hidden = remember(excluded) { excluded.toSet() }
    val selectedCount = groups.keys.count { it !in hidden }
    val unnamed = stringResource(R.string.chapter_groups_unnamed)
    val groupNames = remember(groups.keys) {
        groups.keys.sortedWith(
            compareBy<String> { it.isEmpty() }.thenBy(String.CASE_INSENSITIVE_ORDER) { it }.thenBy { it },
        )
    }
    val matching = remember(groupNames, query, unnamed) {
        groupNames.filter { it.ifEmpty { unnamed }.contains(query.trim(), ignoreCase = true) }
    }
    val listMaxHeight = (LocalConfiguration.current.screenHeightDp.dp * 0.43f).coerceAtMost(380.dp)
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.chapter_groups_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (groups.size > 5 || query.isNotEmpty()) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text(stringResource(R.string.chapter_groups_search)) },
                        leadingIcon = { Icon(Icons.Outlined.Search, null) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.chapter_groups_selected, selectedCount, groups.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { excluded = arrayListOf() }, enabled = hidden.isNotEmpty()) {
                        Text(stringResource(R.string.chapter_groups_all))
                    }
                }
                if (matching.isEmpty()) {
                    Text(stringResource(R.string.chapter_groups_no_results), style = MaterialTheme.typography.bodyMedium)
                } else {
                    LazyColumn(Modifier.heightIn(max = listMaxHeight)) {
                        items(matching, key = { it }) { group ->
                            val selected = group !in hidden
                            val name = group.ifEmpty { unnamed }
                            val count = groups.getValue(group)
                            val onlyThisGroup = stringResource(R.string.chapter_groups_only_named, name)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Row(
                                    Modifier.weight(1f)
                                        .clip(MaterialTheme.shapes.small)
                                        .toggleable(value = selected, role = Role.Checkbox) { show ->
                                            excluded = ArrayList(if (show) hidden - group else hidden + group)
                                        }
                                        .heightIn(min = 52.dp)
                                        .padding(horizontal = 4.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Checkbox(checked = selected, onCheckedChange = null)
                                    Column {
                                        Text(name, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            pluralStringResource(R.plurals.chapter_groups_chapters, count, count),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                TextButton(
                                    onClick = { excluded = ArrayList(groups.keys - group) },
                                    enabled = groups.size > 1 && !(selected && selectedCount == 1),
                                    modifier = Modifier.semantics { contentDescription = onlyThisGroup },
                                ) {
                                    Text(stringResource(R.string.chapter_groups_only))
                                }
                            }
                        }
                    }
                }
                if (selectedCount == 0 && groups.isNotEmpty()) {
                    Text(
                        stringResource(R.string.chapter_groups_choose_one),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(mokoStringResource(MR.strings.action_cancel))
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(hidden)
                    onDismissRequest()
                },
                enabled = selectedCount > 0,
            ) {
                Text(stringResource(R.string.chapter_groups_apply))
            }
        },
    )
}
