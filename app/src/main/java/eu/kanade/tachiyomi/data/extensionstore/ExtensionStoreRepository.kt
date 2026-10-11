package eu.kanade.tachiyomi.data.extensionstore

import android.content.Context
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.data.news.NewsRepository
import eu.kanade.tachiyomi.extension.InstallStep
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import eu.kanade.tachiyomi.extension.anime.model.AnimeExtension
import eu.kanade.tachiyomi.extension.manga.MangaExtensionManager
import eu.kanade.tachiyomi.extension.manga.model.MangaExtension
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Application lifetime: changing screens cannot cancel extension downloads. */
class ExtensionStoreRepository private constructor(context: Context) {
    val rin = eu.kanade.tachiyomi.data.rin.RinRepository.get(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val anime = Injekt.get<AnimeExtensionManager>()
    private val manga = Injekt.get<MangaExtensionManager>()
    val news = Injekt.get<NewsRepository>()
    val addons = eu.kanade.tachiyomi.data.addon.AddonRepository.get(context)
    private val operations = StoreInstallCoordinator(scope)
    private val mutableSteps = MutableStateFlow<Map<String, InstallStep>>(emptyMap())
    val steps = mutableSteps.asStateFlow()
    private val mutableRefreshing = MutableStateFlow(true)
    val refreshing = mutableRefreshing.asStateFlow()
    private val mutableFailed = MutableStateFlow(false)
    val failed = mutableFailed.asStateFlow()
    private var refreshJob: Job? = null
    private var lastRefresh = 0L
    private var bulkJob: Job? = null
    private val awaitingNews = MutableStateFlow<Map<String, Pair<String, Long>>>(emptyMap())

    private val animeEntries =
        combine(
            anime.installedExtensionsFlow,
            anime.untrustedExtensionsFlow,
            anime.availableExtensionsFlow,
            StoreCatalogue::anime,
        )
    private val mangaEntries =
        combine(
            manga.installedExtensionsFlow,
            manga.untrustedExtensionsFlow,
            manga.availableExtensionsFlow,
            StoreCatalogue::manga,
        )
    private val newsEntries =
        combine(news.registry.state, news.catalogue.state) { installed, catalogue ->
            StoreCatalogue.news(
                installed,
                catalogue.entries.filter {
                    it.repository !in catalogue.unavailable
                },
                news.catalogue.availableFor(installed, catalogue),
            )
        }
    private val addonEntries = combine(addons.registry.state, addons.catalogue.state) { installed, catalogue ->
        StoreCatalogue.addons(
            installed,
            catalogue.entries.filter { it.repository !in catalogue.unavailable },
            addons.catalogue.availableFor(installed, catalogue),
        )
    }
    private val rinEntries = combine(
        rin.entries,
        rin.installed,
        anime.installedExtensionsFlow,
        manga.installedExtensionsFlow,
        RinStoreCatalogue::entries,
    )
    val entries = combine(animeEntries, mangaEntries, newsEntries, addonEntries, rinEntries) { a, m, n, d, r ->
        val rinPackages = r.map { it.packageName }.toSet()
        ((a + m).filterNot { it.packageName in rinPackages } + n + d + r)
            .sortedWith(compareBy<StoreEntry> { !it.ready }.thenBy { it.name.lowercase() }.thenBy { it.id })
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        scope.launch(Dispatchers.IO) {
            listOf(
                java.io.File(context.filesDir, "extension-store/prepared.apk"),
                java.io.File(context.filesDir, "extension-store/prepared.part"),
                java.io.File(context.noBackupFilesDir, "extension-store-replacement.json"),
                java.io.File(context.noBackupFilesDir, "extension-store-replacement.json.bak"),
            ).forEach { it.delete() }
        }
    }

    fun resume() {
        val awaiting = awaitingNews.value
        scope.launch(Dispatchers.IO) {
            try {
                news.initialize()
                addons.registry.reload()
                awaiting.forEach { (id, expected) ->
                    val present = news.registry.state.value.any {
                        it.packageName == expected.first && it.versionCode >= expected.second
                    } ||
                        addons.registry.state.value.any {
                            it.packageName == expected.first && it.versionCode >= expected.second
                        }
                    mutableSteps.update { it + (id to if (present) InstallStep.Installed else InstallStep.Idle) }
                }
                awaitingNews.update { it - awaiting.keys }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                mutableFailed.value = true
            }
        }
        refresh()
    }

    fun refresh(force: Boolean = false) {
        if (refreshJob?.isActive == true) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && lastRefresh > 0 && now - lastRefresh < 5_000) return
        if (Injekt.get<BasePreferences>().downloadedOnly().get()) {
            mutableRefreshing.value = false
            return
        }
        lastRefresh = now
        refreshJob = scope.launch {
            mutableRefreshing.value = true
            mutableFailed.value = false
            try {
                supervisorScope {
                    listOf<suspend () -> Unit>(
                        { anime.findAvailableExtensions() },
                        { manga.findAvailableExtensions() },
                        { news.catalogue.refresh() },
                        { addons.catalogue.refresh() },
                        { rin.refresh() },
                    ).map { operation ->
                        async(Dispatchers.IO) {
                            try {
                                operation()
                            } catch (
                                cancel: CancellationException,
                            ) {
                                throw cancel
                            } catch (_: Exception) {
                                mutableFailed.value = true
                            }
                        }
                    }.awaitAll()
                }
                if (news.catalogue.state.value.unavailable.isNotEmpty() ||
                    addons.catalogue.state.value.unavailable.isNotEmpty()
                ) {
                    mutableFailed.value = true
                }
            } finally {
                mutableRefreshing.value = false
            }
        }
    }

