package eu.kanade.tachiyomi.data.rin

import android.content.Context
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import eu.kanade.tachiyomi.extension.anime.util.AnimeExtensionInstallReceiver
import eu.kanade.tachiyomi.extension.manga.MangaExtensionManager
import eu.kanade.tachiyomi.extension.manga.util.MangaExtensionInstallReceiver
import eu.kanade.tachiyomi.extension.manga.util.MangaExtensionLoader
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.catalogueRequest
import it.lorelore.nyanime.studio.SourceActivationRequirements
import it.lorelore.nyanime.studio.StudioTelegramGrantStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import logcat.LogPriority
import mihon.domain.extension.anime.repository.AnimeExtensionStoreRepository
import mihon.domain.extensionrepo.manga.repository.MangaExtensionRepoRepository
import nyanime.telegram.TelegramAccountSessions
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

@Serializable
data class RinResources(val iconUrl: String? = null)

@Serializable
data class RinEntry(
    val manifest: RinManifest,
    val url: String,
    val sha256: String,
    val signer: String,
    val repository: String = "",
    val resources: RinResources = RinResources(),
    val format: String = "rin",
    val requiresAndroid: Boolean = false,
) {
    val iconUrl: String? get() = resources.iconUrl?.takeIf(RinArchive::validBundleUrl)
}

object RinUpdatePolicy {
    fun accepts(entry: RinEntry, installed: RinRecord): Boolean =
        entry.manifest.packageName == installed.manifest.packageName &&
            (entry.signer in installed.publisherSigners || entry.signer in installed.publisherHistory) &&
            entry.repository == installed.repository &&
            entry.manifest.medium == installed.manifest.medium &&
            entry.manifest.versionCode > installed.manifest.versionCode &&
            RinRepository.compatible(entry.manifest, entry.format) &&
            (
                installed.manifest.knownSourceIds.isEmpty() ||
                    entry.manifest.preservesSourceIds(installed.manifest.knownSourceIds)
                )

    /** Catalogue presence alone NEVER grants permission to install a new source. */
    fun updates(entries: List<RinEntry>, installed: List<RinRecord>): List<RinEntry> = installed.mapNotNull { old ->
        entries.filter { accepts(it, old) }.maxByOrNull { it.manifest.versionCode }
    }
}

class RinRepository private constructor(private val context: Context) {
    init {
        RetiredDocumentData.cleanRegistry(context)
    }

    val storage = RinStorage(context)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val refreshMutex = Mutex()
    private val preferences = context.getSharedPreferences("rin_state", Context.MODE_PRIVATE)
    private val mutableEntries = MutableStateFlow(cachedEntries())
    val entries = mutableEntries.asStateFlow()
    private val mutableInstalled = MutableStateFlow(
        storage.records().map {
            it.copy(trusted = eu.kanade.tachiyomi.extension.ExtensionPackageConsent.trusted(it))
        },
    )
    val installed = mutableInstalled.asStateFlow()
    private val mutableProgress = MutableStateFlow<Pair<String, Float?>?>(null)
    val progress = mutableProgress.asStateFlow()
    private val network get() = Injekt.get<NetworkHelper>()
    private val anime get() = Injekt.get<AnimeExtensionManager>()
    private val manga get() = Injekt.get<MangaExtensionManager>()
    private val inventoryMutex = Mutex()
    private var inventoryRetry: kotlinx.coroutines.Job? = null

