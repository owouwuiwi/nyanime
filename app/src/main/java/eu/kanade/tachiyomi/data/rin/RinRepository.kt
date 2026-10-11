package eu.kanade.tachiyomi.data.rin

import android.content.Context
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.extension.ExtensionCatalogueAuthorities
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import eu.kanade.tachiyomi.extension.anime.util.AnimeExtensionInstallReceiver
import eu.kanade.tachiyomi.extension.manga.MangaExtensionManager
import eu.kanade.tachiyomi.extension.manga.util.MangaExtensionInstallReceiver
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.catalogueRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
) {
    val iconUrl: String? get() = resources.iconUrl?.takeIf(RinArchive::validBundleUrl)
}

object RinUpdatePolicy {
    fun accepts(entry: RinEntry, installed: RinRecord): Boolean =
        entry.manifest.packageName == installed.manifest.packageName &&
            entry.signer == installed.signer &&
            entry.repository == installed.repository &&
            entry.manifest.medium == installed.manifest.medium &&
            entry.manifest.versionCode > installed.manifest.versionCode &&
            RinRepository.compatible(entry.manifest) &&
            entry.manifest.preservesSourceIds(installed.manifest.knownSourceIds)

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
    private val mutex = Mutex()
    private val refreshMutex = Mutex()
    private val preferences = context.getSharedPreferences("rin_state", Context.MODE_PRIVATE)
    private val mutableEntries = MutableStateFlow(cachedEntries())
    val entries = mutableEntries.asStateFlow()
    private val mutableInstalled = MutableStateFlow(storage.records())
    val installed = mutableInstalled.asStateFlow()
    private val mutableProgress = MutableStateFlow<Pair<String, Float?>?>(null)
    val progress = mutableProgress.asStateFlow()
    val migrationRequests = MutableStateFlow(0)
    private val network get() = Injekt.get<NetworkHelper>()
    private val anime get() = Injekt.get<AnimeExtensionManager>()
    private val manga get() = Injekt.get<MangaExtensionManager>()

    init {
        val prepared = runCatching {
            RinArchive.json.decodeFromString<RinMigrationState>(
                context.getSharedPreferences("rin_migration", Context.MODE_PRIVATE).getString("pending", "")!!,
            ).prepared
        }.getOrDefault(emptyList())
        storage.pruneOnColdStart(prepared)
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
                                    root["rinTv"]?.jsonObject?.get("extensions")?.jsonArray.orEmpty()
                                ).forEach extensions@{ value ->
                                val decoded = RinArchive.json.decodeFromJsonElement<RinEntry>(value)
                                val entry = decoded.copy(repository = repository)
                                require(entry.signer == signer && entry.sha256.matches(Regex("[a-f0-9]{64}")))
                                require(RinArchive.validBundleUrl(entry.url))
                                require(
                                    entry.manifest.schema ==
                                        if (entry.manifest.medium in setOf("addon", "guide", "tv")) 2 else 1,
                                )
                                if (!compatible(entry.manifest) &&
                                    entry.manifest.medium !in setOf("addon", "guide", "tv")
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
                        all += entries.value.filter { it.repository == repository && it.signer == signer }
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
            it.indexUrl == entry.repository && it.signingKey.lowercase() == entry.signer
        } ||
            Injekt.get<MangaExtensionRepoRepository>().getAll().any {
                it.baseUrl == entry.repository && it.signingKeyFingerprint.lowercase() == entry.signer
            }
        return configured && compatible(entry.manifest)
    }

    suspend fun migrationEntries(): List<RinEntry> = withContext(Dispatchers.IO) {
        anime.isInitialized.first { it }
        manga.isInitialized.first { it }
        val approvals = ExtensionCatalogueAuthorities(Injekt.get()).get()
        val records = storage.records().associateBy { it.manifest.packageName }
        entries.value.groupBy { it.manifest.packageName }.values.mapNotNull { candidates ->
            val candidate = candidates.singleOrNull() ?: return@mapNotNull null
            if (!trusted(candidate)) return@mapNotNull null
            if (records[candidate.manifest.packageName] != null &&
                !legacyPresent(candidate.manifest.packageName)
            ) {
                return@mapNotNull null
            }
            val packageName = candidate.manifest.packageName
            // An existing disabled RIN has no instances. Inspect the actual legacy APK,
            // never use the RIN's empty source list as evidence against mandatory migration.
            val a = anime.installedExtensionsFlow.value.firstOrNull { it.pkgName == packageName }
                ?.takeUnless { packageName in records }
                ?: if (candidate.manifest.medium == "anime" && legacyPresent(packageName)) {
                    (
                        eu.kanade.tachiyomi.extension.anime.util.AnimeExtensionLoader.inspectForMigration(
                            context,
                            packageName,
                        )
                            as? eu.kanade.tachiyomi.extension.anime.model.AnimeLoadResult.Success
                        )?.extension
                } else {
                    null
                }
            val m = manga.installedExtensionsFlow.value.firstOrNull { it.pkgName == packageName }
                ?.takeUnless { packageName in records }
                ?: if (candidate.manifest.medium == "manga" && legacyPresent(packageName)) {
                    (
                        eu.kanade.tachiyomi.extension.manga.util.MangaExtensionLoader.inspectForMigration(
                            context,
                            packageName,
                        )
                            as? eu.kanade.tachiyomi.extension.manga.model.MangaLoadResult.Success
                        )?.extension
                } else {
                    null
                }
            val ids = a?.sources?.map { it.id } ?: m?.sources?.map { it.id } ?: return@mapNotNull null
            val signer = a?.metadata?.signers ?: m!!.metadata.signers
            val approved = candidate.signer in signer ||
                approvals.any {
                    it.permits(candidate.repository, candidate.signer, candidate.manifest.medium)
                }
            candidate.takeIf { approved && it.manifest.preservesSourceIds(ids) }
        }
    }

