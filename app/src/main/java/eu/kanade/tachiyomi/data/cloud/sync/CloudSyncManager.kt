package eu.kanade.tachiyomi.data.cloud.sync

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.cloud.CloudPreferences
import eu.kanade.tachiyomi.data.cloud.CloudRepository
import eu.kanade.tachiyomi.data.rin.RinSessions
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import eu.kanade.tachiyomi.extension.manga.MangaExtensionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import logcat.LogPriority
import nyanime.telegram.TelegramException
import nyanime.telegram.TelegramFailure
import nyanime.telegram.number
import nyanime.telegram.objectOrNull
import nyanime.telegram.sync.SyncActiveContent
import nyanime.telegram.sync.SyncDeviceCrypto
import nyanime.telegram.sync.SyncRef
import nyanime.telegram.sync.syncJson
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.handlers.anime.AnimeDatabaseHandler
import tachiyomi.data.handlers.manga.MangaDatabaseHandler
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.atomic.AtomicBoolean

/** Optional foreground synchronization. The native player is deliberately outside this boundary. */
internal class CloudSyncManager private constructor(private val context: Context) : DefaultLifecycleObserver {
    val preferences = CloudSyncPreferences()
    val setup by lazy { CloudSyncSetup(context, this) }
    private val cloud = CloudRepository.get(context)
    private val client get() = cloud.client
    private val base: BasePreferences = Injekt.get()
    private val sources: SourcePreferences = Injekt.get()
    private val portableSettings by lazy { PortableSettings(Injekt.get()) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operation = Mutex()
    private val mutableState = MutableStateFlow(CloudSyncStatus())
    val state = mutableState.asStateFlow()
    private val mutableContentRevision = MutableStateFlow(0L)
    val contentRevision = mutableContentRevision.asStateFlow()
    private var session: CloudSyncSession? = null
    private var ports: List<CloudSyncDomainPort> = emptyList()
    private var foreground = false
    private val active = MutableStateFlow(false)
    private var lease: String? = null
    private val networkDirty = AtomicBoolean(true)
    private val urgentSend = AtomicBoolean(false)
    private var lastPull = 0L
    private var lastSend = 0L
    private val clock = CloudSyncClock()
    private var retryAtElapsed = 0L
    private var projectionCursor = ""
    private var lastUnexpectedFailure: String? = null

    init {
        scope.launch(Dispatchers.Main.immediate) {
            ProcessLifecycleOwner.get().lifecycle.addObserver(this@CloudSyncManager)
        }
        scope.launch {
            client.updates.collect { event ->
                if (event.objectOrNull("message")?.number("chat_id") == session?.keys?.chat ||
                    event.number("chat_id") == session?.keys?.chat
                ) {
                    networkDirty.set(true)
                }
            }
        }
        scope.launch {
            combine(
                base.incognitoMode().changes(),
                sources.incognitoAnimeExtensions().changes(),
                sources.incognitoMangaExtensions().changes(),
            ) { _, _, _ -> Unit }.collectLatest {
                operation.withLock {
                    try {
                        configureJournals()
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        publish(CloudSyncPhase.ERROR)
                    }
                }
            }
        }
        scope.launch {
            combine(active, preferences.enabled().changes()) { visible, enabled -> visible && enabled }
                .collectLatest { running ->
                    if (!running) return@collectLatest
                    while (true) {
                        tick()
                        delay(1000)
                    }
                }
        }
        scope.launch {
            operation.withLock {
                if (session == null && preferences.account().get() > 0) {
                    try {
                        openSession(preferences.account().get())
                        publish(if (preferences.enabled().get()) CloudSyncPhase.CONNECTING else CloudSyncPhase.PAUSED)
                    } catch (_: Exception) {
                        publish(CloudSyncPhase.LOCKED)
                    }
                }
            }
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        foreground = true
        active.value = true
        networkDirty.set(true)
        if (preferences.enabled().get() && lease == null) lease = client.retain()
    }

    override fun onStop(owner: LifecycleOwner) {
        foreground = false
        active.value = false
        // A final bounded pass may finish; Android controls later best-effort work.
        scope.launch {
            if (preferences.enabled().get()) kotlinx.coroutines.withTimeoutOrNull(15_000) { tick() }
            withContext(Dispatchers.Main.immediate) {
                if (!foreground) {
                    lease?.let(client::release)
                    lease = null
                }
            }
        }
    }

    suspend fun tick(showActivity: Boolean = false): Boolean = operation.withLock {
        withContext(Dispatchers.IO) {
            if (!preferences.enabled().get()) return@withContext true
            if (SystemClock.elapsedRealtime() < retryAtElapsed) return@withContext false
            mutableState.value = mutableState.value.copy(retryAt = 0)
            try {
                if (context.packageName == eu.kanade.tachiyomi.BuildConfig.LEGACY_APPLICATION_ID) {
                    val transfer = eu.kanade.tachiyomi.data.migration.MigrationState.state(context)
                    if (transfer.optBoolean("pauseTransferredSync")) {
                        preferences.enabled().set(false)
                        configureJournals(enabled = false)
                        closeSession()
                        eu.kanade.tachiyomi.data.migration.MigrationState.update(context) {
                            it.remove("pauseTransferredSync")
                        }
                        publish(CloudSyncPhase.PAUSED)
                        return@withContext true
                    }
                    if (transfer.optLong("grantUntil") > System.currentTimeMillis()) {
                        publish(CloudSyncPhase.PAUSED)
                        return@withContext true
                    }
                }
                if (base.incognitoMode().get()) {
                    configureJournals()
                    publish(CloudSyncPhase.PAUSED)
                    return@withContext true
                }
                if (session == null) {
                    publish(CloudSyncPhase.CONNECTING)
                    openSession(preferences.account().get())
                }
                val current = requireNotNull(session)
                if (eu.kanade.tachiyomi.data.backup.restore.BackupRestoreJob.isRunning(context)) {
                    publish(CloudSyncPhase.WAITING_IDLE)
                    return@withContext false
                }
                // Save and capture offline changes before waiting for the Telegram transport.
                configureJournals()
                ports.forEach { it.capture() }
                val connectedAccount = cloud.ready()
                if (connectedAccount != current.keys.account) {
                    closeSession()
                    publish(CloudSyncPhase.OTHER_ACCOUNT)
                    return@withContext false
                }
                if (!client.state.value.online) throw TelegramException(TelegramFailure.DISCONNECTED)
                if (!current.keys.contains(current.device.public)) {
                    deviceRemoved()
                    return@withContext false
                }
                clock.refresh(client)
                if (preferences.importPending().get()) {
                    current.store.state("seeded", "false")
                    preferences.importPending().set(false)
                }
                if (current.store.state("seeded") != "true") {
                    if (RinSessions.busy.value) {
                        publish(CloudSyncPhase.WAITING_IDLE)
                        return@withContext false
                    }
                    publish(CloudSyncPhase.PREPARING)
                    ports.forEach { it.seed() }
                    current.store.state("seeded", "true")
                    current.store.state("initial-checkpoint", "true")
                }
                ports.forEach { it.capture() }
                val uncaptured = ports.sumOf { it.pendingCount() }
                val shouldPull = networkDirty.getAndSet(false) ||
                    current.store.pendingCount() > 0 ||
                    SystemClock.elapsedRealtime() - lastPull >= 15_000
                if (shouldPull) {
                    publish(mutableState.value.receiving(showActivity, uncaptured + current.store.pendingCount()))
                    current.transport.verifyArchive()
                    if (!current.pull()) {
                        networkDirty.set(true)
                        return@withContext false
                    }
                    lastPull = SystemClock.elapsedRealtime()
                    current.resumeMembershipChange()
                }
                if (!current.keys.contains(current.device.public)) {
                    deviceRemoved()
                    return@withContext false
                }
                val applied = if (!RinSessions.busy.value) applyAvailable() else false
                val pending = current.store.pendingCount()
                if (uncaptured > 0) {
                    publish(CloudSyncPhase.PREPARING)
                    return@withContext false
                }
                val checkpointDue = !RinSessions.busy.value &&
                    pending > 0 &&
                    clock.now() / 1000 - (current.store.state("lastCheckpoint")?.toLongOrNull() ?: 0) >= 86_400
                if (pending > 256 ||
                    checkpointDue ||
                    current.store.state("initial-checkpoint") == "true" ||
                    !current.store.state("checkpoint-pending").isNullOrEmpty()
                ) {
                    if (RinSessions.busy.value) {
                        publish(CloudSyncPhase.WAITING_IDLE)
                        return@withContext false
                    }
                    publish(CloudSyncPhase.SENDING)
                    current.checkpoints.publish(current.keys)
                    current.store.state("initial-checkpoint", "false")
                    networkDirty.set(true)
                } else if (pending > 0 &&
                    (
                        urgentSend.get() ||
                            !RinSessions.busy.value ||
                            SystemClock.elapsedRealtime() - lastSend >= 10_000
                        )
                ) {
                    publish(CloudSyncPhase.SENDING)
                    urgentSend.set(false)
                    current.sendChanges()
                    lastSend = SystemClock.elapsedRealtime()
                }
                if (!RinSessions.busy.value) {
                    val through = minOf(
                        current.store.state("compact-through")?.toLongOrNull() ?: 0,
                        current.store.state("processed")?.toLongOrNull() ?: 0,
                    )
                    if (through > 0) current.store.compactAcknowledged(through).forEach(current.files::removeDelivery)
                }
                // Cached authorization/history is not proof of a working Telegram connection.
                if (!client.state.value.online) throw TelegramException(TelegramFailure.DISCONNECTED)
                if (shouldPull) current.store.state("lastVerified", (clock.now() / 1000).toString())
                retryAtElapsed = 0
                lastUnexpectedFailure = null
                publish(
                    if (current.store.pendingCount() > 0) {
                        CloudSyncPhase.SENDING
                    } else if (current.store.projectionCount() > 0) {
                        when {
                            RinSessions.busy.value -> CloudSyncPhase.WAITING_IDLE
                            applied -> CloudSyncPhase.APPLYING
                            else -> CloudSyncPhase.WAITING_CONTENT
                        }
                    } else if (ports.any { it.blockedCount() > 0 }) {
                        CloudSyncPhase.WAITING_CONTENT
                    } else {
                        CloudSyncPhase.CURRENT
                    },
                )
                true
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: CloudSyncTransferDeferred) {
                networkDirty.set(true)
                publish(CloudSyncPhase.WAITING_IDLE)
                false
            } catch (error: TelegramException) {
                networkDirty.set(true)
                if (error.failure == TelegramFailure.ARCHIVE_MISSING && session != null) {
                    publish(CloudSyncPhase.PREPARING)
                    try {
                        if (RinSessions.busy.value) throw CloudSyncTransferDeferred()
                        val current = requireNotNull(session)
                        val repaired = CloudSyncArchiveRecovery(context, client).recover(current)
                        repaired.save(CloudSyncVault(context, repaired.account))
                        closeSession()
                        openSession(repaired.account)
                        retryAtElapsed = 0L
                        lastPull = 0L
                        return@withContext false
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (deferred: CloudSyncTransferDeferred) {
                        publish(CloudSyncPhase.WAITING_IDLE)
                        return@withContext false
                    } catch (recovery: TelegramException) {
                        reportTransportFailure(recovery)
                        return@withContext false
                    } catch (recovery: Exception) {
                        recordUnexpectedFailure(recovery)
                        publish(CloudSyncPhase.ERROR)
                        retryAfter(30_000)
                        return@withContext false
                    }
                }
                reportTransportFailure(error)
                false
            } catch (_: java.security.GeneralSecurityException) {
                publish(CloudSyncPhase.LOCKED)
                retryAfter(60_000)
                false
            } catch (error: Exception) {
                // A failed synchronization must never escape into startup, the player or the reader.
                recordUnexpectedFailure(error)
                publish(CloudSyncPhase.ERROR)
                retryAfter(30_000)
                false
            }
        }
    }

    private fun recordUnexpectedFailure(error: Exception) {
        // No exception message, account, content, keys or remote payload enters the diagnostic log.
        val fingerprint = error.javaClass.name + "\n" + error.stackTrace.take(8).joinToString("\n")
        if (lastUnexpectedFailure == fingerprint) return
        lastUnexpectedFailure = fingerprint
        logcat(LogPriority.WARN) { "Cloud sync unexpected failure: $fingerprint" }
    }

    private fun retryAfter(millis: Long) {
        retryAtElapsed = SystemClock.elapsedRealtime() + millis
        mutableState.value = mutableState.value.copy(retryAt = System.currentTimeMillis() + millis)
    }

    private fun reportTransportFailure(error: TelegramException) {
        retryAfter(
            if (error.failure ==
                TelegramFailure.RATE_LIMIT
            ) {
                error.retryAfterSeconds.coerceAtLeast(1) * 1000L
            } else {
                15_000L
            },
        )
        publish(
            when {
                error.failure == TelegramFailure.RATE_LIMIT -> CloudSyncPhase.RATE_LIMIT
                nyanime.telegram.TelegramErrors.retryable(error) -> CloudSyncPhase.NETWORK
                else -> CloudSyncPhase.ERROR
            },
        )
    }

    private fun openSession(account: Long) {
        val vault = CloudSyncVault(context, account)
        val clear = vault.read("device") ?: error("Device approval is required")
        val device = try {
            SyncDeviceCrypto.importAfterLocalUnwrapping(clear)
        } finally {
            clear.fill(0)
        }
        val keys = requireNotNull(CloudSyncKeys.load(vault, preferences.space().get()))
        require(keys.account == account)
        session = CloudSyncSession(context, client, keys, device, vault)
        val store = requireNotNull(session).store
        val previousChat = store.state("transport-chat")
        if (previousChat != null && previousChat != keys.chat.toString()) {
            requireNotNull(session).files.resetReceived()
            store.resetArchive(keys.chat)
        } else if (previousChat == null) {
            store.state("transport-chat", keys.chat.toString())
        }
        requireNotNull(session).transport.beforeSend = {
            val previousPolicy = store.state("exclusions")
            configureJournals()
            if (base.incognitoMode().get() || previousPolicy != store.state("exclusions")) {
                // Abort an envelope prepared under an older policy; capture/rebase it on the next pass.
                throw CloudSyncTransferDeferred()
            }
        }
        val active = SyncActiveContent()
        val name = { CloudPreferences().deviceName().get() }
        ports = listOf(
            AnimeCloudSyncPort(store, active, name, clock::now),
            MangaCloudSyncPort(store, active, name, clock::now),
            GuidedCloudSyncPort(context, store, preferences, clock::now),
            CloudSyncSettingsPort(context, store, preferences, clock::now),
            CloudSyncContentSettingsPort(context, store, preferences, clock::now),
            CloudSyncCategorySettingsPort(context, store, preferences, clock::now),
            CloudSyncCataloguesPort(context, store, preferences, clock::now),
            CloudSyncSourceSettingsPort(
                context,
                store,
                preferences,
                clock::now,
                { preferences.sharedCredentials().get() },
            ),
        )
        store.capture(
            SyncRef("device", title = device.public.id),
            mapOf("name" to JsonPrimitive(name().take(80))) + present,
            clock.now(),
        )
        client.observeChats("cloud-sync", setOf(keys.chat))
    }

    private var journalConfiguration: Triple<Boolean, Boolean, Pair<Set<Long>, Set<Long>>>? = null
    private suspend fun configureJournals(
        enabled: Boolean = preferences.account().get() > 0 &&
            session?.let { it.keys.contains(it.device.public) } == true,
    ) {
        val animePrivate = sources.incognitoAnimeExtensions().get()
        val mangaPrivate = sources.incognitoMangaExtensions().get()
        val anime = Injekt.get<AnimeExtensionManager>().installedExtensionsFlow.value
            .filter { it.pkgName in animePrivate }.flatMap { it.sources }.map { it.id }.toSet() +
            0L
        val manga = Injekt.get<MangaExtensionManager>().installedExtensionsFlow.value
            .filter { it.pkgName in mangaPrivate }.flatMap { it.sources }.map { it.id }.toSet() +
            0L
        val incognito = base.incognitoMode().get()
        val privatePackages = animePrivate + mangaPrivate
        val excludedPreferences = portableSettings.entries.values
            .filterNot { preferences.shares(it.group) }.map { it.key }.toSet()
        val catalogues = preferences.sharedCatalogues().get()
        val credentials = preferences.sharedCredentials().get()
        val signature = nyanime.telegram.sync.SyncCheckpoints.hash(
            listOf(
                anime.sorted(),
                manga.sorted(),
                privatePackages.sorted(),
                excludedPreferences.sorted(),
                catalogues,
                credentials,
                preferences.sharedReader().get(),
            ).toString(),
        )
        session?.store?.configureExclusions(signature) { ref ->
            when {
                ref.kind == "manga-guided-page" && !preferences.sharedReader().get() -> true
                ref.kind.endsWith("-category-preference") && !preferences.sharedPreferences().get() -> true
                ref.kind.endsWith("-choice-intro") && !preferences.sharedPlayback().get() -> true
                ref.kind.contains("-choice-") &&
                    !ref.kind.endsWith("-choice-intro") &&
                    !preferences.sharedPreferences().get() -> true
                ref.kind.endsWith("-source-credential") && !credentials -> true
                ref.kind.contains("-source-") && !catalogues -> true
                ref.kind.startsWith("anime-") -> ref.source in anime
                ref.kind.startsWith("manga-") -> ref.source in manga
                ref.kind == "preference" -> ref.title in excludedPreferences
                ref.kind == "rin" -> !catalogues || ref.title in privatePackages
                ref.kind == "catalogue" -> !catalogues
                else -> false
            }
        }
        val configuration = Triple(enabled, incognito, anime to manga)
        ports.filterIsInstance<GuidedCloudSyncPort>().forEach { it.configure(enabled, incognito, manga) }
        if (configuration == journalConfiguration) return
        Injekt.get<AnimeDatabaseHandler>().await(true) {
            cloudSyncQueries.configure(if (enabled) 1 else 0, if (incognito) 1 else 0)
            cloudSyncQueries.clearExcludedSources()
            anime.forEach(cloudSyncQueries::excludeSource)
        }
        Injekt.get<MangaDatabaseHandler>().await(true) {
            cloudSyncQueries.configure(if (enabled) 1 else 0, if (incognito) 1 else 0)
            cloudSyncQueries.clearExcludedSources()
            manga.forEach(cloudSyncQueries::excludeSource)
        }
        journalConfiguration = configuration
    }

    private suspend fun applyAvailable(): Boolean {
        val store = session?.store ?: return false
        val values = store.projections(after = projectionCursor)
        if (values.isEmpty()) {
            projectionCursor = ""
            return false
        }
        var anyApplied = false
        for (projection in values) {
            if (RinSessions.busy.value) break
            val kind = projection.record.ref.kind
            if (kind == "device") {
                store.applied(projection, projection.record.fields.mapValues { it.value.resolved()?.value })
                anyApplied = true
            } else {
                for (port in ports) {
                    if (port.apply(projection)) {
                        anyApplied = true
                        break
                    }
                }
            }
            projectionCursor = projection.key
        }
        if (anyApplied) mutableContentRevision.value++
        return anyApplied
    }

    private fun publish(phase: CloudSyncPhase) {
        val current = session
        val devices = current?.keys?.membership?.devices.orEmpty().values.map {
            val ref = SyncRef("device", title = it.id)
            val name = current?.store?.record(ref)?.values()?.get("name")
            CloudSyncDevice(
                it.id,
                (name as? JsonPrimitive)?.content?.take(80).orEmpty(),
                it.id == current?.device?.public?.id,
            )
        }
        mutableState.value = mutableState.value.copy(
            phase = phase,
            pending = current?.store?.pendingCount() ?: 0,
            projections = current?.store?.projectionCount() ?: 0,
            lastConfirmed = current?.store?.state("lastSent")?.toLongOrNull() ?: 0,
            lastVerified = current?.store?.state("lastVerified")?.toLongOrNull() ?: 0,
            devices = devices,
        )
    }

    suspend fun setEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        operation.withLock {
            require(!enabled || preferences.account().get() > 0)
            check(!enabled || session?.let { it.keys.contains(it.device.public) } != false)
            preferences.enabled().set(enabled)
            retryAtElapsed = 0
            networkDirty.set(true)
            if (enabled) {
                session?.keys?.chat?.let { client.observeChats("cloud-sync", setOf(it)) }
                if (foreground && lease == null) lease = client.retain()
            }
            if (!enabled) {
                lease?.let(client::release)
                lease = null
                client.observeChats("cloud-sync", emptySet())
                publish(CloudSyncPhase.PAUSED)
            }
            CloudSyncWorker.schedule(context)
        }
    }

    suspend fun syncNow(): Boolean {
        retryAtElapsed = 0
        networkDirty.set(true)
        return tick(showActivity = true)
    }

    private suspend fun deviceRemoved() {
        configureJournals(enabled = false)
        client.observeChats("cloud-sync", emptySet())
        lease?.let(client::release)
        lease = null
        publish(CloudSyncPhase.REMOVED)
        preferences.enabled().set(false)
        CloudSyncWorker.schedule(context)
    }

    suspend fun removeDevice(id: String) = withSession { current ->
        check(!RinSessions.busy.value && id != current.device.public.id)
        check(current.changeDevices(remove = id)) { "Devices changed; retry removal" }
        current.store.state("initial-checkpoint", "true")
        networkDirty.set(true)
        publish(CloudSyncPhase.SENDING)
    }

    suspend fun safetyCopy(): java.io.File = withSession { current ->
        check(!RinSessions.busy.value)
        CloudSyncSafetyCopy(context).restorePreview(current.keys)
    }

    suspend fun positions(ref: SyncRef): List<PortablePosition> = operation.withLock {
        withContext(Dispatchers.IO) {
            if (!preferences.enabled().get() || base.incognitoMode().get()) return@withContext emptyList()
            val current = session ?: return@withContext emptyList()
            if (current.store.isExcluded(ref)) return@withContext emptyList()
            current.store.record(ref)?.positions().orEmpty()
        }
    }

    suspend fun <T> withSession(action: suspend (CloudSyncSession) -> T): T = operation.withLock {
        withContext(Dispatchers.IO) {
            if (session == null) openSession(cloud.ready())
            action(requireNotNull(session))
        }
    }

    /** Called only after the explicit merge confirmation and a successful encrypted safety copy. */
    suspend fun activate(keys: CloudSyncKeys, startFrom: Long = 0, checkpoint: Long = 0) = operation.withLock {
        withContext(Dispatchers.IO) {
            check(!RinSessions.busy.value && !base.incognitoMode().get())
            check(cloud.ready() == keys.account)
            keys.save(CloudSyncVault(context, keys.account))
            preferences.account().set(keys.account)
            preferences.space().set(keys.space)
            closeSession()
            openSession(keys.account)
            configureJournals()
            val current = requireNotNull(session)
            publish(CloudSyncPhase.PREPARING)
            if (current.store.state("seeded") != "true") {
                ports.forEach { it.seed() }
                current.store.state("seeded", "true")
            }
            if (startFrom > 0 && current.store.state("processed") == null) {
                current.store.state("processed", startFrom.toString())
                current.store.state("received", startFrom.toString())
            }
            if (checkpoint > 0) {
                val through = current.checkpoints.restore(current.transport.message(checkpoint), keys)
                if (through > 0 && current.store.state("processed") == null) {
                    current.store.state("processed", through.toString())
                    current.store.state("received", through.toString())
                }
            }
            current.store.state("initial-checkpoint", "true")
            preferences.enabled().set(true)
            networkDirty.set(true)
            retryAtElapsed = 0
            if (foreground && lease == null) lease = client.retain()
            CloudSyncWorker.schedule(context)
        }
    }

    private fun closeSession() {
        session?.close()
        session = null
        ports = emptyList()
        client.observeChats("cloud-sync", emptySet())
    }

    companion object {
        @Volatile private var instance: CloudSyncManager? = null
        fun get(context: Context): CloudSyncManager = instance ?: synchronized(this) {
            instance ?: CloudSyncManager(context.applicationContext).also { instance = it }
        }
        fun startIfConfigured(context: Context) {
            if (CloudSyncPreferences().account().get() > 0) get(context)
        }

        /** A one-way signal after a local save. It never creates a client or touches native playback. */
        fun progressSaved() {
            val manager = instance ?: return
            manager.urgentSend.set(true)
            if (!manager.foreground && manager.preferences.enabled().get()) {
                manager.scope.launch { kotlinx.coroutines.withTimeoutOrNull(15_000) { manager.tick() } }
            }
        }
        fun wantsProgress(): Boolean = instance?.preferences?.enabled()?.get() == true
        fun backupRestored() {
            val preferences = CloudSyncPreferences()
            if (preferences.account().get() > 0) preferences.importPending().set(true)
        }
    }
}
