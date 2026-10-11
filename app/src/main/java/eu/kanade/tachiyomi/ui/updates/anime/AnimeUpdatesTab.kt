package eu.kanade.tachiyomi.ui.updates.anime

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
import eu.kanade.presentation.components.NavigatorAdaptiveSheet
import eu.kanade.presentation.components.TabContent
import eu.kanade.presentation.entries.anime.EpisodeOptionsDialogScreen
import eu.kanade.presentation.updates.UpdatesDeleteConfirmationDialog
import eu.kanade.presentation.updates.anime.AnimeUpdateScreen
import eu.kanade.tachiyomi.ui.entries.anime.AnimeScreen
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import eu.kanade.tachiyomi.ui.updates.dismissLibraryUpdates
import eu.kanade.tachiyomi.ui.updates.inboxKey
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import mihon.feature.upcoming.anime.UpcomingAnimeScreen
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.i18n.MR
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

@Composable
fun Screen.animeUpdatesTab(
    context: Context,
    fromMore: Boolean,
): TabContent {
    val navigator = LocalNavigator.currentOrThrow
    val screenModel = rememberScreenModel { AnimeUpdatesScreenModel() }
    val scope = rememberCoroutineScope()
    val state by screenModel.state.collectAsState()
    var pendingOnly by rememberSaveable { mutableStateOf(true) }
    val dismissedPreference = remember { Injekt.get<UiPreferences>().dismissedLibraryUpdates() }
    val dismissed by dismissedPreference.changes().collectAsState(initial = dismissedPreference.get())
    val pendingEpisodes = state.items.filter { !it.update.seen && it.update.inboxKey() !in dismissed }
    val pendingItems = pendingEpisodes.distinctBy { it.update.animeId }
    val displayedState = if (pendingOnly) state.copy(items = pendingItems.toPersistentList()) else state
    val dismiss: (AnimeUpdatesItem) -> Set<String> = {
        val keys = pendingEpisodes.asSequence()
            .filter { episode -> episode.update.animeId == it.update.animeId }
            .map { episode -> episode.update.inboxKey() }
            .toSet()
        dismissLibraryUpdates(dismissedPreference, keys)
        keys
    }

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

    suspend fun openEpisode(updateItem: AnimeUpdatesItem, altPlayer: Boolean = false) {
        val playerPreferences: PlayerPreferences by injectLazy()
        val update = updateItem.update
        val extPlayer = playerPreferences.alwaysUseExternalPlayer().get() != altPlayer
        MainActivity.startPlayerActivity(context, update.animeId, update.episodeId, extPlayer, update.sourceId)
    }

    return TabContent(
        titleRes = AYMR.strings.label_anime_updates,
        searchEnabled = false,
        content = { contentPadding, _ ->
            AnimeUpdateScreen(
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
                    navigator.push(AnimeScreen(item.update.animeId))
                },
                onSelectAll = screenModel::toggleAllSelection,
                onInvertSelection = screenModel::invertSelection,
                onUpdateLibrary = screenModel::updateLibrary,
                onDownloadEpisode = screenModel::downloadEpisodes,
                onMultiBookmarkClicked = screenModel::bookmarkUpdates,
                onMultiFillermarkClicked = screenModel::fillermarkUpdates,
                onMultiMarkAsSeenClicked = screenModel::markUpdatesSeen,
                onMultiDeleteClicked = screenModel::showConfirmDeleteEpisodes,
                onUpdateSelected = { item, selected, userSelected, fromLongPress ->
                    if (pendingOnly) pendingOnly = false
                    screenModel.toggleSelection(item, selected, userSelected, fromLongPress)
                },
                onOpenEpisode = { updateItem: AnimeUpdatesItem, altPlayer: Boolean ->
                    if (pendingOnly) dismiss(updateItem)
                    scope.launchIO {
                        openEpisode(updateItem, altPlayer)
                    }
                },
            )

            val onDismissDialog = { screenModel.setDialog(null) }
            when (val dialog = state.dialog) {
                is AnimeUpdatesScreenModel.Dialog.DeleteConfirmation -> {
                    UpdatesDeleteConfirmationDialog(
                        onDismissRequest = onDismissDialog,
                        onConfirm = { screenModel.deleteEpisodes(dialog.toDelete) },
                        isManga = false,
                    )
                }
                is AnimeUpdatesScreenModel.Dialog.ShowQualities -> {
                    EpisodeOptionsDialogScreen.onDismissDialog = onDismissDialog
                    NavigatorAdaptiveSheet(
                        screen = EpisodeOptionsDialogScreen(
                            useExternalDownloader = screenModel.useExternalDownloader,
                            episodeTitle = dialog.episodeTitle,
                            episodeId = dialog.episodeId,
                            animeId = dialog.animeId,
                            sourceId = dialog.sourceId,
                        ),
                        onDismissRequest = onDismissDialog,
                    )
                }
                null -> {}
            }

            LaunchedEffect(Unit) {
                screenModel.events.collectLatest { event ->
                    when (event) {
                        AnimeUpdatesScreenModel.Event.InternalError -> screenModel.snackbarHostState.showSnackbar(
                            context.stringResource(
                                MR.strings.internal_error,
                            ),
                        )
                        is AnimeUpdatesScreenModel.Event.LibraryUpdateTriggered -> {
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
                    onClick = { navigator.push(UpcomingAnimeScreen()) },
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
