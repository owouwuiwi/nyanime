package eu.kanade.tachiyomi.ui.updates.manga

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.TabContent
import eu.kanade.presentation.updates.UpdatesDeleteConfirmationDialog
import eu.kanade.presentation.updates.manga.MangaUpdateScreen
import eu.kanade.tachiyomi.ui.entries.manga.MangaScreen
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.updates.dismissLibraryUpdates
import eu.kanade.tachiyomi.ui.updates.inboxKey
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import mihon.feature.upcoming.manga.UpcomingMangaScreen
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
fun Screen.mangaUpdatesTab(
    context: Context,
    fromMore: Boolean,
): TabContent {
    val navigator = LocalNavigator.currentOrThrow
    val screenModel = rememberScreenModel { MangaUpdatesScreenModel() }
    val state by screenModel.state.collectAsState()
    var pendingOnly by rememberSaveable { mutableStateOf(true) }
    val dismissedPreference = remember { Injekt.get<UiPreferences>().dismissedLibraryUpdates() }
    val dismissed by dismissedPreference.changes().collectAsState(initial = dismissedPreference.get())
    val pendingChapters = state.items.filter { !it.update.read && it.update.inboxKey() !in dismissed }
    val pendingItems = pendingChapters.distinctBy { it.update.mangaId }
    val displayedState = if (pendingOnly) state.copy(items = pendingItems.toPersistentList()) else state
    val dismiss: (MangaUpdatesItem) -> Set<String> = {
        val keys = pendingChapters.asSequence()
            .filter { chapter -> chapter.update.mangaId == it.update.mangaId }
            .map { chapter -> chapter.update.inboxKey() }
            .toSet()
        dismissLibraryUpdates(dismissedPreference, keys)
        keys
    }

    val scope = rememberCoroutineScope()
    val navigateUp: (() -> Unit)? = if (fromMore) {
        {
            if (navigator.lastItem == HomeScreen) {
                scope.launch { HomeScreen.openTab(HomeScreen.Tab.AnimeLib()) }
            } else {
                navigator.pop()
            }
        }
    } else {
        null
    }

    return TabContent(
        legacyManga = true,
        titleRes = AYMR.strings.label_updates,
        searchEnabled = false,
        content = { contentPadding, _ ->
            MangaUpdateScreen(
                state = displayedState,
                pendingOnly = pendingOnly,
                pendingCount = pendingItems.size,
                allCount = state.items.size,
                onPendingChange = {
                    screenModel.toggleAllSelection(false)
                    pendingOnly = it
                },
                onIgnore = { item ->
                    val ignoredKeys = dismiss(item)
                    scope.launch {
                        if (screenModel.snackbarHostState.showSnackbar("Novità ignorata", "Annulla") ==
                            SnackbarResult.ActionPerformed
                        ) {
                            dismissedPreference.set(dismissedPreference.get() - ignoredKeys)
                        }
                    }
                },
                snackbarHostState = screenModel.snackbarHostState,
                lastUpdated = screenModel.lastUpdated,
                onClickCover = { item ->
                    if (pendingOnly) dismiss(item)
                    navigator.push(MangaScreen(item.update.mangaId))
                },
                onSelectAll = screenModel::toggleAllSelection,
                onInvertSelection = screenModel::invertSelection,
                onUpdateLibrary = screenModel::updateLibrary,
                onDownloadChapter = screenModel::downloadChapters,
                onMultiBookmarkClicked = screenModel::bookmarkUpdates,
                onMultiMarkAsReadClicked = screenModel::markUpdatesRead,
                onMultiDeleteClicked = screenModel::showConfirmDeleteChapters,
                onUpdateSelected = { item, selected, userSelected, fromLongPress ->
                    if (pendingOnly) pendingOnly = false
                    screenModel.toggleSelection(item, selected, userSelected, fromLongPress)
                },
                onOpenChapter = {
                    if (pendingOnly) dismiss(it)
                    val intent =
                        ReaderActivity.newIntent(context, it.update.mangaId, it.update.chapterId)
                    context.startActivity(intent)
                },
            )

            val onDismissDialog = { screenModel.setDialog(null) }
            when (val dialog = state.dialog) {
                is MangaUpdatesScreenModel.Dialog.DeleteConfirmation -> {
                    UpdatesDeleteConfirmationDialog(
                        onDismissRequest = onDismissDialog,
                        onConfirm = { screenModel.deleteChapters(dialog.toDelete) },
                        isManga = true,
                    )
                }
                null -> {}
            }

            LaunchedEffect(Unit) {
                screenModel.events.collectLatest { event ->
                    when (event) {
                        MangaUpdatesScreenModel.Event.InternalError -> screenModel.snackbarHostState.showSnackbar(
                            context.stringResource(
                                MR.strings.internal_error,
                            ),
                        )
                        is MangaUpdatesScreenModel.Event.LibraryUpdateTriggered -> {
                            val msg = if (event.started) {
                                MR.strings.updating_library
                            } else {
                                MR.strings.update_already_running
                            }
                            screenModel.snackbarHostState.showSnackbar(context.stringResource(msg))
                        }
                    }
                }
            }

            LaunchedEffect(state.selectionMode) {
                HomeScreen.showBottomNav(!state.selectionMode)
            }

            LaunchedEffect(state.isLoading) {
                if (!state.isLoading) {
                    (context as? MainActivity)?.ready = true
                }
            }
            DisposableEffect(Unit) {
                screenModel.resetNewUpdatesCount()

                onDispose {
                    screenModel.resetNewUpdatesCount()
                }
            }
        },
        actions =
        if (screenModel.state.collectAsState().value.selected.isNotEmpty()) {
            persistentListOf(
                AppBar.Action(
                    title = stringResource(MR.strings.action_select_all),
                    icon = Icons.Outlined.SelectAll,
                    onClick = { screenModel.toggleAllSelection(true) },
                ),
                AppBar.Action(
                    title = stringResource(MR.strings.action_select_inverse),
                    icon = Icons.Outlined.FlipToBack,
                    onClick = { screenModel.invertSelection() },
                ),
            )
        } else {
            persistentListOf(
                AppBar.Action(
                    title = stringResource(MR.strings.action_view_upcoming),
                    icon = Icons.Outlined.CalendarMonth,
                    onClick = { navigator.push(UpcomingMangaScreen()) },
                ),
                AppBar.Action(
                    title = stringResource(MR.strings.action_update_library),
                    icon = Icons.Outlined.Refresh,
                    onClick = { screenModel.updateLibrary() },
                ),
            )
        },
        navigateUp = navigateUp,
    )
}
