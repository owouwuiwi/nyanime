package eu.kanade.presentation.browse.manga

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.GetApp
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.icerock.moko.resources.StringResource
import eu.kanade.domain.extension.ExtensionPackageMetadata
import eu.kanade.domain.extension.ExtensionUpdateStatus
import eu.kanade.presentation.browse.BaseBrowseItem
import eu.kanade.presentation.browse.components.ExtensionEntry
import eu.kanade.presentation.browse.components.ExtensionManagerContent
import eu.kanade.presentation.browse.components.ExtensionManagerSkeleton
import eu.kanade.presentation.browse.manga.components.MangaExtensionIcon
import eu.kanade.presentation.components.WarningBanner
import eu.kanade.presentation.entries.components.DotSeparatorNoSpaceText
import eu.kanade.presentation.more.settings.screen.browse.MangaExtensionReposScreen
import eu.kanade.presentation.util.animateItemFastScroll
import eu.kanade.presentation.util.rememberRequestPackageInstallsPermissionState
import eu.kanade.tachiyomi.extension.InstallStep
import eu.kanade.tachiyomi.extension.manga.model.MangaExtension
import eu.kanade.tachiyomi.ui.browse.manga.extension.MangaExtensionUiModel
import eu.kanade.tachiyomi.ui.browse.manga.extension.MangaExtensionsScreenModel
import eu.kanade.tachiyomi.util.system.LocaleHelper
import eu.kanade.tachiyomi.util.system.launchRequestPackageInstallsPermission
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.components.material.topSmallPaddingValues
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.theme.header
import tachiyomi.presentation.core.util.plus
import tachiyomi.presentation.core.util.secondaryItemAlpha

@Composable
fun MangaExtensionScreen(
    state: MangaExtensionsScreenModel.State,
    contentPadding: PaddingValues,
    searchQuery: String?,
    onLongClickItem: (MangaExtension) -> Unit,
    onClickItemCancel: (MangaExtension) -> Unit,
    onOpenWebView: (MangaExtension.Available) -> Unit,
    onInstallExtension: (MangaExtension.Available) -> Unit,
    onUninstallExtension: (MangaExtension) -> Unit,
    onUpdateExtension: (MangaExtension.Installed) -> Unit,
    onTrustExtension: (MangaExtension.Untrusted) -> Unit,
    onOpenExtension: (MangaExtension.Installed) -> Unit,
    onClickUpdateAll: () -> Unit,
    onRefresh: () -> Unit,
) {
    val navigator = LocalNavigator.currentOrThrow

    PullRefresh(
        refreshing = state.isRefreshing,
        onRefresh = onRefresh,
        enabled = !state.isLoading,
    ) {
        when {
            state.isLoading -> ExtensionManagerSkeleton(contentPadding)
            else -> {
                ManagedMangaExtensionContent(
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
fun ExtensionHeader(
    textRes: StringResource,
    modifier: Modifier = Modifier,
    action: @Composable RowScope.() -> Unit = {},
) {
    ExtensionHeader(
        text = stringResource(textRes),
        modifier = modifier,
        action = action,
    )
}

@Composable
fun ExtensionHeader(
    text: String,
    modifier: Modifier = Modifier,
    action: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier.padding(horizontal = MaterialTheme.padding.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            modifier = Modifier
                .padding(vertical = 8.dp)
                .weight(1f),
            style = MaterialTheme.typography.header,
        )
        action()
    }
}

@Composable
fun ExtensionTrustDialog(
    onClickConfirm: () -> Unit,
    onClickDismiss: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    AlertDialog(
        title = {
            Text(text = stringResource(MR.strings.untrusted_extension))
        },
        text = {
            Text(text = stringResource(MR.strings.untrusted_extension_message))
        },
        confirmButton = {
            TextButton(onClick = onClickConfirm) {
                Text(text = stringResource(MR.strings.ext_trust))
            }
        },
        dismissButton = {
            TextButton(onClick = onClickDismiss) {
                Text(text = stringResource(MR.strings.ext_uninstall))
            }
        },
        onDismissRequest = onDismissRequest,
    )
}

@Composable
private fun ManagedMangaExtensionContent(
    state: MangaExtensionsScreenModel.State,
    contentPadding: PaddingValues,
    onLongClickItem: (MangaExtension) -> Unit,
    onOpenWebView: (MangaExtension.Available) -> Unit,
    onClickItemCancel: (MangaExtension) -> Unit,
    onInstallExtension: (MangaExtension.Available) -> Unit,
    onUninstallExtension: (MangaExtension) -> Unit,
    onUpdateExtension: (MangaExtension.Installed) -> Unit,
    onTrustExtension: (MangaExtension.Untrusted) -> Unit,
    onOpenExtension: (MangaExtension.Installed) -> Unit,
    onClickUpdateAll: () -> Unit,
) {
    var trust by remember { mutableStateOf<MangaExtension.Untrusted?>(null) }
    val entries = state.items.values.flatten().map { item ->
        val extension = item.extension
        val installed = extension as? MangaExtension.Installed
        val available = extension as? MangaExtension.Available
        val untrusted = extension as? MangaExtension.Untrusted
        val repository = installed?.repoName ?: available?.repoName
        ExtensionEntry(
            id = if (available == null) extension.pkgName else extension.pkgName + "|" + available.repoUrl,
            name = extension.name, version = extension.versionName,
            installed = available == null,
            languages = when (extension) {
                is MangaExtension.Available -> extension.sources.map {
                    it.lang
                }.toSet().ifEmpty { setOf(extension.lang) }
                is MangaExtension.Installed ->
                    extension.sources
                        .filterIsInstance<eu.kanade.tachiyomi.source.CatalogueSource>()
                        .map { it.lang }.toSet()
                is MangaExtension.Untrusted -> setOfNotNull(extension.lang)
            },
            repository = repository,
            metadata = installed?.metadata ?: ExtensionPackageMetadata(),
            status = installed?.updateStatus ?: ExtensionUpdateStatus.UNVERIFIED,
            needsTrust = untrusted != null, nsfw = extension.isNsfw, step = item.installStep,
            sources = available?.sources?.map { it.name }.orEmpty(),
            icon = { MangaExtensionIcon(extension = extension, modifier = Modifier.size(44.dp)) },
            onOpen = {
                when (extension) {
                    is MangaExtension.Installed -> onOpenExtension(extension)
                    is MangaExtension.Available -> onOpenWebView(extension)
                    is MangaExtension.Untrusted -> trust = extension
                }
            },
            onAction = {
                when (extension) {
                    is MangaExtension.Installed -> if (extension.hasUpdate) {
                        onUpdateExtension(
                            extension,
                        )
                    } else {
                        onOpenExtension(extension)
                    }
                    is MangaExtension.Available -> onInstallExtension(extension)
                    is MangaExtension.Untrusted -> trust = extension
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
