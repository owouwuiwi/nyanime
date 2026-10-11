package eu.kanade.presentation.more

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.GetApp
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PeopleOutline
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.VideoSettings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.vectorResource
import eu.kanade.domain.ui.model.NavStyle
import eu.kanade.presentation.more.settings.widget.SwitchPreferenceWidget
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.core.common.Constants
import eu.kanade.tachiyomi.ui.more.DownloadQueueState
import eu.kanade.tachiyomi.ui.more.ReadyAppUpdateSurface
import eu.kanade.tachiyomi.ui.watch.rememberRoomText
import tachiyomi.i18n.MR
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun MoreScreen(
    downloadQueueStateProvider: () -> DownloadQueueState,
    downloadedOnly: Boolean,
    onDownloadedOnlyChange: (Boolean) -> Unit,
    incognitoMode: Boolean,
    onIncognitoModeChange: (Boolean) -> Unit,
    navStyle: NavStyle,
    onClickAlt: (eu.kanade.presentation.util.Tab) -> Unit,
    onClickDownloadQueue: () -> Unit,
    onClickCategories: () -> Unit,
    onClickStats: () -> Unit,
    onClickStorage: () -> Unit,
    onClickDataAndStorage: () -> Unit,
    onClickPlayerSettings: () -> Unit,
    onClickSettings: () -> Unit,
    onClickAbout: () -> Unit,
) {
    val text = rememberRoomText()
    val uriHandler = LocalUriHandler.current
    val context = androidx.compose.ui.platform.LocalContext.current

    Scaffold { contentPadding ->
        ScrollbarLazyColumn(
            modifier = Modifier.padding(contentPadding),
            contentPadding = PaddingValues(bottom = eu.kanade.tachiyomi.ui.home.LocalFloatingNavigationInset.current),
        ) {
            item {
                LogoHeader()
            }
            item(key = "ready-app-update") {
                ReadyAppUpdateSurface(allowDismiss = false)
            }
            item(key = "nyanime-cloud") {
                TextPreferenceWidget(
                    title = context.getString(R.string.cloud_title),
                    subtitle = context.getString(R.string.cloud_entry_summary),
                    icon = Icons.Outlined.CloudSync,
                    onPreferenceClick = { eu.kanade.tachiyomi.ui.cloud.CloudActivity.open(context) },
                )
            }
            item {
                TextPreferenceWidget(
                    title = text(R.string.room_more_title),
                    subtitle = text(R.string.room_more_hint),
                    icon = Icons.Outlined.PeopleOutline,
                    onPreferenceClick = {
                        context.startActivity(
                            android.content.Intent(
                                context,
                                eu.kanade.tachiyomi.ui.watch.WatchTogetherActivity::class.java,
                            ),
                        )
                    },
                )
            }

            item(key = "addon-actions") { eu.kanade.tachiyomi.ui.addon.AddonMoreActions() }

            item { HorizontalDivider() }

            item {
                SwitchPreferenceWidget(
                    title = stringResource(MR.strings.label_downloaded_only),
                    subtitle = stringResource(MR.strings.downloaded_only_summary),
                    icon = Icons.Outlined.CloudOff,
                    checked = downloadedOnly,
                    onCheckedChanged = onDownloadedOnlyChange,
                )
            }
            item {
                SwitchPreferenceWidget(
                    title = stringResource(MR.strings.pref_incognito_mode),
                    subtitle = stringResource(AYMR.strings.pref_incognito_mode_summary),
                    icon = ImageVector.vectorResource(R.drawable.ic_glasses_24dp),
                    checked = incognitoMode,
                    onCheckedChanged = onIncognitoModeChange,
                )
            }

            navStyle.overflowTabs.forEach { tab ->
                item {
                    TextPreferenceWidget(
                        title = tab.options.title,
                        icon = navStyle.overflowIcon(tab),
                        onPreferenceClick = { onClickAlt(tab) },
                    )
                }
            }

            item {
                val downloadQueueState = downloadQueueStateProvider()
                TextPreferenceWidget(
                    title = stringResource(MR.strings.label_download_queue),
                    subtitle = when (downloadQueueState) {
                        DownloadQueueState.Stopped -> null
                        is DownloadQueueState.Paused -> {
                            val pending = downloadQueueState.pending
                            if (pending == 0) {
                                stringResource(MR.strings.paused)
                            } else {
                                "${stringResource(MR.strings.paused)} • ${
                                    pluralStringResource(
                                        MR.plurals.download_queue_summary,
                                        count = pending,
                                        pending,
                                    )
                                }"
                            }
                        }

                        is DownloadQueueState.Downloading -> {
                            val pending = downloadQueueState.pending
                            pluralStringResource(
                                MR.plurals.download_queue_summary,
                                count = pending,
                                pending,
                            )
                        }
                    },
                    icon = Icons.Outlined.GetApp,
                    onPreferenceClick = onClickDownloadQueue,
                )
            }
            item {
                TextPreferenceWidget(
                    title = stringResource(AYMR.strings.general_categories),
                    icon = Icons.AutoMirrored.Outlined.Label,
                    onPreferenceClick = onClickCategories,
                )
            }
            item {
                TextPreferenceWidget(
                    title = stringResource(MR.strings.label_stats),
                    icon = Icons.Outlined.QueryStats,
                    onPreferenceClick = onClickStats,
                )
            }
            item {
                TextPreferenceWidget(
                    title = stringResource(MR.strings.label_data_storage),
                    icon = Icons.Outlined.Storage,
                    onPreferenceClick = onClickDataAndStorage,
                )
            }

            item { HorizontalDivider() }

            item {
                TextPreferenceWidget(
                    title = stringResource(MR.strings.label_settings),
                    icon = Icons.Outlined.Settings,
                    onPreferenceClick = onClickSettings,
                )
            }
            item {
                TextPreferenceWidget(
                    title = stringResource(AYMR.strings.label_player_settings),
                    icon = Icons.Outlined.VideoSettings,
                    onPreferenceClick = onClickPlayerSettings,
                )
            }
            item {
                TextPreferenceWidget(
                    title = stringResource(MR.strings.pref_category_about),
                    icon = Icons.Outlined.Info,
                    onPreferenceClick = onClickAbout,
                )
            }
            item {
                TextPreferenceWidget(
                    title = stringResource(MR.strings.label_help),
                    icon = Icons.AutoMirrored.Outlined.HelpOutline,
                    onPreferenceClick = { uriHandler.openUri(Constants.URL_HELP) },
                )
            }
        }
    }
}