    fun legacyPresent(packageName: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(packageName, 0) }.isSuccess ||
            File(context.filesDir, "exts/$packageName.ext").exists()

    suspend fun requestMigration(entry: RinEntry) = withContext(Dispatchers.IO) {
        check(trusted(entry) && legacyPresent(entry.manifest.packageName))
        check(
            context.getSharedPreferences("rin_migration", Context.MODE_PRIVATE).edit().putString(
                "pending",
                RinArchive.json.encodeToString(RinMigrationState(phase = "intro", entries = listOf(entry))),
            ).commit(),
        )
        migrationRequests.value++
    }

    suspend fun prepare(entry: RinEntry): RinRecord = withContext(Dispatchers.IO) {
        check(trusted(entry))
        require(RinArchive.validBundleUrl(entry.url))
        val file = File.createTempFile("rin-download-", ".tmp", context.cacheDir)
        mutableProgress.value = entry.manifest.packageName to null
        try {
            network.apiClient.newCall(GET(entry.url)).awaitSuccess().use { response ->
                val total = response.body.contentLength()
                require(total <= RinArchive.MAX_ARCHIVE_BYTES)
                response.body.byteStream().use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var count = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            count += read
                            require(count <= RinArchive.MAX_ARCHIVE_BYTES)
                            output.write(buffer, 0, read)
                            mutableProgress.value =
                                entry.manifest.packageName to if (total > 0) count.toFloat() / total else null
                        }
                    }
                }
            }
            val record = storage.stage(file, entry.signer, entry.sha256, entry.repository)
            require(record.manifest == entry.manifest) { "RIN catalogue mismatch" }
            // Instantiate while the old package is still recoverable; IDs must match real source instances.
            RinLoader.instantiate(context, record)
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
                        require(bytes <= RinArchive.MAX_ARCHIVE_BYTES)
                        output.write(buffer, 0, count)
                    }
                }
            }
            val signer = RinArchive.publisher(file)
            val manifest = RinArchive.verify(file, signer)
            if (!compatible(manifest)) throw RinImportException(RinImportFailure.INCOMPATIBLE)
            if (legacyPresent(manifest.packageName)) throw RinImportException(RinImportFailure.LEGACY)
            val previous = storage.record(manifest.packageName)
            val origins = Injekt.get<AnimeExtensionStoreRepository>().getAll().map {
                it.indexUrl to it.signingKey.lowercase()
            } +
                Injekt.get<MangaExtensionRepoRepository>().getAll().map {
                    it.baseUrl to it.signingKeyFingerprint.lowercase()
                }
            val origin = RinLocalImportPolicy.origin(signer, previous, origins)
            val record = storage.stage(file, signer, RinArchive.sha256(file.readBytes()), origin)
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
        activateVerified(candidate.record, candidate.previous, requireUnchanged = true)
    }

    suspend fun activate(
        record: RinRecord,
        expected: RinRecord? = null,
        preserveEnabled: Boolean = true,
        requireUnchanged: Boolean = false,
    ) {
        check(trusted(RinEntry(record.manifest, "", record.sha256, record.signer, record.repository)))
        activateVerified(record, expected, preserveEnabled, requireUnchanged)
    }

    private suspend fun activateVerified(
        record: RinRecord,
        expected: RinRecord?,
        preserveEnabled: Boolean = true,
        requireUnchanged: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        require(!legacyPresent(record.manifest.packageName)) { "Remove the previous extension first" }
        check(!requireUnchanged && expected == null || storage.record(record.manifest.packageName) == expected) {
            "Extension was removed or changed before activation"
        }
        RinLoader.instantiate(context, record)
        withContext(Dispatchers.Main.immediate) {
            check(!RinSessions.busy.value && !eu.kanade.tachiyomi.data.addon.RinAddonRuntime.busy.value) {
                "Update deferred during playback or reading"
            }
            check(!requireUnchanged && expected == null || storage.record(record.manifest.packageName) == expected) {
                "Extension was removed or changed while preparing the update"
            }
            storage.activate(
                record.copy(
                    enabled =
                    if (preserveEnabled) {
                        storage.record(record.manifest.packageName)?.enabled ?: record.enabled
                    } else {
                        record.enabled
                    },
                ),
            )
            mutableInstalled.value = storage.records()
            if (record.manifest.medium in setOf("addon", "guide", "tv")) {
                // The generic addon runtime observes this registry; no source manager is notified.
            } else if (record.manifest.medium == "anime") {
                AnimeExtensionInstallReceiver.notifyAdded(context, record.manifest.packageName)
            } else {
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
        if (medium == "anime") AnimeExtensionInstallReceiver.notifyRemoved(context, packageName)
        if (medium == "manga") MangaExtensionInstallReceiver.notifyRemoved(context, packageName)
    }

    suspend fun setEnabled(packageName: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        check(!RinSessions.busy.value && !eu.kanade.tachiyomi.data.addon.RinAddonRuntime.busy.value) {
            "Close playback or reading before changing extensions"
        }
        val previous = requireNotNull(storage.record(packageName))
        if (previous.enabled == enabled) return@withContext
        // Validate before enabling; failed factories leave the disabled package intact.
        if (enabled) RinLoader.instantiate(context, previous)
        withContext(Dispatchers.Main.immediate) {
            check(
                !RinSessions.busy.value &&
                    !eu.kanade.tachiyomi.data.addon.RinAddonRuntime.busy.value &&
                    storage.record(packageName) == previous,
            )
            val changed = storage.setEnabled(packageName, enabled)
            mutableInstalled.value = storage.records()
            if (changed.manifest.medium == "addon") {
                if (!enabled) eu.kanade.tachiyomi.data.addon.RinAddonRuntime.stop(context, packageName)
            } else if (changed.manifest.medium in setOf("guide", "tv")) {
                // No source registrations belong to guide providers.
            } else if (changed.manifest.medium == "anime") {
                AnimeExtensionInstallReceiver.notifyAdded(context, packageName)
            } else {
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

    suspend fun updateInstalled(force: Boolean = false, catalogueRefreshed: Boolean? = null): Boolean = withContext(
        Dispatchers.IO,
    ) {
        mutex.withLock {
            if (Injekt.get<BasePreferences>().downloadedOnly().get()) return@withLock true
            if (RinSessions.busy.value ||
                eu.kanade.tachiyomi.data.addon.RinAddonRuntime.busy.value
            ) {
                return@withLock false
            }
            val now = System.currentTimeMillis()
            val plan = RinUpdateSchedule.plan(
                now,
                preferences.getLong("checked", 0),
                RinUpdatePolicy.updates(entries.value, storage.records()).isNotEmpty(),
                force,
                catalogueRefreshed,
            )
            if (plan == RinUpdateSchedule.Plan.IDLE) return@withLock true
            val refreshed = if (plan == RinUpdateSchedule.Plan.REFRESH) {
                refresh(scheduleUpdates = false)
            } else {
                catalogueRefreshed != false
            }
            val updates = RinUpdatePolicy.updates(entries.value, storage.records())
            if (updates.any { it.manifest.medium in setOf("anime", "manga") }) {
                // Wait until source managers can safely reload the updated packages.
                anime.isInitialized.first { it }
                manga.isInitialized.first { it }
            }
            var deferred = !refreshed
            updates.forEach { entry ->
                val previous = storage.record(entry.manifest.packageName) ?: return@forEach
                if (RinSessions.busy.value || eu.kanade.tachiyomi.data.addon.RinAddonRuntime.busy.value) {
                    deferred = true
                    return@forEach
                }
                try {
                    val record = prepare(entry)
                    if (RinSessions.busy.value || eu.kanade.tachiyomi.data.addon.RinAddonRuntime.busy.value) {
                        deferred = true
                    } else {
                        activate(record, previous)
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    logcat(LogPriority.WARN, error) { "RIN update retained the previous version" }
                    deferred = true
                }
            }
            // Failed/deferred jobs remain due; a session closing or a worker retry checks them again.
            if (!deferred) preferences.edit().putLong("checked", now).apply()
            !deferred
        }
    }

    companion object {
        @Volatile private var instance: RinRepository? = null
        fun get(context: Context): RinRepository = instance ?: synchronized(this) {
            instance ?: RinRepository(context.applicationContext).also { instance = it }
        }

        fun compatible(m: RinManifest): Boolean =
            m.hostVersions.supports(eu.kanade.tachiyomi.BuildConfig.VERSION_CODE.toLong()) &&
                when (m.medium) {
                    "tv" ->
                        m.schema == 2 &&
                            m.extensionLib == 1.0 &&
                            m.entryPoints.size == 1 &&
                            m.validSourceIdentities()
                    "guide" -> m.schema == 2 && m.extensionLib == 1.0 && m.guide?.valid() == true
                    "addon" -> m.schema == 2 && m.extensionLib == 1.0 && m.addon?.valid() == true
                    "anime" -> m.extensionLib in listOf(14.0, 16.0, 17.0)
                    "manga" -> m.extensionLib in listOf(1.4, 1.5, 1.6)
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
