package eu.kanade.presentation.library.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.presentation.theme.LocalNyanimeStyle
import eu.kanade.tachiyomi.R
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.Pill
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.theme.active

@Composable
fun LibraryToolbar(
    hasActiveFilters: Boolean,
    selectedCount: Int,
    title: LibraryToolbarTitle,
    onClickUnselectAll: () -> Unit,
    onClickSelectAll: () -> Unit,
    onClickInvertSelection: () -> Unit,
    onClickFilter: () -> Unit,
    onClickRefresh: () -> Unit,
    onClickGlobalUpdate: () -> Unit,
    onClickUpdates: () -> Unit,
    onClickOpenRandomEntry: () -> Unit,
    searchQuery: String?,
    onSearchQueryChange: (String?) -> Unit,
    scrollBehavior: TopAppBarScrollBehavior?,
    navigateUp: (() -> Unit)? = null,
) = when {
    selectedCount > 0 -> LibrarySelectionToolbar(
        selectedCount = selectedCount,
        onClickUnselectAll = onClickUnselectAll,
        onClickSelectAll = onClickSelectAll,
        onClickInvertSelection = onClickInvertSelection,
    )
    LocalNyanimeStyle.current && searchQuery == null -> Unit
    else -> LibraryRegularToolbar(
        title = title,
        hasFilters = hasActiveFilters,
        searchQuery = searchQuery,
        onSearchQueryChange = onSearchQueryChange,
        onClickFilter = onClickFilter,
        onClickRefresh = onClickRefresh,
        onClickGlobalUpdate = onClickGlobalUpdate,
        onClickUpdates = onClickUpdates,
        onClickOpenRandomEntry = onClickOpenRandomEntry,
        scrollBehavior = scrollBehavior,
        navigateUp = navigateUp,
    )
}

@Composable
private fun LibraryRegularToolbar(
    title: LibraryToolbarTitle,
    hasFilters: Boolean,
    searchQuery: String?,
    onSearchQueryChange: (String?) -> Unit,
    onClickFilter: () -> Unit,
    onClickRefresh: () -> Unit,
    onClickGlobalUpdate: () -> Unit,
    onClickUpdates: () -> Unit,
    onClickOpenRandomEntry: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior?,
    navigateUp: (() -> Unit)?,
) {
    if (LocalNyanimeStyle.current) {
        SearchToolbar(
            titleContent = { Text(androidx.compose.ui.res.stringResource(R.string.library_search_action)) },
            searchQuery = searchQuery,
            onChangeSearchQuery = onSearchQueryChange,
            actions = {
                AppBarActions(
                    persistentListOf(
                        AppBar.Action(
                            title = stringResource(MR.strings.action_filter),
                            icon = Icons.Outlined.FilterList,
                            iconTint = if (hasFilters) {
                                MaterialTheme.colorScheme.active
                            } else {
                                LocalContentColor.current
                            },
                            onClick = onClickFilter,
                        ),
                    ),
                )
            },
            scrollBehavior = scrollBehavior,
            navigateUp = navigateUp,
        )
        return
    }

    val pillAlpha = if (isSystemInDarkTheme()) 0.12f else 0.08f
    SearchToolbar(
        titleContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title.text,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, false),
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.em,
                )
                if (title.numberOfEntries != null) {
                    Pill(
                        text = "${title.numberOfEntries}",
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = pillAlpha),
                        fontSize = 14.sp,
                    )
                }
            }
        },
        searchQuery = searchQuery,
        onChangeSearchQuery = onSearchQueryChange,
        actions = {
            val filterTint = if (hasFilters) MaterialTheme.colorScheme.active else LocalContentColor.current
            AppBarActions(
                persistentListOf(
                    AppBar.Action(
                        title = androidx.compose.ui.res.stringResource(R.string.library_updates_action),
                        icon = Icons.Outlined.Notifications,
                        onClick = onClickUpdates,
                    ),
                    AppBar.Action(
                        title = stringResource(MR.strings.action_filter),
                        icon = Icons.Outlined.FilterList,
                        iconTint = filterTint,
                        onClick = onClickFilter,
                    ),
                    AppBar.OverflowAction(
                        title = stringResource(MR.strings.action_update_library),
                        onClick = onClickGlobalUpdate,
                    ),
                    AppBar.OverflowAction(
                        title = stringResource(MR.strings.action_update_category),
                        onClick = onClickRefresh,
                    ),
                    AppBar.OverflowAction(
                        title = stringResource(MR.strings.action_open_random_manga),
                        onClick = onClickOpenRandomEntry,
                    ),
                ),
            )
        },
        scrollBehavior = scrollBehavior,
        navigateUp = navigateUp,
    )
}