    init {
        val migration = context.getSharedPreferences("rin_migration", Context.MODE_PRIVATE)
        runCatching {
            val raw = migration.getString("pending", null) ?: return@runCatching
            require(raw.length <= 256 * 1024)
            val prepared = RinArchive.json.parseToJsonElement(raw).jsonObject["prepared"]?.jsonArray.orEmpty()
            prepared.forEach { encoded ->
                val record = RinArchive.json.decodeFromJsonElement<RinRecord>(encoded)
                if (storage.record(record.manifest.packageName) == null &&
                    !legacyPresent(record.manifest.packageName)
                ) {
                    storage.activate(record.copy(enabled = false, trusted = false))
                }
            }
        }
        migration.edit().remove("pending").apply()
        storage.pruneOnColdStart(emptyList())
        scope.launch(Dispatchers.IO) { reloadInstalled() }
        scope.launch(Dispatchers.IO) {
            combine(TelegramAccountSessions.get(context).state, StudioTelegramGrantStore.get(context).changes) {
                    session,
                    grants,
                ->
                session.version to grants
            }.drop(1).collect { reloadInstalled() }
        }
        androidx.core.content.ContextCompat.registerReceiver(
            context,
            object : android.content.BroadcastReceiver() {
                override fun onReceive(context: Context, intent: android.content.Intent) {
                    if (intent.action == android.content.Intent.ACTION_PACKAGE_REMOVED &&
                        intent.getBooleanExtra(android.content.Intent.EXTRA_REPLACING, false)
                    ) {
                        return
                    }
                    eu.kanade.tachiyomi.extension.ExtensionPackageDiscovery.invalidate()
                    scope.launch(Dispatchers.IO) { reloadInstalled() }
                }
            },
            android.content.IntentFilter().apply {
                addAction(android.content.Intent.ACTION_PACKAGE_ADDED)
                addAction(android.content.Intent.ACTION_PACKAGE_REPLACED)
                addAction(android.content.Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
            },
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        scope.launch {
            eu.kanade.domain.extension.ExtensionCatalogueTrust(Injekt.get()).changes.drop(1).collect {
                reloadInstalled()
                eu.kanade.tachiyomi.data.addon.AddonRepository.get(context).registry.reload()
                Injekt.get<eu.kanade.tachiyomi.data.news.NewsRepository>().initialize()
                storage.records().filter { it.manifest.medium in setOf("anime", "manga") }.forEach {
                    notifyChanged(it)
                }
            }
        }
    }

    suspend fun reloadInstalled(): Unit = withContext(Dispatchers.IO) {
        inventoryMutex.withLock {
            storage.records().filter {
                it.isShared &&
                    eu.kanade.tachiyomi.extension.ExtensionPackageDiscovery.exists(context, it.manifest.packageName) ==
                    false
            }.forEach {
                storage.remove(it.manifest.packageName)
            }
            val records = eu.kanade.tachiyomi.extension.ExtensionPackageInventory.hosted(context, storage)
            mutableInstalled.value = records.map { raw ->
                val record = raw.copy(trusted = eu.kanade.tachiyomi.extension.ExtensionPackageConsent.trusted(raw))
                if (record.enabled && record.trusted && record.manifest.medium == "novel") {
                    val proof = try {
                        SourceActivationRequirements.proof(
                            context,
                            SourceActivationRequirements.inspect(context, record),
                            live = false,
                        )
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        null
                    }
                    if (proof == null || !SourceActivationRequirements.current(context, proof)) {
                        storage.record(record.manifest.packageName)?.takeIf { it.sha256 == record.sha256 }?.let {
                            storage.setEnabled(record.manifest.packageName, false, expected = it)
                        }
                        record.copy(enabled = false)
                    } else {
                        record
                    }
                } else {
                    record
                }
            }
            if (eu.kanade.tachiyomi.extension.ExtensionPackageDiscovery.unavailable.value &&
                inventoryRetry?.isActive != true
            ) {
                inventoryRetry = scope.launch(Dispatchers.IO) {
                    var retryDelay = 1_000L
                    while (eu.kanade.tachiyomi.extension.ExtensionPackageDiscovery.unavailable.value) {
                        kotlinx.coroutines.delay(retryDelay)
                        retryDelay = (retryDelay * 2).coerceAtMost(60_000)
                        eu.kanade.tachiyomi.extension.ExtensionPackageDiscovery.invalidate()
                        eu.kanade.tachiyomi.extension.ExtensionPackageDiscovery.retryFailed(context)
                        reloadInstalled()
                    }
                    Injekt.get<eu.kanade.tachiyomi.data.news.NewsRepository>().initialize()
                    eu.kanade.tachiyomi.data.addon.AddonRepository.get(context).registry.reload()
                    val native = eu.kanade.tachiyomi.extension.ExtensionPackageDiscovery.candidates(
                        context,
                        android.content.pm.PackageManager.GET_CONFIGURATIONS,
                        setOf("tachiyomi.animeextension", "tachiyomi.extension"),
                    )
                    native.forEach { info ->
                        if (info.reqFeatures.orEmpty().any { it.name == "tachiyomi.animeextension" }) {
                            AnimeExtensionInstallReceiver.notifyAdded(context, info.packageName)
                        } else {
                            MangaExtensionInstallReceiver.notifyAdded(context, info.packageName)
                        }
                    }
                }
            }
        }
    }

    private fun notifyChanged(record: RinRecord) {
        when (record.manifest.medium) {
            "anime" -> AnimeExtensionInstallReceiver.notifyAdded(context, record.manifest.packageName)
            "manga" -> MangaExtensionInstallReceiver.notifyAdded(context, record.manifest.packageName)
        }
    }

    suspend fun restoreDormant(record: RinRecord) = withContext(Dispatchers.IO) {
        storage.activate(record)
        reloadInstalled()
        notifyChanged(record)
    }

    suspend fun trustPackage(packageName: String) {
        val previous = installed.value.firstOrNull { it.manifest.packageName == packageName } ?: return
        activateVerified(
            previous.copy(trusted = true, catalogueConsent = null),
            storage.record(packageName),
            approveConsent = true,
        )
    }

    suspend fun setAutomaticUpdates(packageName: String, enabled: Boolean) {
        val previous = storage.record(packageName) ?: return
        storage.activate(previous.copy(automaticUpdates = enabled))
        reloadInstalled()
    }

    private fun cachedEntries(): List<RinEntry> = runCatching {
        RinArchive.json.decodeFromString<List<RinEntry>>(preferences.getString("catalogue", "[]")!!)
    }.getOrDefault(emptyList())

    suspend fun refresh(scheduleUpdates: Boolean = true): Boolean {
        val complete = refreshMutex.withLock {
            withContext(Dispatchers.IO) {
                if (Injekt.get<BasePreferences>().downloadedOnly().get()) return@withContext false
                val repositories = (
                    Injekt.get<AnimeExtensionStoreRepository>().getAll().map {
                        it.indexUrl to it.signingKey.lowercase()
                    } +
                        Injekt.get<MangaExtensionRepoRepository>().getAll().map {
                            it.baseUrl to it.signingKeyFingerprint.lowercase()
                        }
                    ).distinct()
                val all = mutableListOf<RinEntry>()
                var complete = true
                repositories.forEach { (repository, signer) ->
                    try {
                        val url = if (repository.endsWith(".json")) {
                            repository
                        } else {
                            "${repository.trimEnd('/')}/index.json"
                        }
                        network.apiClient.newCall(catalogueRequest(url)).awaitSuccess().use { response ->
                            val bytes = response.body.byteStream().use { boundedRead(it, 4 * 1024 * 1024) }
                            val root = RinArchive.json.parseToJsonElement(bytes.decodeToString()).jsonObject
                            (
                                root["rinList"]?.jsonObject?.get("extensions")?.jsonArray.orEmpty() +
                                    root["rinAddons"]?.jsonObject?.get("extensions")?.jsonArray.orEmpty() +
                                    root["rinGuides"]?.jsonObject?.get("extensions")?.jsonArray.orEmpty() +
                                    root["rinTv"]?.jsonObject?.get("extensions")?.jsonArray.orEmpty() +
                                    root["packageList"]?.jsonObject?.get("extensions")?.jsonArray.orEmpty()
                                ).forEach extensions@{ value ->
                                val decoded = RinArchive.json.decodeFromJsonElement<RinEntry>(value)
                                val entry = decoded.copy(repository = repository)
                                require(
                                    entry.format in setOf("rin", "apk") &&
                                        entry.signer.matches(Regex("[a-f0-9]{64}")) &&
                                        entry.sha256.matches(Regex("[a-f0-9]{64}")),
                                )
                                require(entry.format == "apk" || entry.signer == signer)
                                require(RinArchive.validBundleUrl(entry.url))
                                require(
                                    entry.manifest.schema ==
                                        if (entry.manifest.medium in
                                            setOf("addon", "guide", "tv", "news", "novel")
                                        ) {
                                            2
                                        } else {
                                            1
                                        },
                                )
                                if (!compatible(entry.manifest, entry.format) &&
                                    entry.manifest.medium !in setOf("addon", "guide", "tv", "novel")
                                ) {
                                    return@extensions
                                }
                                require(
                                    entry.manifest.medium != "addon" ||
                                        entry.manifest.addon?.valid() == true &&
                                        entry.manifest.hostVersions.valid,
                                )
                                require(entry.manifest.medium != "guide" || entry.manifest.guide?.valid() == true)
                                all += entry
                            }
                        }
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (error: Exception) {
                        logcat(LogPriority.WARN, error) { "RIN catalogue refresh failed" }
                        complete = false
                        // Retain only previously authenticated entries from this still-configured publisher.
                        all +=
                            entries.value.filter {
                                it.repository == repository &&
                                    (it.format == "apk" || it.signer == signer)
                            }
                    }
                }
                mutableEntries.value = all.distinctBy { Triple(it.manifest.packageName, it.sha256, it.signer) }
                val encoded = RinArchive.json.encodeToString(mutableEntries.value)
                check(preferences.edit().putString("catalogue", encoded).commit())
                complete
            }
        }
        // A Store refresh can discover a new version inside the periodic check window.
        // Apply those authenticated entries without downloading the catalogue a second time.
        if (scheduleUpdates && RinUpdatePolicy.updates(entries.value, installed.value).isNotEmpty()) {
            scope.launch { runAutomaticUpdate(catalogueRefreshed = complete) }
        }
        return complete
    }

    suspend fun trusted(entry: RinEntry): Boolean {
        val configured = Injekt.get<AnimeExtensionStoreRepository>().getAll().any {
            it.indexUrl == entry.repository && (entry.format == "apk" || it.signingKey.lowercase() == entry.signer)
        } ||
            Injekt.get<MangaExtensionRepoRepository>().getAll().any {
                it.baseUrl == entry.repository &&
                    (entry.format == "apk" || it.signingKeyFingerprint.lowercase() == entry.signer)
            }
        return configured && compatible(entry.manifest, entry.format)
    }

    fun legacyPresent(packageName: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(packageName, 0) }.isSuccess ||
            File(context.filesDir, "exts/$packageName.ext").exists()

    suspend fun prepare(entry: RinEntry): RinRecord = withContext(Dispatchers.IO) {
        check(trusted(entry) && !entry.requiresAndroid)
        require(RinArchive.validBundleUrl(entry.url))
        val file = File.createTempFile("rin-download-", ".tmp", context.cacheDir)
        mutableProgress.value = entry.manifest.packageName to null
        try {
            network.apiClient.newCall(GET(entry.url)).awaitSuccess().use { response ->
                val total = response.body.contentLength()
                require(total <= eu.kanade.tachiyomi.extension.ExtensionPackageArchive.MAX_BYTES)
                response.body.byteStream().use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var count = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            count += read
                            require(count <= eu.kanade.tachiyomi.extension.ExtensionPackageArchive.MAX_BYTES)
                            output.write(buffer, 0, read)
                            mutableProgress.value =
                                entry.manifest.packageName to if (total > 0) count.toFloat() / total else null
                        }
                    }
                }
            }
            val record = storage.stage(file, entry.signer, entry.sha256, entry.repository)
                .copy(catalogueConsent = storage.record(entry.manifest.packageName)?.catalogueConsent)
            require(record.manifest == entry.manifest && record.format == entry.format) { "RIN catalogue mismatch" }
            // Instantiate while the old package is still recoverable; IDs must match real source instances.
            record
        } finally {
            mutableProgress.value = null
            file.delete()
        }
    }

