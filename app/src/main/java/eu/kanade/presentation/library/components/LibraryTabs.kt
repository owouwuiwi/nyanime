package eu.kanade.presentation.library.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import eu.kanade.presentation.category.visualName
import eu.kanade.presentation.theme.LocalNyanimeStyle
import eu.kanade.tachiyomi.R
import tachiyomi.domain.category.model.Category
import tachiyomi.i18n.MR

@Composable
internal fun LibraryTabs(
    categories: List<Category>,
    pagerState: PagerState,
    getNumberOfItemsForCategory: (Category) -> Int?,
    hasActiveFilters: Boolean,
    onSearch: () -> Unit,
    onFilter: () -> Unit,
    onUpdates: () -> Unit,
    onRefreshCategory: () -> Unit,
    onRefreshLibrary: () -> Unit,
    onOpenRandomEntry: () -> Unit,
    onTabItemClick: (Int) -> Unit,
) {
    val currentPageIndex = pagerState.currentPage.coerceAtMost(categories.lastIndex)
    if (!LocalNyanimeStyle.current) {
        LegacyLibraryTabs(categories, currentPageIndex, getNumberOfItemsForCategory, onTabItemClick)
        return
    }

    var expanded by remember { mutableStateOf(false) }
    var actionsExpanded by remember { mutableStateOf(false) }
    val selected = categories.getOrNull(currentPageIndex) ?: return
    val count = getNumberOfItemsForCategory(selected)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .zIndex(2f),
    ) {
        val shape = RoundedCornerShape(15.dp)
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(shape),
            shape = shape,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .72f)),
            tonalElevation = 1.dp,
        ) {
            Row(
                modifier = Modifier.padding(start = 4.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .clickable(enabled = categories.size > 1) { expanded = true }
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CollectionsBookmark,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = selected.visualName,
                        modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (count != null) {
                        Text(
                            text = count.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (categories.size > 1) {
                        Icon(
                            imageVector = Icons.Outlined.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.padding(start = 5.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = onSearch) {
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = androidx.compose.ui.res.stringResource(R.string.library_search_action),
                    )
                }
                IconButton(onClick = onFilter) {
                    Icon(
                        imageVector = Icons.Outlined.FilterList,
                        contentDescription = tachiyomi.presentation.core.i18n.stringResource(MR.strings.action_filter),
                        tint = if (hasActiveFilters) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Box {
                    IconButton(onClick = { actionsExpanded = true }) {
                        Icon(
                            imageVector = Icons.Outlined.MoreVert,
                            contentDescription = androidx.compose.ui.res.stringResource(R.string.library_more_actions),
                        )
                    }
                    DropdownMenu(
                        expanded = actionsExpanded,
                        onDismissRequest = { actionsExpanded = false },
                        shape = RoundedCornerShape(18.dp),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        LibraryTabMenuItem(androidx.compose.ui.res.stringResource(R.string.library_updates_action)) {
                            actionsExpanded = false
                            onUpdates()
                        }
                        LibraryTabMenuItem(
                            tachiyomi.presentation.core.i18n.stringResource(MR.strings.action_update_category),
                        ) {
                            actionsExpanded = false
                            onRefreshCategory()
                        }
                        LibraryTabMenuItem(
                            tachiyomi.presentation.core.i18n.stringResource(MR.strings.action_update_library),
                        ) {
                            actionsExpanded = false
                            onRefreshLibrary()
                        }
                        LibraryTabMenuItem(
                            tachiyomi.presentation.core.i18n.stringResource(MR.strings.action_open_random_manga),
                        ) {
                            actionsExpanded = false
                            onOpenRandomEntry()
                        }
                    }
                }
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 280.dp, max = 420.dp),
            shape = RoundedCornerShape(18.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            categories.forEachIndexed { index, category ->
                val categoryCount = getNumberOfItemsForCategory(category)
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = category.visualName,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = if (index == currentPageIndex) FontWeight.Bold else FontWeight.Normal,
                            )
                            if (categoryCount != null) {
                                Text(
                                    text = categoryCount.toString(),
                                    modifier = Modifier.padding(start = 12.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    leadingIcon = if (index == currentPageIndex) {
                        { Icon(Icons.Outlined.Check, contentDescription = null) }
                    } else {
                        null
                    },
                    onClick = {
                        expanded = false
                        onTabItemClick(index)
                    },
                )
            }
        }
    }
}

@Composable
private fun LibraryTabMenuItem(text: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text) }, onClick = onClick)
}

@Composable
private fun LegacyLibraryTabs(
    categories: List<Category>,
    currentPageIndex: Int,
    getNumberOfItemsForCategory: (Category) -> Int?,
    onTabItemClick: (Int) -> Unit,
) {
    androidx.compose.foundation.layout.Column(modifier = Modifier.zIndex(1f)) {
        androidx.compose.material3.PrimaryScrollableTabRow(
            selectedTabIndex = currentPageIndex,
            edgePadding = 0.dp,
            divider = {},
        ) {
            categories.forEachIndexed { index, category ->
                androidx.compose.material3.Tab(
                    selected = currentPageIndex == index,
                    onClick = { onTabItemClick(index) },
                    text = {
                        tachiyomi.presentation.core.components.material.TabText(
                            text = category.visualName,
                            badgeCount = getNumberOfItemsForCategory(category),
                        )
                    },
                    unselectedContentColor = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        androidx.compose.material3.HorizontalDivider()
    }
}