@Composable
private fun ModernLibraryControls(
    title: LibraryToolbarTitle,
    hasFilters: Boolean,
    navigateUp: (() -> Unit)?,
    onSearch: () -> Unit,
    onClickFilter: () -> Unit,
    onClickRefresh: () -> Unit,
    onClickGlobalUpdate: () -> Unit,
    onClickUpdates: () -> Unit,
    onClickOpenRandomEntry: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (navigateUp != null) {
            IconButton(onClick = navigateUp, modifier = Modifier.size(44.dp)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
            }
        }
        val searchShape = RoundedCornerShape(15.dp)
        Surface(
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .clickable(onClick = onSearch),
            shape = searchShape,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 1.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.library_search_action),
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                title.numberOfEntries?.let {
                    Text(
                        text = it.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .8f),
                    )
                }
            }
        }
        val filterColor = if (hasFilters) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
        Surface(
            modifier = Modifier
                .padding(start = 6.dp)
                .size(44.dp)
                .clickable(onClick = onClickFilter),
            shape = RoundedCornerShape(13.dp),
            color = filterColor,
            contentColor = if (hasFilters) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        ) {
            Icon(
                Icons.Outlined.FilterList,
                contentDescription = stringResource(MR.strings.action_filter),
                modifier = Modifier.padding(10.dp),
            )
        }
        Box {
            IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(44.dp)) {
                Icon(
                    Icons.Outlined.MoreVert,
                    contentDescription = androidx.compose.ui.res.stringResource(R.string.library_more_actions),
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                shape = RoundedCornerShape(18.dp),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                LibraryMenuItem(androidx.compose.ui.res.stringResource(R.string.library_updates_action)) {
                    menuExpanded = false
                    onClickUpdates()
                }
                LibraryMenuItem(stringResource(MR.strings.action_update_category)) {
                    menuExpanded = false
                    onClickRefresh()
                }
                LibraryMenuItem(stringResource(MR.strings.action_update_library)) {
                    menuExpanded = false
                    onClickGlobalUpdate()
                }
                LibraryMenuItem(stringResource(MR.strings.action_open_random_manga)) {
                    menuExpanded = false
                    onClickOpenRandomEntry()
                }
            }
        }
    }
}

@Composable
private fun LibraryMenuItem(text: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text) }, onClick = onClick)
}

@Composable
private fun LibrarySelectionToolbar(
    selectedCount: Int,
    onClickUnselectAll: () -> Unit,
    onClickSelectAll: () -> Unit,
    onClickInvertSelection: () -> Unit,
) {
    AppBar(
        titleContent = { Text(text = "$selectedCount") },
        actions = {
            AppBarActions(
                persistentListOf(
                    AppBar.Action(
                        title = stringResource(MR.strings.action_select_all),
                        icon = Icons.Outlined.SelectAll,
                        onClick = onClickSelectAll,
                    ),
                    AppBar.Action(
                        title = stringResource(MR.strings.action_select_inverse),
                        icon = Icons.Outlined.FlipToBack,
                        onClick = onClickInvertSelection,
                    ),
                ),
            )
        },
        isActionMode = true,
        onCancelActionMode = onClickUnselectAll,
    )
}

@Immutable
data class LibraryToolbarTitle(
    val text: String,
    val numberOfEntries: Int? = null,
)
