package eu.kanade.presentation.browse.store

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.more.settings.screen.browse.AnimeExtensionStoresScreen
import eu.kanade.presentation.more.settings.screen.browse.ExtensionCatalogueScreen
import eu.kanade.presentation.more.settings.screen.browse.MangaExtensionReposScreen
import eu.kanade.presentation.more.settings.screen.browse.components.CatalogueLinkHelpDialog
import eu.kanade.presentation.theme.LocalDarkTheme
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.extensionstore.ExtensionStoreRepository
import eu.kanade.tachiyomi.data.extensionstore.StoreAction
import eu.kanade.tachiyomi.data.extensionstore.StoreActivation
import eu.kanade.tachiyomi.data.extensionstore.StoreCatalogue
import eu.kanade.tachiyomi.data.extensionstore.StoreEntry
import eu.kanade.tachiyomi.data.extensionstore.StoreLanguages
import eu.kanade.tachiyomi.data.extensionstore.StoreMedium
import eu.kanade.tachiyomi.data.extensionstore.StorePresentation
import eu.kanade.tachiyomi.data.extensionstore.StoreShelf
import eu.kanade.tachiyomi.data.rin.RinSessions
import eu.kanade.tachiyomi.extension.InstallStep
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import eu.kanade.tachiyomi.extension.anime.model.AnimeExtension
import eu.kanade.tachiyomi.extension.manga.MangaExtensionManager
import eu.kanade.tachiyomi.extension.manga.model.MangaExtension
import eu.kanade.tachiyomi.ui.browse.AdvancedBrowseScreen
import eu.kanade.tachiyomi.ui.browse.anime.extension.details.AnimeExtensionDetailsScreen
import eu.kanade.tachiyomi.ui.browse.anime.source.browse.BrowseAnimeSourceScreen
import eu.kanade.tachiyomi.ui.browse.manga.extension.details.MangaExtensionDetailsScreen
import eu.kanade.tachiyomi.ui.browse.manga.source.browse.BrowseMangaSourceScreen
import eu.kanade.tachiyomi.ui.home.LocalFloatingNavigationInset
import eu.kanade.tachiyomi.ui.news.NewsSourcesScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

internal data class StoreColors(
    val background: Color,
    val surface: Color,
    val elevated: Color,
    val text: Color,
    val muted: Color,
    val line: Color,
    val accent: Color,
    val tint: Color,
)

internal val LocalStoreColors = staticCompositionLocalOf {
    StoreColors(
        Color.Black,
        Color.DarkGray,
        Color.Gray,
        Color.White,
        Color.LightGray,
        Color.Gray,
        Color.Red,
        Color.DarkGray,
    )
}

private data class StoreDisplay(
    val entries: List<StoreEntry> = emptyList(),
    val shelf: StoreShelf = StoreShelf.OWNED,
    val searching: Boolean = false,
    val filterKey: String = "",
)

