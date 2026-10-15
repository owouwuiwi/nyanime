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
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Application lifetime: changing screens cannot cancel extension downloads. */
class ExtensionStoreRepository private constructor(private val context: Context) {
    val rin = eu.kanade.tachiyomi.data.rin.RinRepository.get(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val anime = Injekt.get<AnimeExtensionManager>()
    private val manga = Injekt.get<MangaExtensionManager>()
    val news = Injekt.get<NewsRepository>()
    val addons = eu.kanade.tachiyomi.data.addon.AddonRepository.get(context)
    private val operations = StoreInstallCoordinator(scope)
    private val status = ExtensionInstallStatusStore(context)
    private val mutableSteps = MutableStateFlow(status.read())
    val steps = mutableSteps.asStateFlow()
    private val mutableProgress = MutableStateFlow<Map<String, Float?>>(emptyMap())
    val progress = mutableProgress.asStateFlow()
    private val packageInstaller = ExtensionPackageInstaller(context, rin)
    private val modes = context.getSharedPreferences("extension_install_modes_v1", Context.MODE_PRIVATE)
    fun preferredMode(entry: StoreEntry): StoreInstallMode = if (entry.rinRecord != null) {
        if (entry.rinRecord.isShared) StoreInstallMode.SYSTEM else StoreInstallMode.PRIVATE
    } else if (entry.privatelyInstalled || modes.getString("mode", "SYSTEM") == "PRIVATE") {
        if (entry.canImport) StoreInstallMode.PRIVATE else StoreInstallMode.SYSTEM
    } else {
        StoreInstallMode.SYSTEM
    }
    suspend fun automaticUpdates(
        entry: StoreEntry,
        enabled: Boolean,
    ) = rin.setAutomaticUpdates(entry.packageName, enabled)
    data class UpdateOrigin(val url: String, val label: String, val signer: String)
    fun updateOrigins(entry: StoreEntry): List<UpdateOrigin> {
        val signers = entry.rinRecord?.publisherHistory ?: entry.installedMetadata?.signerHistory.orEmpty()
        if (signers.isEmpty()) return emptyList()
        val candidates = StoreCatalogue.anime(emptyList(), emptyList(), anime.availableExtensionsFlow.value) +
            StoreCatalogue.manga(emptyList(), emptyList(), manga.availableExtensionsFlow.value) +
            StoreCatalogue.news(emptyList(), news.catalogue.state.value.entries, emptyList()) +
            StoreCatalogue.addons(emptyList(), addons.catalogue.state.value.entries, emptyList()) +
            RinStoreCatalogue.entries(rin.entries.value, emptyList(), emptyList(), emptyList())
        return candidates.filter {
            it.packageName == entry.packageName &&
                it.medium == entry.medium &&
                it.candidateSigner in signers &&
                (it.candidateVersion ?: 0) >= (entry.installedVersion ?: 0) &&
                it.candidateUrl != null &&
                it.addonHostCompatible
        }
            .mapNotNull { candidate ->
                candidate.repository?.let {
                    UpdateOrigin(it, candidate.repositoryName ?: it, requireNotNull(candidate.candidateSigner))
                }
            }.distinctBy { it.url to it.signer }
    }

    suspend fun associateOrigin(entry: StoreEntry, choice: UpdateOrigin) = kotlinx.coroutines.withContext(
        Dispatchers.IO,
    ) {
        require(choice in updateOrigins(entry))
        val previous = requireNotNull(rin.storage.record(entry.packageName))
        require(
            previous.sha256 == entry.rinRecord?.sha256 &&
                eu.kanade.tachiyomi.extension.ExtensionPackageConsent.trusted(previous),
        )
        require(choice.signer in previous.publisherHistory)
        // Explicit association changes only the update route; verified publisher identity stays pinned.
        rin.storage.activate(previous.copy(repository = choice.url, catalogueConsent = null))
        rin.reloadInstalled()
        refresh(force = true)
    }

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
    private val inventory = combine(animeEntries, mangaEntries, newsEntries, addonEntries, rinEntries) {
            a,
            m,
            n,
            d,
            r,
        ->
        val local = (a + m + n + d).groupBy { it.packageName }
        val hosted = r.map { record ->
            val other = local[record.packageName].orEmpty().firstOrNull()
            if (other != null &&
                (other.installedVersion ?: 0) > (record.installedVersion ?: 0) &&
                other.installedMetadata?.let { metadata ->
                    record.rinRecord?.let { old ->
                        eu.kanade.tachiyomi.extension.ExtensionPackageChoice.samePublisher(
                            old.publisherSigners,
                            eu.kanade.tachiyomi.extension.ExtensionPackageChoice.Candidate(
                                other.id,
                                other.installedVersion ?: 0,
                                metadata.signers,
                                metadata.signerHistory,
                            ),
                        )
                    }
                } == true
            ) {
                other
            } else if (other != null &&
                other.candidateVersion != null &&
                other.candidateSigner in record.rinRecord?.publisherHistory.orEmpty() &&
                other.repository == record.repository &&
                other.candidateVersion!! > (record.installedVersion ?: 0)
            ) {
                other.copy(
                    id = record.id,
                    installed = record.installed,
                    rinRecord = record.rinRecord,
                    anime = record.anime,
                    manga = record.manga,
                    action = StoreAction.UPDATE,
                )
            } else {
                record
            }
        }
        ((a + m + n + d).filterNot { it.packageName in hosted.map { it.packageName }.toSet() } + hosted)
            .sortedWith(compareBy<StoreEntry> { !it.ready }.thenBy { it.name.lowercase() }.thenBy { it.id })
    }.flowOn(Dispatchers.Default)
    val entries = inventory.stateIn(scope, SharingStarted.Eagerly, emptyList())

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

    private val automaticLock = kotlinx.coroutines.sync.Mutex()
    suspend fun checkImportedUpdates(force: Boolean = false): Boolean = automaticLock.withLock {
        if (Injekt.get<BasePreferences>().downloadedOnly().get()) return@withLock true
        val preferences = context.getSharedPreferences("extension_package_updates_v1", Context.MODE_PRIVATE)
        val checked = preferences.getLong("checked", 0)
        val now = System.currentTimeMillis()
        if (!force && checked > 0 && now - checked in 0 until 6 * 60 * 60 * 1000L) return@withLock true
        if (eu.kanade.tachiyomi.data.rin.RinSessions.busy.value ||
            eu.kanade.tachiyomi.data.addon.RinAddonRuntime.busy.value
        ) {
            return@withLock false
        }
        refresh(force = true)
        refreshJob?.join()
        if (failed.value) return@withLock false
        val snapshot = inventory.first()
        for (entry in snapshot.filter {
            it.installed &&
                it.imported &&
                it.automaticUpdates &&
                !it.repository.isNullOrBlank() &&
                it.action == StoreAction.UPDATE
        }) {
            val operation = install(entry, StoreInstallMode.PRIVATE) ?: return@withLock false
            operation.join()
            if (steps.value[entry.packageName] != InstallStep.Installed) return@withLock false
        }
        preferences.edit().putLong("checked", now).apply()
        true
    }

    fun resume() {
        val awaiting = awaitingNews.value
        scope.launch(Dispatchers.IO) {
            status.read().filterValues { it == InstallStep.Installing }.forEach { (name, _) ->
                val installed = runCatching {
                    if (status.expectedMode(name) == StoreInstallMode.PRIVATE) return@runCatching false
                    val info = context.packageManager.getPackageInfo(
                        name,
                        eu.kanade.tachiyomi.extension.ExtensionPackageArchive.FLAGS,
                    )
                    val expected = status.expectedVersion(name) ?: return@runCatching false
                    androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info) >= expected &&
                        eu.kanade.domain.extension.ExtensionExecutionTrust(Injekt.get()).permits(
                            name,
                            eu.kanade.tachiyomi.extension.ExtensionPackageInspector.inspect(info) { 0 },
                        )
                }.getOrDefault(false) ||
                    rin.storage.record(name)?.let { record ->
                        val expected = status.expectedVersion(name)
                        expected != null &&
                            status.expectedMode(name) != StoreInstallMode.SYSTEM &&
                            record.manifest.versionCode >= expected &&
                            eu.kanade.tachiyomi.extension.ExtensionPackageConsent.trusted(record)
                    } == true
                val result = if (installed) InstallStep.Installed else InstallStep.Idle
                status.set(name, result)
                mutableSteps.update { it + (name to result) }
            }
        }
        scope.launch(Dispatchers.IO) {
            try {
                rin.reloadInstalled()
                news.initialize()
                addons.registry.reload()
                awaiting.forEach { (id, expected) ->
                    val present = runCatching {
                        val info = context.packageManager.getPackageInfo(
                            expected.first,
                            eu.kanade.tachiyomi.extension.ExtensionPackageArchive.FLAGS,
                        )
                        androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info) >= expected.second &&
                            eu.kanade.domain.extension.ExtensionExecutionTrust(Injekt.get()).permits(
                                expected.first,
                                eu.kanade.tachiyomi.extension.ExtensionPackageInspector.inspect(info) { 0 },
                            )
                    }.getOrDefault(false) ||
                        rin.installed.value.any {
                            it.manifest.packageName == expected.first &&
                                it.manifest.versionCode >= expected.second &&
                                it.isShared
                        } ||
                        news.registry.state.value.any {
                            it.packageName == expected.first && it.versionCode >= expected.second
                        } ||
                        addons.registry.state.value.any {
                            it.packageName == expected.first && it.versionCode >= expected.second
                        }
                    if (present) {
                        rin.storage.record(expected.first)?.takeIf { it.format == "apk" }?.let { old ->
                            val info = context.packageManager.getPackageInfo(
                                expected.first,
                                eu.kanade.tachiyomi.extension.ExtensionPackageArchive.FLAGS,
                            )
                            if (eu.kanade.tachiyomi.extension.ExtensionPackageArchive.sha256(
                                    java.io.File(requireNotNull(info.applicationInfo?.sourceDir)),
                                ) ==
                                old.sha256
                            ) {
                                rin.storage.activate(old.copy(isShared = true))
                                rin.reloadInstalled()
                            }
                        }
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
                        { rin.refresh(scheduleUpdates = false) },
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

    fun catalogueConsent(entry: StoreEntry): String? =
        eu.kanade.domain.extension.ExtensionCatalogueTrust(Injekt.get()).get().firstOrNull {
            it.url == entry.repository
        }?.signer

    fun install(
        entry: StoreEntry,
        mode: StoreInstallMode = preferredMode(
            entry,
        ),
        individualConsent: Boolean = false,
    ): Job? {
        val packageName = entry.packageName
        if (steps.value[packageName] == InstallStep.Installing) return null
        // Resolve again from live state; a stale card cannot bypass update/repository policy.
        val current = entries.value.firstOrNull { it.id == entry.id } ?: return null
        val modeChange = current.installed &&
            current.candidateVersion == current.installedVersion &&
            mode != preferredMode(current) &&
            current.rinCandidate?.format != "rin"
        if ((!modeChange && current.action !in setOf(StoreAction.INSTALL, StoreAction.UPDATE)) ||
            !current.addonHostCompatible
        ) {
            return null
        }
        val consent = if (individualConsent) null else current.rinRecord?.catalogueConsent ?: catalogueConsent(current)
        if (!individualConsent &&
            (!current.installed || current.action == StoreAction.TRUST) &&
            consent == null
        ) {
            return null
        }
        return operations.submit(packageName) {
            val operation = currentCoroutineContext()[Job]!!
            fun emit(step: InstallStep) {
                if (operations.isCurrent(packageName, operation)) {
                    mutableSteps.update { it + (packageName to step) }
                    status.set(packageName, step, current.candidateVersion, mode)
                }
            }
            emit(InstallStep.Pending)
            try {
                when {
                    current.candidateUrl != null && current.rinCandidate?.format != "rin" -> {
                        emit(InstallStep.Downloading)
                        val done = packageInstaller.install(current, mode, { value ->
                            mutableProgress.update { it + (packageName to value) }
                        }, consent)
                        modes.edit().putString("mode", mode.name).apply()
                        if (!done) {
                            awaitingNews.update {
                                it +
                                    (packageName to (packageName to requireNotNull(current.candidateVersion)))
                            }
                        }
                        if (done) {
                            news.initialize()
                        }
                        emit(if (done) InstallStep.Installed else InstallStep.Installing)
                    }
                    current.rinCandidate != null -> {
                        emit(InstallStep.Downloading)
                        val prepared = rin.prepare(current.rinCandidate)
                        val previous = rin.storage.record(packageName)
                        val selected = prepared.copy(catalogueConsent = consent)
                        rin.activateLocal(eu.kanade.tachiyomi.data.rin.RinLocalImport(selected, previous))
                        emit(InstallStep.Installed)
                    }
                    else -> emit(InstallStep.Error)
                }
            } catch (cancel: CancellationException) {
                if (operations.isCurrent(packageName, operation)) mutableSteps.update { it - packageName }
                throw cancel
            } catch (_: Exception) {
                emit(InstallStep.Error)
            } finally {
                mutableProgress.update { it - packageName }
            }
        }
    }

    /** Shares the Store coordinator; leaving the import screen does not interrupt activation. */
    fun installLocal(candidate: eu.kanade.tachiyomi.data.rin.RinLocalImport): Job =
        operations.submit(candidate.record.manifest.packageName) {
            val name = candidate.record.manifest.packageName
            val operation = currentCoroutineContext()[Job]!!
            fun emit(step: InstallStep) {
                if (operations.isCurrent(name, operation)) {
                    mutableSteps.update { it + (name to step) }
                    status.set(name, step, candidate.record.manifest.versionCode, StoreInstallMode.PRIVATE)
                }
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
            } finally {
                mutableProgress.update { it - name }
            }
        }

    fun cancel(entry: StoreEntry) {
        operations.cancel(entry.packageName)
        entry.anime?.let(anime::cancelInstallUpdateExtension)
        entry.manga?.let(manga::cancelInstallUpdateExtension)
        mutableSteps.update { it - entry.packageName }
        status.forget(entry.packageName)
    }

    fun trust(entry: StoreEntry) {
        scope.launch {
            try {
                if (entry.rinRecord != null) rin.trustPackage(entry.packageName)
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
            (medium == null || it.medium == medium) && it.action == StoreAction.UPDATE
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