    fun install(entry: StoreEntry): Job? {
        val packageName = entry.packageName
        if (steps.value[packageName] == InstallStep.Installing) return null
        // Resolve again from live state; a stale card cannot bypass update/repository policy.
        val current = entries.value.firstOrNull { it.id == entry.id } ?: return null
        if (current.action !in setOf(StoreAction.INSTALL, StoreAction.UPDATE) ||
            !current.addonHostCompatible
        ) {
            return null
        }
        return operations.submit(packageName) {
            val operation = currentCoroutineContext()[Job]!!
            fun emit(step: InstallStep) {
                if (operations.isCurrent(packageName, operation)) {
                    mutableSteps.update { it + (packageName to step) }
                }
            }
            emit(InstallStep.Pending)
            try {
                when {
                    current.rinCandidate != null -> {
                        if (rin.legacyPresent(packageName)) {
                            rin.requestMigration(current.rinCandidate)
                            emit(InstallStep.Idle)
                            return@submit
                        }
                        emit(InstallStep.Downloading)
                        val prepared = rin.prepare(current.rinCandidate)
                        rin.activate(prepared)
                        emit(InstallStep.Installed)
                    }
                    current.action == StoreAction.UPDATE && current.anime is AnimeExtension.Installed -> {
                        anime.updateExtension(current.anime).collect { step ->
                            emit(step)
                        }
                    }
                    current.action == StoreAction.UPDATE && current.manga is MangaExtension.Installed -> {
                        manga.updateExtension(current.manga).collect { step ->
                            emit(step)
                        }
                    }
                    current.animeCandidate != null -> anime.installExtension(current.animeCandidate).collect { step ->
                        emit(step)
                    }
                    current.mangaCandidate != null -> manga.installExtension(current.mangaCandidate).collect { step ->
                        emit(step)
                    }
                    current.newsCandidate != null -> {
                        emit(InstallStep.Downloading)
                        news.catalogue.install(current.newsCandidate, news.registry)
                        val error = news.catalogue.state.value.error == current.packageName
                        if (!error) {
                            awaitingNews.update {
                                it + (packageName to (packageName to current.newsCandidate.versionCode))
                            }
                        }
                        emit(if (error) InstallStep.Error else InstallStep.Installing)
                    }
                    current.addonCandidate != null -> {
                        emit(InstallStep.Downloading)
                        addons.catalogue.install(current.addonCandidate, addons.registry)
                        val error = addons.catalogue.state.value.error == current.packageName
                        if (!error) {
                            awaitingNews.update {
                                it +
                                    (packageName to (packageName to current.addonCandidate.versionCode))
                            }
                        }
                        emit(if (error) InstallStep.Error else InstallStep.Installing)
                    }
                    else -> emit(InstallStep.Error)
                }
            } catch (cancel: CancellationException) {
                if (operations.isCurrent(packageName, operation)) mutableSteps.update { it - packageName }
                throw cancel
            } catch (_: Exception) {
                emit(InstallStep.Error)
            }
        }
    }

    /** Shares the Store coordinator; leaving the import screen does not interrupt activation. */
    fun installLocal(candidate: eu.kanade.tachiyomi.data.rin.RinLocalImport): Job =
        operations.submit(candidate.record.manifest.packageName) {
            val name = candidate.record.manifest.packageName
            val operation = currentCoroutineContext()[Job]!!
            fun emit(step: InstallStep) {
                if (operations.isCurrent(name, operation)) mutableSteps.update { it + (name to step) }
            }
            emit(InstallStep.Installing)
            try {
                rin.activateLocal(candidate)
                emit(InstallStep.Installed)
            } catch (cancel: CancellationException) {
                emit(InstallStep.Idle)
                throw cancel
            } catch (_: Exception) {
                emit(InstallStep.Error)
            }
        }

    fun cancel(entry: StoreEntry) {
        operations.cancel(entry.packageName)
        entry.anime?.let(anime::cancelInstallUpdateExtension)
        entry.manga?.let(manga::cancelInstallUpdateExtension)
        mutableSteps.update { it - entry.packageName }
    }

    fun trust(entry: StoreEntry) {
        scope.launch {
            try {
                (entry.anime as? AnimeExtension.Untrusted)?.let { anime.trust(it) }
                (entry.manga as? MangaExtension.Untrusted)?.let { manga.trust(it) }
                entry.addon?.let { addons.registry.trust(it.packageName) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                mutableFailed.value = true
            }
        }
    }

    fun updateAll(medium: StoreMedium? = null) {
        if (bulkJob?.isActive == true) return
        val queue = entries.value.filter {
            !it.isRin && (medium == null || it.medium == medium) && it.action == StoreAction.UPDATE
        }
        bulkJob = scope.launch {
            for (entry in queue) {
                val operation = install(entry) ?: break
                operation.join()
                if (entry.medium in setOf(StoreMedium.NEWS, StoreMedium.ADDON) &&
                    mutableSteps.value[entry.packageName] == InstallStep.Installing
                ) {
                    val result = withTimeoutOrNull(120_000) {
                        steps.first { it[entry.packageName] != InstallStep.Installing }
                    }
                    if (result == null || result[entry.packageName] != InstallStep.Installed) break
                }
                if (mutableSteps.value[entry.packageName] != InstallStep.Installed) break
            }
        }
    }

    companion object {
        @Volatile private var instance: ExtensionStoreRepository? = null
        fun get(context: Context): ExtensionStoreRepository = instance ?: synchronized(this) {
            instance ?: ExtensionStoreRepository(context.applicationContext).also { instance = it }
        }
    }
}