@Composable
fun ExtensionStoreScreen(medium: StoreMedium, onMedium: (StoreMedium) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { ExtensionStoreRepository.get(context) }
    val entries by repository.entries.collectAsState()
    val steps by repository.steps.collectAsState()
    val progress by repository.progress.collectAsState()
    var associateOrigin by remember { mutableStateOf<StoreEntry?>(null) }
    var installMode by remember {
        mutableStateOf<Pair<StoreEntry, eu.kanade.tachiyomi.data.extensionstore.StoreInstallMode>?>(null)
    }
    val refreshing by repository.refreshing.collectAsState()
    val failed by repository.failed.collectAsState()
    val inventoryUnavailable by eu.kanade.tachiyomi.extension.ExtensionPackageDiscovery.unavailable.collectAsState()
    val preferences = remember { Injekt.get<SourcePreferences>() }
    val savedLanguages by
        preferences
            .enabledLanguages()
            .changes()
            .collectAsState(preferences.enabledLanguages().get())
    val languages = StoreLanguages.normalize(savedLanguages)
    val adult by
        preferences.showNsfwSource().changes().collectAsState(preferences.showNsfwSource().get())
    var shelf by rememberSaveable { mutableStateOf(StoreShelf.OWNED) }
    var category by rememberSaveable { mutableStateOf<StoreMedium?>(null) }
    var activation by rememberSaveable { mutableStateOf(StoreActivation.ALL) }
    var query by rememberSaveable { mutableStateOf("") }
    var filters by rememberSaveable { mutableStateOf(false) }
    var catalogues by rememberSaveable { mutableStateOf(false) }
    var addCatalogue by rememberSaveable { mutableStateOf(false) }
    var firstLoad by rememberSaveable { mutableStateOf(true) }
    var detail by remember { mutableStateOf<StoreEntry?>(null) }
    var trust by remember { mutableStateOf<StoreEntry?>(null) }
    var uninstall by remember { mutableStateOf<StoreEntry?>(null) }
    var changing by remember { mutableStateOf<Set<String>>(emptySet()) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val navigator = LocalNavigator.currentOrThrow
    val rinFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { navigator.push(RinImportScreen(it.toString())) }
    }
    val scheme = MaterialTheme.colorScheme
    val dark = LocalDarkTheme.current
    val colors =
        remember(scheme, dark) {
            StoreColors(
                if (dark) Color(0xFF0C0E13) else Color(0xFFFAFAFC),
                if (dark) Color(0xFF171A22) else Color.White,
                if (dark) Color(0xFF222632) else Color(0xFFF0F1F6),
                scheme.onSurface,
                scheme.onSurfaceVariant,
                scheme.outlineVariant.copy(alpha = .55f),
                scheme.primary,
                scheme.primary.copy(alpha = if (dark) .14f else .07f),
            )
        }
    val visible by
        produceState(
            StoreDisplay(),
            entries,
            shelf,
            category,
            activation,
            query,
            languages,
            adult,
        ) {
            value =
                withContext(Dispatchers.Default) {
                    StoreDisplay(
                        entries = StorePresentation.select(
                            entries,
                            shelf,
                            category,
                            activation,
                            query,
                            languages,
                            adult,
                        ),
                        shelf = shelf,
                        searching = query.isNotBlank(),
                        filterKey = "$shelf:$category:$activation:$query",
                    )
                }
        }
    val owned = remember(entries) { entries.count { it.installed } }
    val inactive =
        remember(entries) { entries.count { it.installed && (!it.enabled || it.action == StoreAction.TRUST) } }
    val discover =
        remember(entries, languages, adult) {
            entries.count { !it.installed && it.matchesLanguages(languages) && (adult || !it.nsfw) }
        }
    LifecycleResumeEffect(repository) {
        repository.resume()
        onPauseOrDispose {}
    }
    LaunchedEffect(refreshing) {
        if (!refreshing && firstLoad) {
            firstLoad = false
            if (owned == 0) shelf = StoreShelf.DISCOVER
        }
    }
    LaunchedEffect(medium) { if (category != null) category = medium }
    BackHandler { if (query.isNotEmpty()) query = "" else onBack() }
    val installer =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            repository.resume()
        }
    val report: (Int) -> Unit = { resource ->
        scope.launch { snackbar.showSnackbar(context.getString(resource)) }
    }
    val toggle: (StoreEntry) -> Unit = { entry ->
        if (RinSessions.busy.value) {
            report(R.string.store_session_busy)
        } else if (entry.packageName !in changing) {
            changing = changing + entry.packageName
            scope.launch {
                try {
                    repository.rin.setEnabled(entry.packageName, !entry.enabled)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    snackbar.showSnackbar(context.getString(R.string.store_activation_error))
                } finally {
                    changing = changing - entry.packageName
                }
            }
        }
    }
    val manage: (StoreEntry) -> Unit = { entry ->
        detail = null
        navigator.push(
            when (entry.medium) {
                StoreMedium.ANIME -> AnimeExtensionDetailsScreen(entry.packageName)
                StoreMedium.MANGA -> MangaExtensionDetailsScreen(entry.packageName)
                StoreMedium.NEWS -> NewsSourcesScreen()
                StoreMedium.TV -> eu.kanade.tachiyomi.ui.tv.TvExtensionScreen(entry.packageName)
                StoreMedium.GUIDE -> eu.kanade.presentation.episodeguide.GuideExtensionScreen(entry.packageName)
                StoreMedium.ADDON -> if (entry.isRin) {
                    eu.kanade.tachiyomi.ui.addon.RinAddonSettingsScreen(entry.packageName)
                } else {
                    eu.kanade.tachiyomi.ui.addon.AddonSettingsScreen(entry.packageName)
                }
            },
        )
    }
    val action: (StoreEntry) -> Unit = { entry ->
        when {
            entry.rinRecord != null && !entry.enabled && entry.action != StoreAction.TRUST -> toggle(entry)
            entry.action in setOf(StoreAction.INSTALL, StoreAction.UPDATE) -> {
                if (!entry.installed && repository.catalogueConsent(entry) == null) {
                    installMode = entry to repository.preferredMode(entry)
                } else {
                    repository.install(entry, repository.preferredMode(entry))?.let { job ->
                        scope.launch {
                            job.join()
                            if (repository.steps.value[entry.packageName] == InstallStep.Installed) {
                                val result = snackbar.showSnackbar(
                                    context.getString(R.string.store_installed_notice, entry.name),
                                    actionLabel = context.getString(R.string.store_mine),
                                    withDismissAction = true,
                                    duration = SnackbarDuration.Long,
                                )
                                if (result == SnackbarResult.ActionPerformed) {
                                    shelf = StoreShelf.OWNED
                                    query = ""
                                    activation = StoreActivation.ALL
                                }
                            }
                        }
                    }
                }
            }
            entry.action == StoreAction.TRUST -> {
                detail = null
                trust = entry
            }
            entry.action == StoreAction.OPEN -> {
                val a = (entry.anime as? AnimeExtension.Installed)?.sources.orEmpty()
                val m = (entry.manga as? MangaExtension.Installed)?.sources.orEmpty()
                when {
                    entry.medium in setOf(StoreMedium.GUIDE, StoreMedium.TV, StoreMedium.NEWS) -> manage(entry)
                    entry.medium == eu.kanade.tachiyomi.data.extensionstore.StoreMedium.ADDON &&
                        entry.rinRecord != null ->
                        eu.kanade.tachiyomi.data.addon.RinAddonRuntime.open(context, entry.packageName)
                    entry.addon != null ->
                        scope.launch { repository.addons.open(entry.packageName, context) }
                    a.size == 1 -> {
                        detail = null
                        navigator.push(BrowseAnimeSourceScreen(a.single().id, null))
                    }
                    m.size == 1 -> {
                        detail = null
                        navigator.push(BrowseMangaSourceScreen(m.single().id, null))
                    }
                    else -> detail = entry
                }
            }
            else -> manage(entry)
        }
    }
    CompositionLocalProvider(LocalStoreColors provides colors) {
        Box(Modifier.fillMaxSize().background(colors.background).statusBarsPadding().imePadding()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            stringResource(R.string.store_close),
                        )
                    }
                    Text(
                        stringResource(R.string.store_title),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )

                    IconButton(
                        onClick = { repository.refresh(force = true) },
                        enabled = !refreshing,
                    ) {
                        Icon(Icons.Outlined.Refresh, stringResource(R.string.store_refresh))
                    }
                    IconButton(onClick = { catalogues = true }) {
                        Icon(Icons.Outlined.Settings, stringResource(R.string.store_catalogues))
                    }
                }
                StoreShelves(shelf, owned, discover) { shelf = it }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    StoreButton(
                        stringResource(R.string.rin_load),
                        { rinFile.launch(arrayOf("*/*")) },
                        Modifier.weight(1f),
                        outlined = true,
                    )
                    StoreButton(
                        stringResource(R.string.extensions_catalogue_add),
                        { addCatalogue = true },
                        Modifier.weight(1f),
                        outlined = true,
                    )
                }
                StoreSearch(
                    query,
                    onQuery = { query = it },
                    onFilters = { filters = true },
                    filtered = activation != StoreActivation.ALL || languages.size != 2 || adult,
                )
                if (inventoryUnavailable) {
                    Text(
                        stringResource(R.string.package_inventory_unavailable),
                        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                        color = colors.muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                StoreCategories(category) {
                    category = it
                    if (it != null) onMedium(it)
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (shelf == StoreShelf.OWNED) {
                            stringResource(R.string.store_owned_summary, owned - inactive, inactive)
                        } else {
                            stringResource(R.string.store_discover_summary)
                        },
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.muted,
                    )
                    Text(
                        visible.entries.size.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.muted,
                    )
                }
                StoreGrid(
                    visible.entries,
                    steps,
                    visible.shelf,
                    visible.searching,
                    refreshing,
                    failed,
                    changing,
                    onInfo = { detail = it },
                    onAction = action,
                    onToggle = toggle,
                    onCancel = repository::cancel,
                    onRetry = { repository.refresh(force = true) },
                    hasFilters = query.isNotBlank() ||
                        category != null ||
                        (shelf == StoreShelf.OWNED && activation != StoreActivation.ALL),
                    onCatalogues = { catalogues = true },
                    onDiscover = { shelf = StoreShelf.DISCOVER },
                    onClear = {
                        query = ""
                        category = null
                        activation = StoreActivation.ALL
                    },
                    modifier = Modifier.weight(1f),
                    filterKey = visible.filterKey,
                )
            }
            SnackbarHost(
                snackbar,
                Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = LocalFloatingNavigationInset.current + 16.dp),
            )
        }
        if (filters) {
            StoreSheet(
                onDismiss = { filters = false },
                footer = {
                    StoreButton(
                        stringResource(R.string.store_done),
                        { filters = false },
                        Modifier.fillMaxWidth(),
                        accent = true,
                    )
                },
            ) {
                SheetTitle(stringResource(R.string.store_filters))
                if (shelf == StoreShelf.OWNED) {
                    StoreActivationFilters(activation) { activation = it }
                }
                StoreFilterOptions(
                    languages,
                    adult,
                    onLanguage = {
                        val selected = preferences.enabledLanguages()
                        selected.set(StoreLanguages.toggle(selected.get(), it))
                    },
                    onAdult = { preferences.showNsfwSource().set(it) },
                )
            }
        }
        if (catalogues) {
            StoreSheet(onDismiss = { catalogues = false }) {
                SheetTitle(stringResource(R.string.store_catalogues))
                val updates = entries.count {
                    it.action == StoreAction.UPDATE && (category == null || it.medium == category)
                }
                if (updates > 0) {
                    StoreButton(
                        stringResource(R.string.store_update_others, updates),
                        {
                            catalogues = false
                            repository.updateAll(category)
                        },
                        Modifier.fillMaxWidth(),
                        outlined = true,
                    )
                }
                StoreButton(stringResource(R.string.package_catalogue_trust), {
                    catalogues = false
                    navigator.push(eu.kanade.presentation.more.settings.screen.browse.ExtensionCatalogueTrustScreen())
                }, Modifier.fillMaxWidth(), outlined = true)
                Text(
                    stringResource(R.string.package_modes_hint),
                    color = colors.muted,
                    style = MaterialTheme.typography.bodyMedium,
                )
                StoreButton(
                    stringResource(R.string.store_video_catalogues),
                    {
                        catalogues = false
                        navigator.push(AnimeExtensionStoresScreen())
                    },
                    Modifier.fillMaxWidth(),
                    outlined = true,
                )
                StoreButton(
                    stringResource(R.string.store_manga_catalogues),
                    {
                        catalogues = false
                        navigator.push(MangaExtensionReposScreen())
                    },
                    Modifier.fillMaxWidth(),
                    outlined = true,
                )
                StoreButton(
                    stringResource(R.string.store_sources_migration),
                    {
                        catalogues = false
                        navigator.push(AdvancedBrowseScreen())
                    },
                    Modifier.fillMaxWidth(),
                    outlined = true,
                )
            }
        }
        if (addCatalogue) {
            CatalogueLinkHelpDialog(
                onDismissRequest = { addCatalogue = false },
                onLink = {
                    addCatalogue = false
                    navigator.push(ExtensionCatalogueScreen(it))
                },
                unified = true,
            )
        }
        detail?.let { selected ->
            val current = StoreCatalogue.currentEntry(selected, entries)
            if (current != null) {
                StoreDetails(
                    current,
                    steps[current.packageName],
                    current.packageName in changing,
                    onDismiss = { detail = null },
                    onAction = { action(current) },
                    onToggle = { toggle(current) },
                    onCancel = { repository.cancel(current) },
                    onManage = { manage(current) },
                    onFilters = {
                        detail = null
                        filters = true
                    },
                    onUninstall = {
                        detail = null
                        uninstall = current
                    },
                    onAnime = {
                        detail = null
                        navigator.push(BrowseAnimeSourceScreen(it, null))
                    },
                    onInstallMode = { mode ->
                        if (current.installed &&
                            current.action != StoreAction.TRUST ||
                            repository.catalogueConsent(current) != null
                        ) {
                            repository.install(current, mode)
                        } else {
                            installMode = current to mode
                        }
                    },
                    onAssociateOrigin = { associateOrigin = current },
                    onAutomaticUpdates = { enabled -> scope.launch { repository.automaticUpdates(current, enabled) } },
                    progress = progress[current.packageName],
                    onManga = {
                        detail = null
                        navigator.push(BrowseMangaSourceScreen(it, null))
                    },
                )
            }
        }
        associateOrigin?.let { entry ->
            StoreSheet(onDismiss = { associateOrigin = null }) {
                SheetTitle(stringResource(R.string.package_associate_origin))
                Text(stringResource(R.string.package_associate_hint), style = MaterialTheme.typography.bodyMedium)
                val choices = repository.updateOrigins(entry)
                if (choices.isEmpty()) {
                    Text(
                        stringResource(R.string.package_no_origin),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                choices.forEach { origin ->
                    StoreButton(origin.label, {
                        scope.launch {
                            try {
                                repository.associateOrigin(entry, origin)
                                associateOrigin = null
                            } catch (
                                cancel: CancellationException,
                            ) {
                                throw cancel
                            } catch (_: Exception) {
                                report(R.string.package_update_failed)
                            }
                        }
                    }, Modifier.fillMaxWidth(), outlined = true)
                }
            }
        }
        installMode?.let { (entry, mode) ->
            AlertDialog(
                onDismissRequest = { installMode = null },
                title = {
                    Text(
                        stringResource(
                            if (mode ==
                                eu.kanade.tachiyomi.data.extensionstore.StoreInstallMode.PRIVATE
                            ) {
                                R.string.package_import
                            } else {
                                R.string.package_install
                            },
                        ),
                    )
                },
                text = { Text(entry.name + "\n\n" + stringResource(R.string.store_trust_description)) },
                confirmButton = {
                    TextButton(onClick = {
                        repository.install(entry, mode, individualConsent = true)
                        installMode = null
                    }) { Text(stringResource(R.string.store_trust_accept)) }
                },
                dismissButton = {
                    TextButton(onClick = { installMode = null }) { Text(stringResource(R.string.store_not_now)) }
                },
            )
        }
        trust?.let { entry ->
            val signer =
                (entry.anime as? AnimeExtension.Untrusted)?.signatureHash
                    ?: (entry.manga as? MangaExtension.Untrusted)?.signatureHash
                    ?: entry.addon?.metadata?.signers?.joinToString("\n")
                    ?: entry.rinRecord?.publisherSigners?.joinToString("\n").orEmpty()
            AlertDialog(
                onDismissRequest = { trust = null },
                title = { Text(stringResource(R.string.store_trust_title)) },
                text = {
                    Text(
                        "${entry.name}\n\n${stringResource(R.string.store_trust_description)}\n$signer",
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            repository.trust(entry)
                            trust = null
                        },
                    ) {
                        Text(stringResource(R.string.store_trust_accept))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { trust = null }) {
                        Text(stringResource(R.string.store_not_now))
                    }
                },
            )
        }
        uninstall?.let { entry ->
            AlertDialog(
                onDismissRequest = { uninstall = null },
                title = { Text(stringResource(R.string.store_uninstall_title, entry.name)) },
                text = { Text(stringResource(R.string.store_uninstall_description)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            when {
                                RinSessions.busy.value -> report(R.string.store_session_busy)
                                entry.rinRecord != null && !entry.rinRecord.isShared -> repository.rin.remove(
                                    entry.packageName,
                                )
                                entry.anime != null ->
                                    Injekt.get<AnimeExtensionManager>()
                                        .uninstallExtension(entry.anime)
                                entry.manga != null ->
                                    Injekt.get<MangaExtensionManager>()
                                        .uninstallExtension(entry.manga)
                                else ->
                                    installer.launch(
                                        Intent(
                                            Intent.ACTION_DELETE,
                                            Uri.parse("package:${entry.packageName}"),
                                        ),
                                    )
                            }
                            uninstall = null
                        },
                    ) {
                        Text(stringResource(R.string.store_uninstall))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { uninstall = null }) {
                        Text(stringResource(R.string.store_not_now))
                    }
                },
            )
        }
    }
}
