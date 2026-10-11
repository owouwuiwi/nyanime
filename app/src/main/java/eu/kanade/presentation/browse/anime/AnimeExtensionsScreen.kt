package eu.kanade.presentation.browse.anime

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.GetApp
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.extension.ExtensionPackageMetadata
import eu.kanade.domain.extension.ExtensionUpdateStatus
import eu.kanade.presentation.browse.BaseBrowseItem
import eu.kanade.presentation.browse.anime.components.AnimeExtensionIcon
import eu.kanade.presentation.browse.components.ExtensionEntry
import eu.kanade.presentation.browse.components.ExtensionManagerContent
import eu.kanade.presentation.browse.components.ExtensionManagerSkeleton
import eu.kanade.presentation.browse.manga.ExtensionHeader
import eu.kanade.presentation.browse.manga.ExtensionTrustDialog
import eu.kanade.presentation.components.WarningBanner
import eu.kanade.presentation.entries.components.DotSeparatorNoSpaceText
import eu.kanade.presentation.more.settings.screen.browse.AnimeExtensionStoresScreen
import eu.kanade.presentation.util.animateItemFastScroll
import eu.kanade.presentation.util.rememberRequestPackageInstallsPermissionState
import eu.kanade.tachiyomi.extension.InstallStep
import eu.kanade.tachiyomi.extension.anime.model.AnimeExtension
import eu.kanade.tachiyomi.ui.browse.anime.extension.AnimeExtensionUiModel
import eu.kanade.tachiyomi.ui.browse.anime.extension.AnimeExtensionsScreenModel
import eu.kanade.tachiyomi.util.system.LocaleHelper
import eu.kanade.tachiyomi.util.system.launchRequestPackageInstallsPermission
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.components.material.topSmallPaddingValues
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.icons.CustomIcons
import tachiyomi.presentation.core.icons.Magnet
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.plus
import tachiyomi.presentation.core.util.secondaryItemAlpha

@Composable
fun AnimeExtensionScreen(
    state: AnimeExtensionsScreenModel.State,
    contentPadding: PaddingValues,
    searchQuery: String?,
    onLongClickItem: (AnimeExtension) -> Unit,
    onClickItemCancel: (AnimeExtension) -> Unit,
    onOpenWebView: (AnimeExtension.Available) -> Unit,
    onInstallExtension: (AnimeExtension.Available) -> Unit,
    onUninstallExtension: (AnimeExtension) -> Unit,
    onUpdateExtension: (AnimeExtension.Installed) -> Unit,
    onTrustExtension: (AnimeExtension.Untrusted) -> Unit,
    onOpenExtension: (AnimeExtension.Installed) -> Unit,
    onClickUpdateAll: () -> Unit,
    onRefresh: () -> Unit,
) {
    val navigator = LocalNavigator.currentOrThrow

    PullRefresh(
        indicatorOnGestureOnly = true,
        refreshing = state.isRefreshing,
        onRefresh = onRefresh,
        enabled = !state.isLoading,
    ) {
        when {
            state.isLoading -> ExtensionManagerSkeleton(contentPadding)
            else -> {
                ManagedAnimeExtensionContent(
                    state = state,
                    contentPadding = contentPadding,
                    onLongClickItem = onLongClickItem,
                    onClickItemCancel = onClickItemCancel,
                    onOpenWebView = onOpenWebView,
                    onInstallExtension = onInstallExtension,
                    onUninstallExtension = onUninstallExtension,
                    onUpdateExtension = onUpdateExtension,
                    onTrustExtension = onTrustExtension,
                    onOpenExtension = onOpenExtension,
                    onClickUpdateAll = onClickUpdateAll,
                )
            }
        }
    }
}

@Composable
private fun ManagedAnimeExtensionContent(
    state: AnimeExtensionsScreenModel.State,
    contentPadding: PaddingValues,
    onLongClickItem: (AnimeExtension) -> Unit,
    onOpenWebView: (AnimeExtension.Available) -> Unit,
    onClickItemCancel: (AnimeExtension) -> Unit,
    onInstallExtension: (AnimeExtension.Available) -> Unit,
    onUninstallExtension: (AnimeExtension) -> Unit,
    onUpdateExtension: (AnimeExtension.Installed) -> Unit,
    onTrustExtension: (AnimeExtension.Untrusted) -> Unit,
    onOpenExtension: (AnimeExtension.Installed) -> Unit,
    onClickUpdateAll: () -> Unit,
) {
    var trust by remember { mutableStateOf<AnimeExtension.Untrusted?>(null) }
    val entries = state.items.values.flatten().map { item ->
        val extension = item.extension
        val installed = extension as? AnimeExtension.Installed
        val available = extension as? AnimeExtension.Available
        val untrusted = extension as? AnimeExtension.Untrusted
        val repository = installed?.store?.name ?: available?.store?.name
        ExtensionEntry(
            id = if (available == null) extension.pkgName else extension.pkgName + "|" + available.store.indexUrl,
            name = extension.name, version = extension.versionName,
            installed = available == null,
            languages = when (extension) {
                is AnimeExtension.Available -> extension.sources.map {
                    it.lang
                }.toSet().ifEmpty { setOf(extension.lang) }
                is AnimeExtension.Installed -> extension.sources.map { it.lang }.toSet()
                is AnimeExtension.Untrusted -> setOfNotNull(extension.lang)
            },
            repository = repository,
            metadata = installed?.metadata ?: ExtensionPackageMetadata(),
            status = installed?.updateStatus ?: ExtensionUpdateStatus.UNVERIFIED,
            needsTrust = untrusted != null, nsfw = extension.isNsfw, step = item.installStep,
            sources = available?.sources?.map { it.name }.orEmpty(),
            icon = { AnimeExtensionIcon(extension = extension, modifier = Modifier.size(44.dp)) },
            onOpen = {
                when (extension) {
                    is AnimeExtension.Installed -> onOpenExtension(extension)
                    is AnimeExtension.Available -> onOpenWebView(extension)
                    is AnimeExtension.Untrusted -> trust = extension
                }
            },
            onAction = {
                when (extension) {
                    is AnimeExtension.Installed -> if (extension.hasUpdate) {
                        onUpdateExtension(
                            extension,
                        )
                    } else {
                        onOpenExtension(extension)
                    }
                    is AnimeExtension.Available -> onInstallExtension(extension)
                    is AnimeExtension.Untrusted -> trust = extension
                }
            },
            onCancel = { onClickItemCancel(extension) },
            onLongClick = { onLongClickItem(extension) },
        )
    }
    val context = LocalContext.current
    val installGranted = rememberRequestPackageInstallsPermissionState(initialValue = true)
    ExtensionManagerContent(entries, contentPadding, state.updates, state.checkFailed, onClickUpdateAll) {
        if (!installGranted && state.installer?.requiresSystemPermission == true) {
            WarningBanner(
                textRes = MR.strings.ext_permission_install_apps_warning,
                modifier = Modifier.clickable { context.launchRequestPackageInstallsPermission() },
            )
        }
    }
    trust?.let { extension ->
        ExtensionTrustDialog(
            onClickConfirm = {
                onTrustExtension(extension)
                trust = null
            },
            onClickDismiss = {
                onUninstallExtension(extension)
                trust = null
            },
            onDismissRequest = { trust = null },
        )
    }
}