    /** Stage/authenticate a selected file without executing its code before confirmation. */
    suspend fun prepareLocal(uri: android.net.Uri): RinLocalImport = withContext(Dispatchers.IO) {
        val file = File.createTempFile("rin-import-", ".rin", context.cacheDir)
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw RinImportException(RinImportFailure.INVALID)
            input.use { source ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var bytes = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = source.read(buffer)
                        if (count < 0) break
                        bytes += count
                        require(bytes <= eu.kanade.tachiyomi.extension.ExtensionPackageArchive.MAX_BYTES)
                        output.write(buffer, 0, count)
                    }
                }
            }
            val apk = if (eu.kanade.tachiyomi.extension.ExtensionPackageArchive.isApk(file)) {
                eu.kanade.tachiyomi.extension.ExtensionPackageArchive.inspect(context, file)
            } else {
                null
            }
            if (apk?.requiresAndroid == true) throw RinImportException(RinImportFailure.SYSTEM_REQUIRED)
            val signer =
                apk?.let {
                    eu.kanade.tachiyomi.extension.ExtensionPackageInspector.inspect(it.info) { 0 }.signers.first()
                }
                    ?: RinArchive.publisher(file)
            val manifest = apk?.manifest ?: RinArchive.verify(file, signer)
            val format = if (apk == null) "rin" else "apk"
            if (!compatible(manifest, format)) throw RinImportException(RinImportFailure.INCOMPATIBLE)
            val previous = storage.record(manifest.packageName)
            val origins = Injekt.get<AnimeExtensionStoreRepository>().getAll().map {
                it.indexUrl to it.signingKey.lowercase()
            } +
                Injekt.get<MangaExtensionRepoRepository>().getAll().map {
                    it.baseUrl to it.signingKeyFingerprint.lowercase()
                }
            val origin = if (apk !=
                null
            ) {
                previous?.repository.orEmpty()
            } else {
                RinLocalImportPolicy.origin(signer, previous, origins)
            }
            val record = storage.stage(
                file,
                signer,
                eu.kanade.tachiyomi.extension.ExtensionPackageArchive.sha256(file),
                origin,
            )
            if (!RinLocalImportPolicy.accepts(record, previous)) throw RinImportException(RinImportFailure.CHANGED)
            RinLocalImport(record, previous)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: RinImportException) {
            throw error
        } catch (_: Exception) {
            throw RinImportException(RinImportFailure.INVALID)
        } finally {
            file.delete()
        }
    }

    suspend fun activateLocal(candidate: RinLocalImport) {
        if (RinSessions.busy.value || eu.kanade.tachiyomi.data.addon.RinAddonRuntime.busy.value) {
            throw RinImportException(RinImportFailure.BUSY)
        }
        if (!RinLocalImportPolicy.accepts(candidate.record, candidate.previous)) {
            throw RinImportException(RinImportFailure.CHANGED)
        }
        // This path follows the user's explicit file confirmation, not catalogue authority.
        // Approval applies only to this package; updates remain pinned to its signer and identity.
        activateVerified(candidate.record, candidate.previous, requireUnchanged = true, approveConsent = true)
    }

    private fun approveExecution(record: RinRecord) {
        eu.kanade.domain.extension.ExtensionExecutionTrust(Injekt.get()).approve(
            record.manifest.packageName,
            eu.kanade.domain.extension.ExtensionPackageMetadata(
                signers = record.publisherSigners,
                signerHistory = record.publisherHistory,
            ),
            record.repository,
            record.catalogueConsent,
        )
    }

    suspend fun activate(
        record: RinRecord,
        expected: RinRecord? = null,
        preserveEnabled: Boolean = true,
        requireUnchanged: Boolean = false,
    ) {
        check(
            trusted(
                RinEntry(record.manifest, "", record.sha256, record.signer, record.repository, format = record.format),
            ),
        )
        val previous = expected ?: storage.record(record.manifest.packageName)
        val metadata = eu.kanade.domain.extension.ExtensionPackageMetadata(
            signers = record.publisherSigners,
            signerHistory = record.publisherHistory,
        )
        val previouslyApproved = previous != null &&
            eu.kanade.tachiyomi.extension.ExtensionPackageConsent.trusted(previous) &&
            eu.kanade.tachiyomi.extension.ExtensionPackageChoice.samePublisher(
                previous.publisherSigners,
                eu.kanade.tachiyomi.extension.ExtensionPackageChoice.Candidate(
                    record.file,
                    record.manifest.versionCode,
                    record.publisherSigners,
                    record.publisherHistory,
                ),
            )
        check(
            previouslyApproved ||
                eu.kanade.domain.extension.ExtensionExecutionTrust(Injekt.get())
                    .permits(record.manifest.packageName, metadata),
        ) { "Execution consent required" }
        activateVerified(record, expected, preserveEnabled, requireUnchanged)
    }

    private suspend fun activateVerified(
        record: RinRecord,
        expected: RinRecord?,
        preserveEnabled: Boolean = true,
        requireUnchanged: Boolean = false,
        approveConsent: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        check(!requireUnchanged && expected == null || storage.record(record.manifest.packageName) == expected) {
            "Extension was removed or changed before activation"
        }
        val system = runCatching {
            context.packageManager.getPackageInfo(
                record.manifest.packageName,
                eu.kanade.tachiyomi.extension.ExtensionPackageArchive.FLAGS,
            )
        }.getOrNull()
        system?.let { info ->
            val metadata = eu.kanade.tachiyomi.extension.ExtensionPackageInspector.inspect(info) { 0 }
            require(
                eu.kanade.tachiyomi.extension.ExtensionPackageChoice.samePublisher(
                    metadata.signers,
                    eu.kanade.tachiyomi.extension.ExtensionPackageChoice.Candidate(
                        record.file,
                        record.manifest.versionCode,
                        record.publisherSigners,
                        record.publisherHistory,
                    ),
                ),
            )
            require(record.manifest.versionCode >= androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info))
        }
        val approved = record.copy(trusted = true)
        val sources = RinLoader.instantiate(context, approved)
        val accessProof = try {
            SourceActivationRequirements.proof(
                context,
                SourceActivationRequirements.capture(approved, sources),
                live = false,
            )
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            null
        }
        if (record.manifest.medium == "tv") {
            Injekt.get<eu.kanade.tachiyomi.data.tv.TvServices>().validatePackage(context, approved)
        }
        if (record.manifest.medium == "news") {
            val info = eu.kanade.tachiyomi.extension.ExtensionPackageArchive.inspect(
                context,
                storage.file(approved),
            ).info
            Injekt.get<eu.kanade.tachiyomi.data.news.NewsRepository>().registry.preflight(info)
        }
        val discovered = sources.mapNotNull {
            when (it) {
                is eu.kanade.tachiyomi.animesource.AnimeSource -> RinSource(it.id, it.name, it.lang)
                is eu.kanade.tachiyomi.source.CatalogueSource -> RinSource(it.id, it.name, it.lang)
                else -> null
            }
        }
        val verified = if (record.format == "apk" && record.manifest.sources.isEmpty() && discovered.isNotEmpty()) {
            approved.copy(manifest = approved.manifest.copy(sources = discovered))
        } else {
            approved
        }
        // Immutable files and pointer verification remain on IO. A session opening during
        // preflight defers activation; already created sources keep their immutable version.
        run {
            check(!RinSessions.busy.value && !eu.kanade.tachiyomi.data.addon.RinAddonRuntime.busy.value) {
                "Update deferred during playback or reading"
            }
            check(!requireUnchanged && expected == null || storage.record(record.manifest.packageName) == expected) {
                "Extension was removed or changed while preparing the update"
            }
            storage.activate(
                verified.copy(
                    automaticUpdates = expected?.automaticUpdates ?: record.automaticUpdates,
                    catalogueConsent = record.catalogueConsent,
                    enabled =
                    (
                        if (preserveEnabled) {
                            storage.record(record.manifest.packageName)?.enabled ?: record.enabled
                        } else {
                            record.enabled
                        }
                        ) &&
                        accessProof != null &&
                        SourceActivationRequirements.current(context, accessProof),
                ),
            )
            if (approveConsent) approveExecution(verified)
            reloadInstalled()
            if (record.manifest.medium in setOf("addon", "guide", "tv")) {
                // The generic addon runtime observes this registry; no source manager is notified.
            } else if (record.manifest.medium == "anime") {
                AnimeExtensionInstallReceiver.notifyAdded(context, record.manifest.packageName)
            } else if (record.manifest.medium == "manga") {
                MangaExtensionInstallReceiver.notifyAdded(context, record.manifest.packageName)
            }
        }
    }

    fun remove(packageName: String) {
        if (RinSessions.busy.value || eu.kanade.tachiyomi.data.addon.RinAddonRuntime.busy.value) return
        if (storage.record(packageName)?.manifest?.medium ==
            "addon"
        ) {
            eu.kanade.tachiyomi.data.addon.RinAddonRuntime.stop(context, packageName)
        }
        val medium = storage.record(packageName)?.manifest?.medium
        storage.remove(packageName)
        mutableInstalled.value = storage.records()
        scope.launch { reloadInstalled() }
        if (medium == "anime") AnimeExtensionInstallReceiver.notifyRemoved(context, packageName)
        if (medium == "manga") MangaExtensionInstallReceiver.notifyRemoved(context, packageName)
    }

    suspend fun setEnabled(
        packageName: String,
        enabled: Boolean,
        expected: RinRecord? = null,
        stillRequested: () -> Boolean = { true },
    ) = withContext(Dispatchers.IO) {
        check(!RinSessions.busy.value && !eu.kanade.tachiyomi.data.addon.RinAddonRuntime.busy.value) {
            "Close playback or reading before changing extensions"
        }
        val previous = requireNotNull(storage.record(packageName))
        check(stillRequested() && (expected == null || previous == expected.copy(trusted = previous.trusted))) {
            "Source activation changed or was cancelled"
        }
        if (previous.enabled == enabled) return@withContext
        // Validate before enabling; failed factories leave the disabled package intact.
        val proof = if (enabled) {
            SourceActivationRequirements.requireEnabled(context, previous, RinLoader.instantiate(context, previous))
        } else {
            null
        }
        withContext(Dispatchers.Main.immediate) {
            check(
                !RinSessions.busy.value &&
                    !eu.kanade.tachiyomi.data.addon.RinAddonRuntime.busy.value &&
                    storage.record(packageName) == previous &&
                    stillRequested() &&
                    (proof == null || SourceActivationRequirements.current(context, proof)),
            )
            val changed = storage.setEnabled(packageName, enabled, expected = previous)
            reloadInstalled()
            if (changed.manifest.medium == "addon") {
                if (!enabled) eu.kanade.tachiyomi.data.addon.RinAddonRuntime.stop(context, packageName)
            } else if (changed.manifest.medium in setOf("guide", "tv")) {
                // No source registrations belong to guide providers.
            } else if (changed.manifest.medium == "anime") {
                AnimeExtensionInstallReceiver.notifyAdded(context, packageName)
            } else if (changed.manifest.medium == "manga") {
                MangaExtensionInstallReceiver.notifyAdded(context, packageName)
            }
        }
    }

    internal suspend fun runAutomaticUpdate(catalogueRefreshed: Boolean? = null) {
        val complete = try {
            updateInstalled(catalogueRefreshed = catalogueRefreshed)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            logcat(LogPriority.WARN, error) { "RIN automatic update deferred" }
            false
        }
        if (!complete) RinSessions.retry(context)
    }

    // Compatibility entry point. All imported formats share the Store coordinator.
    suspend fun updateInstalled(force: Boolean = false, catalogueRefreshed: Boolean? = null): Boolean =
        eu.kanade.tachiyomi.data.extensionstore.ExtensionStoreRepository.get(context)
            .checkImportedUpdates(force || catalogueRefreshed == true)

    companion object {
        @Volatile private var instance: RinRepository? = null
        fun get(context: Context): RinRepository = instance ?: synchronized(this) {
            instance ?: RinRepository(context.applicationContext).also { instance = it }
        }

        fun compatible(m: RinManifest, format: String = "rin"): Boolean =
            m.hostVersions.supports(eu.kanade.tachiyomi.BuildConfig.VERSION_CODE.toLong()) &&
                when (m.medium) {
                    "novel" -> it.lorelore.nyanime.novel.source.NovelSourcePackages.compatible(m)
                    "news" -> m.schema == 2 && m.extensionLib == 1.0 && m.entryPoints.size == 1
                    "tv" ->
                        m.schema == 2 &&
                            m.extensionLib == 1.0 &&
                            m.entryPoints.size == 1 &&
                            m.validSourceIdentities()
                    "guide" -> m.schema == 2 && m.extensionLib == 1.0 && m.guide?.valid() == true
                    "addon" ->
                        m.schema == 2 &&
                            m.extensionLib in listOf(1.0, 2.0) &&
                            m.addon?.valid() == true &&
                            m.addon.api.toDouble() == m.extensionLib
                    "anime" -> m.extensionLib in listOf(14.0, 16.0, 17.0)
                    "manga" -> if (format == "apk") {
                        m.extensionLib in MangaExtensionLoader.SUPPORTED_LIB_VERSIONS
                    } else {
                        m.extensionLib in listOf(1.4, 1.5, 1.6)
                    }
                    else -> false
                }

        fun boundedRead(input: java.io.InputStream, limit: Int): ByteArray {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                require(output.size() + read <= limit)
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }
    }
}
