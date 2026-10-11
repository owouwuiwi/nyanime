@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.data.community

import android.content.Context
import android.content.Intent
import android.util.Log
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.UUID

/** Application owner. No network, source resolution or native-player work occurs on the UI thread. */
class CommunityManager private constructor(
    context: Context,
    val personalOnly: Boolean = false,
) : CommunityInteractions {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val publicationMutex = Mutex()
    private val imageDrafts = CommunityImageDrafts(context)
    private var preferredImageHost = BlossomImages.hosts.first()
    private val mutable = MutableStateFlow(CommunityState())
    val state = mutable.asStateFlow()
    private val vault = if (personalOnly) {
        IdentityVault(context, PersonalSyncPolicy.VAULT, PersonalSyncPolicy.KEY_ALIAS)
    } else {
        IdentityVault(context)
    }
    private val store =
        CommunityStore(context, vault, if (personalOnly) PersonalSyncPolicy.DATABASE else "community-v1.db")
    private val library = LibrarySyncBridge()
    private val notifications = CommunityNotifications(context)
    private val preferences: BasePreferences = Injekt.get()
    private var identity: CommunityIdentity? = null
    private var transport: CommunityRelays? = null
    private var foreground = true
    private var disconnectJob: kotlinx.coroutines.Job? = null
    private var device = ""
    private var revision = SyncRevision(0, 0, "")
    private var relayList = CommunityRelays.defaults
    private var ticks = 0
    private var refreshedAt = 0L
    private var postLimit = 1000
    private data class LocalActivity(val ref: SyncReference, val public: SocialActivity, val at: Long)
    private var activityOwner = java.lang.ref.WeakReference<Any>(null)
    private var lastActivity: LocalActivity? = null
    private var refreshedGeneration = -1L
    private var libraryAt = 0L
    private val initialized = kotlinx.coroutines.CompletableDeferred<Unit>()

    @Volatile private var relayWarning: String? = null
    private val loggedRejections = mutableMapOf<String, Pair<RelayRejection, Long>>()
    private var deliveryLogAt = 0L
    internal val handoff = DeviceHandoff(this)

    init {
        action {
            device =
                store.read<String>("settings", "device")
                    ?: UUID.randomUUID().toString().replace("-", "").also { store.save("settings", "device", it) }
            revision = store.read<SyncRevision>("settings", "clock") ?: SyncRevision(0, 0, device)
            relayList = store.read<List<String>>("settings", "relays") ?: CommunityRelays.defaults
            vault.load()?.let { key ->
                identity = CommunityIdentity(key)
                key.fill(0)
            }
            mutable.update {
                it.copy(
                    ready = true,
                    background = !personalOnly && (store.read<Boolean>("settings", "background") ?: false),
                    syncEnabled = if (personalOnly) {
                        preferences.personalSyncEnabled().get()
                    } else {
                        store.read<Boolean>("settings", "sync")
                            ?: true
                    },
                    lastReceipt = store.read<Long>("settings", "last-receipt") ?: 0,
                    presenceAccess = store.read<PresenceAccess>("settings", "presence") ?: PresenceAccess.Private,
                )
            }
            refresh()
            if (identity != null) {
                library.capture(state.value.syncEnabled && !preferences.incognitoMode().get())
                connect()
            }
            initialized.complete(Unit)
        }
        scope.launch {
            preferences.incognitoMode().changes().collect { incognito ->
                mutex.withLock {
                    if (incognito) {
                        activityOwner.clear()
                        lastActivity = null
                    }
                    library.capture(identity != null && state.value.syncEnabled && !incognito)
                    if (!incognito && identity != null) {
                        store.list<NostrEvent>("deferred-sync", 2000).forEach { event ->
                            ingest(event)
                            if (store.seen(event.id)) store.remove("deferred-sync", event.id)
                        }
                    }
                }
            }
        }
        scope.launch {
            while (true) {
                delay(1000)
                mutex.withLock {
                    try {
                        if (identity == null ||
                            personalOnly &&
                            !state.value.syncEnabled ||
                            (!foreground && !state.value.background)
                        ) {
                            return@withLock
                        }
                        if (state.value.syncEnabled && !preferences.incognitoMode().get()) {
                            seedPersonalIfNeeded()
                            drain()
                        }
                        store.cleanExpired()
                        if (state.value.syncEnabled && !preferences.incognitoMode().get()) handoff.tick()
                        ticks++
                        if (ticks % 30 == 0 &&
                            foreground
                        ) {
                            publishPresence()
                        }
                        if (ticks % 5 == 0) {
                            if (state.value.syncEnabled && !preferences.incognitoMode().get()) {
                                publishCheckpoint()
                                transport?.recover(store.recoveryBatch())
                                mutable.update { it.copy(recovering = store.count("sync-needed")) }
                            }
                            retryDeferred()
                            if (state.value.syncEnabled &&
                                !preferences.incognitoMode().get()
                            ) {
                                store.list<SyncRecord>("unresolved", 20).forEach { pending ->
                                    if (library.apply(mapped(pending))) store.remove("unresolved", pending.ref.key())
                                }
                            }
                        }
                        refresh()
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        mutable.update {
                            it.copy(error = "Sincronizzazione in attesa. I dati restano salvati sul dispositivo.")
                        }
                    }
                }
            }
        }
    }
    private fun action(block: suspend () -> Unit) {
        scope.launch {
            mutex.withLock {
                try {
                    block()
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    mutable.update {
                        it.copy(
                            loading = false,
                            error =
                            (error as? IllegalArgumentException)?.message?.take(180)
                                ?: "Operazione non riuscita. Riprova: i dati locali sono conservati.",
                        )
                    }
                }
            }
        }
    }
    internal fun dispatch(block: suspend () -> Unit) = action(block)
    internal suspend fun <T> withLibraryImport(block: suspend () -> T): T = mutex.withLock {
        library.capture(false)
        try {
            block()
        } finally {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                val enabled = identity != null && state.value.syncEnabled && !preferences.incognitoMode().get()
                if (enabled) library.seed()
                library.capture(enabled)
            }
        }
    }
    internal fun deviceId() = device
    internal fun ownKey() = identity?.publicKey
    fun clearError() {
        mutable.update { it.copy(error = null) }
    }
    internal fun createPersonal() {
        check(personalOnly)
        scope.launch {
            initialized.await()
            create("I miei dispositivi")
        }
    }
    private fun enablePersonal() {
        if (!personalOnly) return
        preferences.personalSyncEnabled().set(true)
        mutable.update { it.copy(syncEnabled = true) }
    }
    private suspend fun seedPersonalIfNeeded() {
        if (personalOnly &&
            store.read<Boolean>("settings", "seed-needed") == true &&
            !preferences.incognitoMode().get()
        ) {
            library.seed()
            store.save("settings", "seed-needed", false)
        }
    }
    fun create(name: String) = action {
        require(identity == null && !vault.exists()) { "Questo dispositivo è già collegato" }
        require(name.trim().length in 1..40) { "Scegli un nome da 1 a 40 caratteri" }
        mutable.update { it.copy(loading = true) }
        val created = CommunityIdentity()
        val secret = created.exportSecret()
        try {
            if (personalOnly) store.save("settings", "seed-needed", true)
            vault.save(secret)
        } finally {
            secret.fill(0)
        }
        identity = created
        enablePersonal()
        if (!personalOnly) store.save("profiles", created.publicKey, CommunityProfile(created.publicKey, name.trim()))
        if (personalOnly) {
            seedPersonalIfNeeded()
        } else if (!preferences.incognitoMode().get()) {
            library.seed()
        }
        library.capture(!preferences.incognitoMode().get())
        connect()
        if (!personalOnly) publishProfileInternal(CommunityProfile(created.publicKey, name.trim()))
        drain()
        refresh()
    }
    fun saveProfileDraft(profile: CommunityProfile) = action {
        require(profile.key == identity?.publicKey)
        store.save("drafts", "profile", profile)
        mutable.update { it.copy(profileDraft = profile) }
    }

    fun publishProfile(profile: CommunityProfile) = scope.launch {
        publicationMutex.withLock {
            try {
                mutex.withLock {
                    require(profile.validDraft())
                    store.save("drafts", "profile", profile)
                    mutable.update { it.copy(publishing = true, profileDraft = profile) }
                }
                val artwork = PublicArtwork(context)
                val titles = (profile.favorites + profile.shelves).distinctBy { it.id }.associate { title ->
                    title.id to
                        artwork.prepare(title, state.value.library, ::uploadPublicArtwork)
                }
                val prepared = profile.copy(
                    avatar = prepareImage(profile.avatar),
                    banner = prepareImage(profile.banner),
                    favorites = profile.favorites.map {
                        titles.getValue(it.id).copy(status = it.status)
                    },
                    shelves = profile.shelves.map { titles.getValue(it.id).copy(status = it.status) },
                )
                mutex.withLock {
                    publishProfileInternal(prepared)
                    store.remove("drafts", "profile")
                    refresh()
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                mutable.update {
                    it.copy(
                        loading = false,
                        error = failure.takeIf { it is BlossomUploadException }?.message
                            ?: "Non riesco a preparare le immagini. Le modifiche sono salvate in bozza: puoi riprovare.",
                    )
                }
            } finally {
                mutable.update { it.copy(publishing = false) }
            }
        }
    }
    private fun publishProfileInternal(profile: CommunityProfile) {
        val id = requireNotNull(identity)
        require(profile.key == id.publicKey && profile.valid()) { "Controlla nome, immagini e liste del profilo" }
        val json = communityJson.encodeToString(profile)
        require(json.toByteArray().size <= 60_000) { "La vetrina è troppo grande. Riduci le liste pubblicate." }
        store.transaction {
            store.save("profiles", id.publicKey, profile)
            val metadata =
                buildJsonObject {
                    put("name", profile.name)
                    put("display_name", profile.name)
                    put("about", profile.bio)
                    put("picture", profile.avatar)
                    put("banner", profile.banner)
                }
            enqueuePublic(0, metadata.toString(), listOf(listOf("t", "nyanime")), "profile-metadata")
            enqueuePublic(10050, "", relayList.map { listOf("relay", it) }, "inbox-relays")
            enqueuePublic(10002, "", relayList.map { listOf("r", it) }, "profile-relays")
            enqueuePublic(
                30078,
                json,
                listOf(listOf("d", "nyanime.profile.v1"), listOf("t", "nyanime")),
                "profile-showcase",
            )
        }
    }
    private fun enqueuePublic(
        kind: Int,
        content: String,
        tags: List<List<String>>,
        address: String = "",
        priority: Int = 1,
    ): NostrEvent {
        check(!personalOnly || kind == 30078 && PersonalSyncPolicy.address(address))
        val id = requireNotNull(identity)
        // Addressable updates in the same second remain one queued draft. The published revision is monotonic.
        val at = if (address.isEmpty()) {
            System.currentTimeMillis() / 1000
        } else {
            val previous = store.read<Long>("published-at", address) ?: 0
            maxOf(System.currentTimeMillis() / 1000, previous + 1).also {
                require(it <= System.currentTimeMillis() / 1000 + 240)
                store.save("published-at", address, it)
            }
        }
        val event = NostrEvent.create(id, kind, content, tags, at)
        store.enqueue(event, address, priority)
        return event
    }
    private fun connect() {
        if (transport != null || personalOnly && !state.value.syncEnabled) return
        val id = identity ?: return
        transport = CommunityRelays(id, relayList, filters = {
            listOf(
                buildJsonObject {
                    put("kinds", JsonArray(listOf(0, 1, 7, 30078, 30315).map(::JsonPrimitive)))
                    put("#t", JsonArray(listOf(JsonPrimitive("nyanime"))))
                    put("limit", 200)
                },
                buildJsonObject {
                    put("kinds", JsonArray(listOf(JsonPrimitive(1059))))
                    put("#p", JsonArray(listOf(JsonPrimitive(id.publicKey))))
                    put("limit", 500)
                },
                buildJsonObject {
                    put("kinds", JsonArray(listOf(JsonPrimitive(30078))))
                    put("authors", JsonArray(listOf(JsonPrimitive(id.publicKey))))
                    put("limit", 1000)
                },
                buildJsonObject {
                    put("kinds", JsonArray(listOf(JsonPrimitive(30078))))
                    put("authors", JsonArray(listOf(JsonPrimitive(id.publicKey))))
                    put("#t", JsonArray(listOf(JsonPrimitive("nyanime-private-checkpoint"))))
                    put("limit", 200)
                },
            ).let { if (personalOnly) it.drop(1) else it }
        }, received = { event ->
            mutex.withLock {
                ingest(event)
                refresh()
            }
        }, accepted = { event, relay ->
            mutex.withLock {
                val recipient = store.recipient(event)
                store.acknowledge(event, relay, targetsFor(recipient))
                val at = System.currentTimeMillis()
                store.save("settings", "last-receipt", at)
                mutable.update { it.copy(lastReceipt = at) }
                updateDeliveryState()
            }
        }, status = { count, error ->
            relayWarning = error
            mutable.update { it.copy(connected = count, relayIssue = error) }
        }, next = { relay ->
            mutex.withLock {
                if (identity == null ||
                    personalOnly &&
                    (!state.value.syncEnabled || preferences.incognitoMode().get())
                ) {
                    null
                } else {
                    store.claimNext(
                        relay,
                        relay in relayList && state.value.syncEnabled && !preferences.incognitoMode().get(),
                        accepts = { event ->
                            (!personalOnly || PersonalSyncPolicy.event(event, id.publicKey)) &&
                                relay in targetsFor(event.takeIf { it.kind == 1059 }?.tag("p"))
                        },
                    )
                }
            }
        }, rejected = { event, relay, reason ->
            mutex.withLock {
                store.reject(event, relay, reason)
                updateDeliveryState()
                val now = System.currentTimeMillis()
                val previous = loggedRejections[relay]
                if (previous?.first != reason || now - previous.second >= 60_000) {
                    Log.w(
                        "NyanimeSync",
                        "relay=${java.net.URI(
                            relay,
                        ).host} rejected=${reason.code} queued=${state.value.pending} replicated=${state.value.replicating}",
                    )
                    loggedRejections[relay] = reason to now
                }
            }
        }, authenticated = { relay -> mutex.withLock { store.authenticated(relay) } }, diagnostic = {
            Log.w("NyanimeSync", it)
        })
        if (!personalOnly) transport?.query(id.publicKey)
        if (state.value.syncEnabled && !preferences.incognitoMode().get()) {
            store.list<SyncCheckpoint>("sync-checkpoints").forEach { scheduleRecovery(it) }
        }
        if (!personalOnly) {
            store.list<FriendState>("friends").filter { it.accepted || it.outgoing.isNotEmpty() }.forEach {
                transport?.query(it.peer)
                transport?.addDestinations(store.read<List<String>>("inbox-relays", it.peer).orEmpty(), it.peer)
            }
        }
    }
    fun onForeground(value: Boolean) = action {
        foreground = value
        disconnectJob?.cancel()
        disconnectJob = null
        if (identity == null) return@action
        publishPresence(clear = !value)
        if (state.value.syncEnabled && !preferences.incognitoMode().get()) drain()
        if (value || state.value.background) {
            if (value && state.value.background) {
                withContext(Dispatchers.Main) {
                    context.startForegroundService(Intent(context, CommunityConnectionService::class.java))
                }
            }
            connect()
        } else {
            // Allow final pause/exit updates to drain without holding the manager mutex.
            disconnectJob = scope.launch {
                delay(250)
                try {
                    mutex.withLock {
                        if (!foreground && state.value.syncEnabled && !preferences.incognitoMode().get()) drain()
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    mutable.update { it.copy(error = "L’ultimo progresso resta in coda. Riproverò alla riapertura.") }
                }
                delay(1750)
                mutex.withLock {
                    if (!foreground && !state.value.background) {
                        transport?.close()
                        transport = null
                        mutable.update { it.copy(connected = 0) }
                    }
                }
            }
        }
    }
    fun setBackground(enabled: Boolean) = action {
        check(!personalOnly)
        if (enabled) {
            withContext(Dispatchers.Main) {
                context.startForegroundService(Intent(context, CommunityConnectionService::class.java))
            }
            connect()
        } else {
            context.stopService(Intent(context, CommunityConnectionService::class.java))
            if (!foreground) {
                transport?.close()
                transport = null
                mutable.update { it.copy(connected = 0) }
            }
        }
        store.save("settings", "background", enabled)
        mutable.update { it.copy(background = enabled) }
    }
    fun setSync(enabled: Boolean) = action {
        require(identity != null)
        if (personalOnly && !enabled) handoff.disconnect()
        if (personalOnly) preferences.personalSyncEnabled().set(enabled)
        store.save("settings", "sync", enabled)
        mutable.update { it.copy(syncEnabled = enabled) }
        library.capture(enabled && !preferences.incognitoMode().get())
        if (enabled) {
            drain()
            store.list<NostrEvent>("deferred-sync", 2000).forEach { event ->
                ingest(event)
                if (store.seen(event.id)) store.remove("deferred-sync", event.id)
            }
            transport?.close()
            transport = null
            connect()
        }
        if (personalOnly && !enabled) {
            transport?.close()
            transport = null
            mutable.update { it.copy(connected = 0) }
        }
        refresh()
    }
    fun setRelays(values: List<String>) = action {
        require(
            values.size in 1..5 && values.distinct().size == values.size && values.all(CommunityRelays::validRelay),
        ) {
            "Inserisci da 1 a 5 relay wss:// validi"
        }
        relayList = values
        store.save("settings", "relays", values)
        store.retryDeliveries()
        transport?.close()
        transport = null
        connect()
        if (!personalOnly &&
            identity != null
        ) {
            enqueuePublic(10050, "", values.map { listOf("relay", it) }, "inbox-relays")
        }
    }
    private fun targetsFor(recipient: String?): List<String> =
        if (recipient != null && recipient != identity?.publicKey) {
            store.read<List<String>>("inbox-relays", recipient).orEmpty().ifEmpty { relayList }
        } else {
            relayList
        }

    private fun updateDeliveryState() {
        mutable.update {
            it.copy(
                pending = store.pendingCount(),
                replicating = store.replicatingCount(),
                relayIssue = store.deliveryIssue() ?: relayWarning,
            )
        }
        val now = System.currentTimeMillis()
        if (identity != null && now - deliveryLogAt >= 30_000) {
            deliveryLogAt = now
            Log.i(
                "NyanimeSync",
                "connected=${state.value.connected} queued=${state.value.pending} replicated=${state.value.replicating} enabled=${state.value.syncEnabled}",
            )
        }
    }

    fun retryDeliveries() = action {
        store.retryDeliveries()
        transport?.close()
        transport = null
        connect()
        updateDeliveryState()
    }

    fun relays() = relayList.toList()
    fun lookup(codeOrName: String) = action {
        val key = runCatching { ProfileCode.decode(codeOrName) }.getOrNull()
        if (key != null) {
            val hints = ProfileCode.relayHints(codeOrName)
            if (hints.isNotEmpty()) {
                if (!store.contains("inbox-relays", key)) store.save("inbox-relays", key, hints)
                transport?.addDestinations(hints, key)
            }
            transport?.query(key)
        } else {
            require(codeOrName.trim().length >= 2) { "Inserisci almeno due caratteri o un codice profilo" }
            transport?.search(codeOrName.trim())
        }
    }
    fun openProfile(key: String) = action {
        require(validKey(key))
        transport?.query(key)
    }
    fun loadOlder() = action {
        postLimit = (postLimit + 500).coerceAtMost(20_000)
        transport?.older((state.value.posts.minOfOrNull { it.at } ?: System.currentTimeMillis() / 1000) - 1)
        refresh()
    }
    fun code(key: String) = ProfileCode.encode(key)
    suspend fun profileLink(key: String): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            ProfileCode.link(
                key,
                if (key ==
                    identity?.publicKey
                ) {
                    relayList
                } else {
                    store.read<List<String>>("inbox-relays", key).orEmpty()
                },
            )
        }
    }
    suspend fun mappingCandidates(title: SyncRecord) = library.candidates(title)
    private fun mapped(record: SyncRecord) = record.copy(
        ref =
        store.read<SyncReference>("aliases", record.ref.key()) ?: record.ref,
    )
    private fun canonical(record: SyncRecord): SyncRecord {
        val original = store.entries("aliases").firstOrNull { (_, value) ->
            communityJson.decodeFromString<SyncReference>(value) == record.ref
        }?.first ?: return record
        return record.copy(ref = store.read<SyncRecord>("sync", original)?.ref ?: record.ref)
    }
    fun associate(record: SyncRecord, target: SyncRecord) = action {
        val pending = requireNotNull(store.read<SyncRecord>("unresolved", record.ref.key()))
        require(pending.ref.manga == target.ref.manga && pending.ref.itemUrl.isEmpty() == target.ref.itemUrl.isEmpty())
        require(library.apply(pending.copy(ref = target.ref))) { "La fonte scelta non è disponibile" }
        store.save("aliases", pending.ref.key(), target.ref)
        store.remove("unresolved", pending.ref.key())
        refresh()
    }

    fun post(post: SocialPost, wall: String = "", reply: String = "") = scope.launch {
        publicationMutex.withLock {
            try {
                mutex.withLock {
                    require(post.validDraft())
                    store.save("drafts", "post", post)
                    mutable.update { it.copy(postDraft = post, publishing = true) }
                }
                val prepared = post.copy(
                    image = prepareImage(post.image),
                    title = post.title?.let { title ->
                        PublicArtwork(context).prepare(title, state.value.library) {
                            uploadPublicArtwork(it)
                        }
                    },
                )
                mutex.withLock { publishPost(prepared, wall, reply) }
            } catch (
                cancel: CancellationException,
            ) {
                throw cancel
            } catch (
                _: Exception,
            ) {
                mutable.update {
                    it.copy(error = "La bozza è conservata. Non riesco a preparare le immagini per la pubblicazione.")
                }
            } finally {
                mutable.update { it.copy(publishing = false) }
            }
        }
    }
    private suspend fun publishPost(post: SocialPost, wall: String, reply: String) {
        require(post.valid()) { "Il messaggio è vuoto o troppo lungo" }
        val me = requireNotNull(identity).publicKey
        if (wall.isNotEmpty() && wall != me) {
            val owner = state.value.profiles[wall] ?: error("Profilo non disponibile")
            require(
                owner.wall == WallAccess.Everyone || owner.wall == WallAccess.Friends && state.value.isFriend(wall),
            ) {
                "Questa bacheca non accetta il tuo messaggio"
            }
        }
        val tags = buildList {
            add(listOf("t", "nyanime"))
            add(listOf("nyanime", communityJson.encodeToString(post.copy(text = ""))))
            if (wall.isNotEmpty()) {
                add(listOf("wall", wall))
                add(listOf("p", wall))
            }
            if (reply.isNotEmpty()) add(listOf("e", reply, "", "reply"))
            if (post.spoiler) add(listOf("content-warning", "Spoiler"))
        }
        val event = enqueuePublic(1, post.text, tags)
        ingest(event)
        store.remove("drafts", "post")
        refresh()
    }
    override fun react(item: CommunityItem) = action {
        val key = requireNotNull(identity).publicKey
        val event = enqueuePublic(
            7,
            if (key in
                item.likes
            ) {
                "-"
            } else {
                "+"
            },
            listOf(listOf("t", "nyanime"), listOf("e", item.id), listOf("p", item.author)),
        )
        ingest(event)
        refresh()
    }
    override fun moderate(item: CommunityItem, pin: Boolean) = action {
        val me = requireNotNull(identity).publicKey
        require(item.wall == me || item.author == me) { "Puoi moderare soltanto la tua bacheca" }
        val name = if (pin) "pinned" else "hidden"
        val ids = store.read<Set<String>>("moderation", name).orEmpty().toMutableSet()
        if (!ids.add(item.id)) ids.remove(item.id)
        store.save("moderation", name, ids)
        enqueuePublic(
            30078,
            communityJson.encodeToString(ids),
            listOf(listOf("d", "nyanime.$name.v1"), listOf("t", "nyanime")),
            name,
        )
        refresh()
    }
    private fun sendAction(action: PrivateAction, peers: List<String>): PrivateAction {
        check(!personalOnly || PersonalSyncPolicy.command(action.type) && peers.all { it == identity?.publicKey })
        val id = requireNotNull(identity)
        val command = action.copy(
            revision = if (action.revision >
                0
            ) {
                action.revision
            } else {
                nextRevision().millis * 1000 + revision.counter
            },
        )
        val rumor = GiftWrap.rumor(
            id.publicKey,
            30079,
            communityJson.encodeToString(command),
            peers.distinct().map {
                listOf("p", it)
            },
        )
        val envelopes = (peers + id.publicKey).distinct().map { GiftWrap.wrap(id, it, rumor, command.expires) }
        peers.forEach { peer ->
            transport?.addDestinations(store.read<List<String>>("inbox-relays", peer).orEmpty(), peer)
        }
        store.transaction {
            envelopes.forEach {
                store.enqueue(
                    it,
                    address = if (action.type ==
                        "device.presence"
                    ) {
                        "device-presence:$device"
                    } else {
                        ""
                    },
                    priority = 3,
                )
            }
        }
        return command
    }
    internal fun sendDeviceAction(
        action: PrivateAction,
    ) = sendAction(action, listOf(requireNotNull(identity).publicKey))
    override fun requestFriend(peer: String) = action {
        require(validKey(peer) && peer != identity?.publicKey)
        transport?.query(peer)
        val old = friend(peer)
        require(!old.blocked) { "Sblocca prima questo profilo" }
        if (old.accepted || old.outgoing.isNotEmpty()) return@action
        val request = UUID.randomUUID().toString()
        val command = PrivateAction(type = "friend.request", request = request, peer = peer)
        applyFriend(peer, sendAction(command, listOf(peer)), true)
        refresh()
    }
    fun acceptFriend(peer: String) = action {
        val old = friend(peer)
        require(old.incoming.isNotEmpty() && !old.blocked)
        applyFriend(
            peer,
            sendAction(PrivateAction(type = "friend.accept", request = old.incoming, peer = peer), listOf(peer)),
            true,
        )
        retryDeferred()
        refresh()
    }
    override fun removeFriend(peer: String, block: Boolean) = action {
        require(validKey(peer))
        applyFriend(
            peer,
            sendAction(
                PrivateAction(type = "friend.remove", peer = peer, request = friend(peer).incoming),
                listOf(peer),
            ),
            true,
        )
        setPrivateFlag("blocked", peer, block)
        refresh()
    }
    fun unblock(peer: String) = action {
        setPrivateFlag("blocked", peer, false)
        refresh()
    }
    private fun applyFriend(peer: String, action: PrivateAction, self: Boolean) {
        val ledger = (store.read<FriendLedger>("friend-ledger", peer) ?: FriendLedger()).apply(action, self)
        store.save("friend-ledger", peer, ledger)
        store.save("friends", peer, ledger.state(peer, friend(peer).blocked))
    }
    private fun friend(peer: String) = store.read<FriendState>("friends", peer) ?: FriendState(peer)

    fun sendChat(conversation: String, text: String) = action {
        require(text.isNotBlank() && text.length <= 4000) { "Scrivi un messaggio fino a 4000 caratteri" }
        val id = requireNotNull(identity)
        val group = store.read<PrivateGroup>("groups", conversation)
        val peers = if (group != null) {
            require(!group.closed && id.publicKey in group.members)
            require(
                group.members.none {
                    friend(it).blocked
                },
            ) { "Rimuovi dal gruppo i profili bloccati o lascia il gruppo prima di inviare." }
            group.members
        } else {
            require(friend(conversation).accepted && !friend(conversation).blocked) { "Accetta prima l’amicizia" }
            listOf(conversation, id.publicKey)
        }
        val tags = peers.filter { it != id.publicKey }.map { listOf("p", it) }.toMutableList()
        if (group !=
            null
        ) {
            tags.add(listOf("nyanime-group", group.id, group.revision.toString(), group.fingerprint()))
            tags.add(listOf("subject", group.name))
        }
        val rumor = GiftWrap.rumor(id.publicKey, 14, text, tags)
        peers.forEach { transport?.addDestinations(store.read<List<String>>("inbox-relays", it).orEmpty(), it) }
        val envelopes = peers.distinct().map { GiftWrap.wrap(id, it, rumor) }
        store.transaction { envelopes.forEach { store.enqueue(it, priority = 2) } }
        ingestChat(rumor)
        refresh()
    }
    fun createGroup(name: String, peers: List<String>, image: String = "") = action {
        val me = requireNotNull(identity).publicKey
        require(peers.all { friend(it).accepted && !friend(it).blocked }) { "Puoi invitare gli amici accettati" }
        val group =
            PrivateGroup(
                UUID.randomUUID().toString().replace("-", ""),
                me,
                name.trim(),
                (peers + me).distinct(),
                revision = nextGroupRevision(),
                image = image,
            )
        require(group.valid()) { "Scegli un nome e da 1 a 9 amici" }
        store.save("groups", group.id, group)
        store.save("group-heads", group.id, group)
        store.save("group-rosters", group.versionKey(), group)
        sendAction(PrivateAction(type = "group.update", group = group), group.members)
        refresh()
    }
    fun updateGroup(group: PrivateGroup) = action {
        val previous = requireNotNull(store.read<PrivateGroup>("groups", group.id))
        require(previous.owner == identity?.publicKey && group.owner == previous.owner)
        val next = group.copy(revision = nextGroupRevision(previous.revision), closed = group.members.size < 2)
        require(
            next.valid() &&
                next.members.filter {
                    it !in previous.members
                }.all { friend(it).accepted && !friend(it).blocked },
        ) { "Gruppo non valido" }
        if (next.closed) store.remove("groups", next.id) else store.save("groups", next.id, next)
        store.save("group-heads", next.id, next)
        store.save("group-rosters", next.versionKey(), next)
        sendAction(PrivateAction(type = "group.update", group = next), (previous.members + next.members).distinct())
        refresh()
    }
    fun leaveGroup(id: String) = action {
        val group = requireNotNull(store.read<PrivateGroup>("groups", id))
        if (group.owner == identity?.publicKey) {
            val closed = group.copy(
                members = listOf(group.owner),
                closed = true,
                revision = nextGroupRevision(group.revision),
            )
            store.save("group-heads", id, closed)
            store.remove("groups", id)
            sendAction(PrivateAction(type = "group.update", group = closed), group.members)
            refresh()
            return@action
        }
        sendAction(PrivateAction(type = "group.leave", peer = id), listOf(group.owner))
        setPrivateFlag("left", id, true)
        store.remove("groups", id)
        refresh()
    }
    fun acceptGroup(id: String) = action {
        val group = requireNotNull(store.read<PrivateGroup>("group-invites", id))
        require(!group.closed && identity?.publicKey in group.members)
        setPrivateFlag("left", id, false)
        setPrivateFlag("joined", id, true)
        store.save("groups", id, group)
        store.remove("group-invites", id)
        refresh()
    }
    fun declineGroup(id: String) = action {
        val group = requireNotNull(store.read<PrivateGroup>("group-invites", id))
        sendAction(PrivateAction(type = "group.leave", peer = id), listOf(group.owner))
        setPrivateFlag("left", id, true)
        store.remove("group-invites", id)
        refresh()
    }
    fun mute(conversation: String) = action {
        setPrivateFlag("muted", conversation, !(store.read<Boolean>("muted", conversation) ?: false))
        refresh()
    }
    fun invite(peerOrGroup: String, code: String) = action {
        eu.kanade.tachiyomi.data.watch.WatchInvite.parse(code, System.currentTimeMillis())
        val group = store.read<PrivateGroup>("groups", peerOrGroup)
        val peers = group?.members ?: listOf(peerOrGroup).also { require(friend(peerOrGroup).accepted) }
        sendAction(
            PrivateAction(
                type = "watch.invite",
                peer = peerOrGroup,
                body = code,
                expires =
                System.currentTimeMillis() + 600_000,
            ),
            peers,
        )
        refresh()
    }
    fun setPresence(access: PresenceAccess) = action {
        if (state.value.presenceAccess != access) publishPresence(clear = true)
        store.save("settings", "presence", access)
        mutable.update { it.copy(presenceAccess = access) }
        publishPresence()
    }

    /** Called with existing view-model data only. Never samples the released native player. */
    fun updateActivity(owner: Any, ref: SyncReference, title: String, item: String) = action {
        if (identity == null || preferences.incognitoMode().get() || !ref.valid()) return@action
        val changed = lastActivity?.ref != ref || activityOwner.get() !== owner
        val token = if (changed) {
            UUID.randomUUID().toString().replace(
                "-",
                "",
            )
        } else {
            requireNotNull(lastActivity).public.token
        }
        activityOwner = java.lang.ref.WeakReference(owner)
        lastActivity =
            LocalActivity(
                ref,
                SocialActivity(token, title.take(240), item.take(240), ref.manga),
                System.currentTimeMillis(),
            )
        if (changed) publishPresence()
    }
    fun clearActivity(owner: Any) = action {
        if (activityOwner.get() !== owner) return@action
        activityOwner.clear()
        lastActivity = lastActivity?.copy(at = System.currentTimeMillis())
        publishPresence()
    }
    private fun publishPresence(clear: Boolean = false) {
        if (personalOnly) return
        if (identity == null ||
            preferences.incognitoMode().get() ||
            state.value.presenceAccess == PresenceAccess.Private
        ) {
            return
        }
        val activity = lastActivity?.takeIf { activityOwner.get() != null && foreground && !clear }?.let {
            lastActivity = it.copy(at = System.currentTimeMillis())
            it.public
        }
        val text = if (clear) {
            ""
        } else {
            activity?.let { (if (it.manga) "Sta leggendo: " else "Sta guardando: ") + it.title }
                ?: "Online"
        }
        val presence = SocialPresence(text.take(240), System.currentTimeMillis() + 90_000, activity)
        if (state.value.presenceAccess == PresenceAccess.Public) {
            enqueuePublic(
                30315,
                presence.text,
                listOf(
                    listOf("d", "general"),
                    listOf("t", "nyanime"),
                    listOf(
                        "expiration",
                        (
                            presence.expires /
                                1000
                            ).toString(),
                    ),
                ) +
                    if (activity !=
                        null
                    ) {
                        listOf(listOf("nyanime-activity", communityJson.encodeToString(activity)))
                    } else {
                        emptyList()
                    },
                "presence",
            )
        } else {
            val peers = state.value.friends.filter { it.accepted && !it.blocked }.map { it.peer }
            if (peers.isNotEmpty()) {
                sendAction(
                    PrivateAction(
                        type = "presence",
                        body = communityJson.encodeToString(presence),
                        expires = presence.expires,
                    ),
                    peers,
                )
            }
        }
    }
    fun requestWatch(peer: String) = action {
        val me = requireNotNull(identity).publicKey
        require(friend(peer).accepted && !friend(peer).blocked) { "Aggiungi prima questa persona agli amici" }
        val now = System.currentTimeMillis()
        val presence = requireNotNull(state.value.presence[peer]) { "Questa attività non è più disponibile" }
        val activity = requireNotNull(presence.activity) { "Questo amico non sta guardando un episodio" }
        require(presence.expires > now && activity.valid() && !activity.manga) {
            "Questa attività non è più disponibile"
        }
        require(
            store.list<SocialWatchRequest>("watch-requests").none {
                it.requester == me && it.host == peer && it.pending(now)
            },
        ) { "Hai già inviato un invito: attendi la risposta" }
        val request = SocialWatchRequest(
            UUID.randomUUID().toString().replace("-", ""),
            me,
            peer,
            activity,
            now + 180_000,
        )
        store.transaction {
            saveWatchRequest(request, now / 1000)
            sendAction(
                PrivateAction(
                    type = "watch.request",
                    peer = peer,
                    body = communityJson.encodeToString(request),
                    expires = request.expires,
                ),
                listOf(peer),
            )
        }
        refresh()
    }
    fun respondWatch(id: String, accept: Boolean, activity: android.app.Activity) = action {
        val request = requireNotNull(store.read<SocialWatchRequest>("watch-requests", id))
        val me = requireNotNull(identity).publicKey
        require(request.pending(System.currentTimeMillis()) && me in listOf(request.host, request.requester)) {
            "Questo invito è scaduto"
        }
        val peer = if (me == request.host) request.requester else request.host
        require(friend(peer).accepted && !friend(peer).blocked) { "Questa amicizia non è più disponibile" }
        var code = ""
        val status = if (me == request.requester) {
            require(!accept)
            WatchRequestStatus.Cancelled
        } else if (!accept) {
            WatchRequestStatus.Declined
        } else {
            require(!preferences.incognitoMode().get()) { "Esci dalla modalità incognito prima di entrare in stanza" }
            val current = requireNotNull(lastActivity) { "Riapri l’episodio per creare una stanza" }
            require(
                current.public.token == request.activity.token &&
                    System.currentTimeMillis() - current.at < 180_000 &&
                    !current.ref.manga,
            ) {
                "L’episodio è cambiato. Apri quello desiderato e chiedi un nuovo invito."
            }
            val ids = requireNotNull(library.videoIds(current.ref)) { "L’episodio non è più presente sul dispositivo" }
            code = withContext(Dispatchers.Main) {
                require(!activity.isFinishing && !activity.isDestroyed) { "Riapri la chat per accettare" }
                val cast = eu.kanade.tachiyomi.data.cast.CastController.get(context).state.value
                require(!cast.active && !cast.connecting) { "Termina prima la trasmissione alla TV" }
                val rooms = eu.kanade.tachiyomi.data.watch.WatchTogetherManager.get(context)
                require(!rooms.controller.active) {
                    "Sei già in una stanza. Puoi invitare l’amico dalle opzioni della chat."
                }
                rooms.present(activity)
                rooms.createRoom(state.value.me?.name.orEmpty())
                require(rooms.controller.active) { "Non riesco a creare la stanza. Riprova." }
                try {
                    activity.startActivity(
                        eu.kanade.tachiyomi.ui.player.PlayerActivity.newIntent(activity, ids.first, ids.second),
                    )
                    rooms.controller.state.value.invite
                } catch (error: Exception) {
                    rooms.controller.leave()
                    throw error
                }
            }
            WatchRequestStatus.Accepted
        }
        val response = request.copy(status = status, invite = code)
        store.transaction {
            store.save("watch-requests", id, response)
            sendAction(
                PrivateAction(
                    type = "watch.response",
                    peer = peer,
                    body = communityJson.encodeToString(response),
                    expires = request.expires,
                ),
                listOf(peer),
            )
        }
        refresh()
    }
    private fun saveWatchRequest(request: SocialWatchRequest, at: Long) {
        val me = requireNotNull(identity).publicKey
        val conversation = if (me == request.requester) request.host else request.requester
        store.save("watch-requests", request.id, request)
        store.save(
            "chats",
            "watch:${request.id}",
            ChatItem(
                "watch:${request.id}",
                request.requester,
                conversation,
                "Guardiamo insieme ${request.activity.title}?",
                at,
                watchRequest = request.id,
            ),
            at * 1000,
        )
    }
    fun enterWatch(id: String, activity: android.app.Activity) = action {
        val request = requireNotNull(store.read<SocialWatchRequest>("watch-requests", id))
        val me = requireNotNull(identity).publicKey
        require(
            request.status == WatchRequestStatus.Accepted &&
                request.expires > System.currentTimeMillis() &&
                me in listOf(request.requester, request.host),
        ) { "Questo invito è scaduto" }
        val peer = if (me == request.host) request.requester else request.host
        require(friend(peer).accepted && !friend(peer).blocked) { "Questa amicizia non è più disponibile" }
        eu.kanade.tachiyomi.data.watch.WatchInvite.parse(request.invite, System.currentTimeMillis())
        withContext(Dispatchers.Main) {
            require(!activity.isFinishing && !activity.isDestroyed) { "Riapri la chat per entrare" }
            val cast = eu.kanade.tachiyomi.data.cast.CastController.get(context).state.value
            require(!cast.active && !cast.connecting) { "Termina prima la trasmissione alla TV" }
            val rooms = eu.kanade.tachiyomi.data.watch.WatchTogetherManager.get(context)
            require(!rooms.controller.active || rooms.controller.state.value.invite == request.invite) {
                "Sei già in un’altra stanza"
            }
            require(me != request.host || rooms.controller.active) {
                "Questa stanza è stata chiusa. Create un nuovo invito."
            }
            rooms.present(activity)
            if (!rooms.controller.active) rooms.controller.join(request.invite, state.value.me?.name.orEmpty())
            require(rooms.controller.active) { "Non riesco a entrare. Riprova." }
            activity.startActivity(Intent(activity, eu.kanade.tachiyomi.ui.watch.WatchTogetherActivity::class.java))
        }
    }
    private fun nextRevision(now: Long = System.currentTimeMillis()): SyncRevision {
        revision = revision.next(now, device)
        store.save("settings", "clock", revision)
        return revision
    }
    private fun nextGroupRevision(previous: Long = 0): Long = maxOf(
        previous + 1,
        nextRevision().millis * 1000 + revision.counter,
    )
    private suspend fun drain() {
        if (!state.value.syncEnabled || preferences.incognitoMode().get()) return
        library.drain(persist = { captured ->
            val record = canonical(captured)
            require(record.valid(System.currentTimeMillis())) {
                "Un contenuto supera i limiti di sincronizzazione. La modifica resta salvata in coda."
            }
            val id = requireNotNull(identity)
            store.transaction {
                val merged = SyncMerge.merge(store.read("sync", record.ref.key()), record)
                store.save("sync", record.ref.key(), merged)
                updateResume(merged)
                val address = "nyanime.sync.v1:" +
                    device +
                    ":" +
                    Nip44.hmac(id.conversationKey(id.publicKey), record.ref.key().toByteArray()).hex()
                enqueuePublic(
                    30078,
                    Nip44.encrypt(id.conversationKey(id.publicKey), communityJson.encodeToString(merged)),
                    listOf(listOf("d", address)),
                    address,
                    priority = PersonalSyncPolicy.deliveryPriority(record, System.currentTimeMillis()),
                )
                if (!store.contains("sync-addresses", address)) {
                    store.save("sync-addresses", address, true)
                    store.save("settings", "checkpoint-dirty", true)
                }
            }
        }, revision = ::nextRevision)
        updateDeliveryState()
    }
    private fun publishCheckpoint() {
        if (store.read<Boolean>("settings", "checkpoint-dirty") != true) return
        val id = requireNotNull(identity)
        val key = id.conversationKey(id.publicKey)
        store.transaction {
            val root = SyncCheckpoint.build(store.syncAddresses(), key) { address, node ->
                if (!store.contains("sync-nodes", address)) {
                    enqueuePublic(
                        30078,
                        Nip44.encrypt(key, communityJson.encodeToString(node)),
                        listOf(listOf("d", address)),
                        address,
                        priority = 0,
                    )
                    store.save("sync-nodes", address, node)
                }
            }
            if (root != null) {
                val address = "nyanime.sync.checkpoint.v1:$device"
                enqueuePublic(
                    30078,
                    Nip44.encrypt(key, communityJson.encodeToString(root)),
                    listOf(listOf("d", address), listOf("t", "nyanime-private-checkpoint")),
                    address,
                )
                store.save("sync-checkpoints", address, root)
            }
            store.save("settings", "checkpoint-dirty", false)
        }
        key.fill(0)
    }
    private fun scheduleRecovery(node: SyncCheckpoint, depth: Int = 0) {
        require(node.valid() && depth < 8) { "Indice di recupero non valido" }
        node.entries.forEach { address ->
            val cached = if (node.leaf) null else store.read<SyncCheckpoint>("sync-nodes", address)
            if (cached != null) {
                scheduleRecovery(cached, depth + 1)
            } else if (!store.contains("sync-needed", address)) {
                store.save("sync-needed", address, true)
            }
        }
    }
    private fun setPrivateFlag(bucket: String, key: String, value: Boolean) {
        val command =
            sendAction(
                PrivateAction(type = "preference", peer = "$bucket:$key", body = value.toString()),
                listOf(requireNotNull(identity).publicKey),
            )
        applyPrivateFlag(command)
    }
    private fun applyPrivateFlag(action: PrivateAction) {
        val bucket = action.peer.substringBefore(':')
        val key = action.peer.substringAfter(':')
        if (bucket !in listOf("blocked", "left", "muted", "joined") ||
            key.length !in 32..64 ||
            action.body !in listOf("true", "false")
        ) {
            return
        }
        val old = store.read<Long>("preference-revision", action.peer) ?: 0
        if (action.revision <= old) return
        val value = action.body == "true"
        store.save("preference-revision", action.peer, action.revision)
        store.save(bucket, key, value)
        if (bucket == "blocked") store.save("friends", key, friend(key).copy(blocked = value))
        if (bucket == "left" && value) store.remove("groups", key)
        if (bucket == "joined" && value && store.read<Boolean>("left", key) != true) {
            store.read<PrivateGroup>("group-heads", key)?.takeIf {
                !it.closed && identity?.publicKey in it.members
            }?.let {
                store.save("groups", key, it)
                store.remove("group-invites", key)
            }
        }
    }
    private fun defer(rumor: NostrEvent) {
        store.save("deferred", rumor.id, rumor)
        store.trim("deferred", 2000)
    }
    private suspend fun retryDeferred() {
        store.list<NostrEvent>("deferred", 2000).forEach { rumor ->
            if (rumor.kind == 14) ingestChat(rumor) else ingestAction(rumor)
            if (store.seen(rumor.id)) store.remove("deferred", rumor.id)
        }
    }
    private suspend fun ingest(event: NostrEvent) {
        if (!event.valid()) return
        val id = identity ?: return
        if (personalOnly && !PersonalSyncPolicy.event(event, id.publicKey)) return
        if (store.seen(event.id)) {
            if (event.kind == 30078 &&
                event.pubkey == id.publicKey
            ) {
                event.tag("d")?.let { store.remove("sync-needed", it) }
            }
            return
        }
        if (event.kind != 1059 && friend(event.pubkey).blocked) return
        when (event.kind) {
            10050 -> {
                val urls = event.values("relay").filter(CommunityRelays::validRelay).distinct().take(5)
                if (urls.isNotEmpty() && newer(event, "inbox:${event.pubkey}")) {
                    store.save("inbox-relays", event.pubkey, urls)
                    if (friend(event.pubkey).accepted ||
                        friend(event.pubkey).outgoing.isNotEmpty()
                    ) {
                        transport?.addDestinations(urls, event.pubkey)
                    }
                }
            }
            0 -> {
                val metadata =
                    runCatching { communityJson.parseToJsonElement(event.content).jsonObject }.getOrNull() ?: return
                val old = store.read<CommunityProfile>("profiles", event.pubkey)
                val profile = (old ?: CommunityProfile(event.pubkey)).copy(
                    name = (metadata["display_name"] ?: metadata["name"])?.jsonPrimitive?.content?.take(
                        40,
                    ).orEmpty().ifBlank {
                        event.pubkey.take(8)
                    },
                    bio = metadata["about"]?.jsonPrimitive?.content?.take(800).orEmpty(),
                    avatar = metadata["picture"]?.jsonPrimitive?.content.orEmpty().takeIf(::safeImage).orEmpty(),
                    banner = metadata["banner"]?.jsonPrimitive?.content.orEmpty().takeIf(::safeImage).orEmpty(),
                )
                if (newer(event, "metadata:" + event.pubkey)) store.save("profiles", event.pubkey, profile)
            }
            30078 -> {
                val address = event.tag("d").orEmpty()
                when {
                    event.pubkey == id.publicKey &&
                        (
                            address.startsWith("nyanime.sync.index.v1:") ||
                                address.startsWith("nyanime.sync.checkpoint.v1:")
                            ) -> {
                        if (!state.value.syncEnabled || preferences.incognitoMode().get()) {
                            store.save("deferred-sync", event.id, event)
                            return
                        }
                        val key = id.conversationKey(id.publicKey)
                        val node = communityJson.decodeFromString<SyncCheckpoint>(Nip44.decrypt(key, event.content))
                        require(node.valid())
                        val root = address.startsWith("nyanime.sync.checkpoint.v1:")
                        if (root) {
                            if (newer(event, address)) {
                                store.save("sync-checkpoints", address, node)
                                scheduleRecovery(node)
                            }
                        } else {
                            require(node.address(key) == address)
                            store.save("sync-nodes", address, node)
                            scheduleRecovery(node)
                        }
                        key.fill(0)
                        store.remove("sync-needed", address)
                    }
                    address == "nyanime.profile.v1" -> {
                        val profile = communityJson.decodeFromString<CommunityProfile>(event.content)
                        if (profile.key == event.pubkey &&
                            profile.valid() &&
                            newer(event, "showcase:" + event.pubkey)
                        ) {
                            store.save("profiles", profile.key, profile)
                        }
                    }
                    address.startsWith(
                        "nyanime.sync.v1:",
                    ) &&
                        event.pubkey == id.publicKey -> {
                        if (!state.value.syncEnabled || preferences.incognitoMode().get()) {
                            store.save("deferred-sync", event.id, event)
                            return
                        }
                        drain()
                        val record = communityJson.decodeFromString<SyncRecord>(
                            Nip44.decrypt(id.conversationKey(id.publicKey), event.content),
                        )
                        if (!record.valid(System.currentTimeMillis())) return
                        store.remove("sync-needed", address)
                        val old = store.read<SyncRecord>("sync", record.ref.key())
                        val merged = SyncMerge.merge(old, record)
                        if (merged != old) {
                            val applied = library.apply(mapped(merged))
                            store.save("sync", record.ref.key(), merged)
                            updateResume(merged)
                            if (applied) {
                                store.remove(
                                    "unresolved",
                                    record.ref.key(),
                                )
                            } else {
                                store.save("unresolved", record.ref.key(), merged)
                            }
                            if (record.revision >
                                revision
                            ) {
                                revision = record.revision
                                store.save("settings", "clock", revision)
                            }
                        }
                    }
                    address in listOf("nyanime.pinned.v1", "nyanime.hidden.v1") -> {
                        val values = communityJson.decodeFromString<Set<String>>(event.content)
                        if (values.size <= 500 && values.all(::validKey) && newer(event, address + event.pubkey)) {
                            store.save("wall-moderation", event.pubkey + address, values)
                        }
                    }
                    address.startsWith("nyanime.wall.admit.v1:") -> {
                        if (validKey(event.content) &&
                            address.substringAfterLast(':') == event.content
                        ) {
                            store.save("admitted", "${event.pubkey}:${event.content}", true)
                        }
                    }
                }
            }
            1 -> if (event.values("t").contains("nyanime")) {
                val extras =
                    event.tag("nyanime")?.let { communityJson.decodeFromString<SocialPost>(it) } ?: SocialPost()
                val post = extras.copy(
                    text = event.content,
                    spoiler =
                    event.tag("content-warning") != null || extras.spoiler,
                )
                val wall = event.tag("wall").orEmpty()
                val reply = event.tag("e").orEmpty()
                if (post.valid() &&
                    (wall.isEmpty() || validKey(wall)) &&
                    (reply.isEmpty() || validKey(reply))
                ) {
                    store.save(
                        "posts",
                        event.id,
                        CommunityItem(event.id, event.pubkey, event.created_at, post, wall, reply),
                        event.created_at * 1000,
                    )
                }
            }
            7 -> {
                val target = event.tag("e") ?: return
                if (validKey(target) &&
                    newer(event, "reaction:$target:${event.pubkey}")
                ) {
                    store.save(
                        "reactions",
                        "$target:${event.pubkey}",
                        event.content == "+",
                    )
                }
            }
            30315 -> {
                val expiry = event.tag("expiration")?.toLongOrNull()?.times(1000) ?: return
                if (expiry in System.currentTimeMillis()..System.currentTimeMillis() + 120_000 &&
                    event.content.length <= 240 &&
                    newer(event, "presence:${event.pubkey}")
                ) {
                    val activity = event.tag("nyanime-activity")?.takeIf { it.length <= 2000 }?.let {
                        runCatching {
                            communityJson.decodeFromString<SocialActivity>(it)
                        }.getOrNull()?.takeIf(SocialActivity::valid)
                    }
                    store.save("presence", event.pubkey, SocialPresence(event.content, expiry, activity))
                }
            }
            1059 -> {
                val rumor = GiftWrap.open(id, event)
                if (personalOnly && (rumor.pubkey != id.publicKey || rumor.kind != 30079)) return
                if (friend(rumor.pubkey).blocked) return
                if (rumor.kind == 14) {
                    ingestChat(rumor)
                } else if (rumor.kind == 30079) {
                    ingestAction(rumor)
                }
            }
        }
        store.markSeen(event.id)
    }
    private fun newer(event: NostrEvent, address: String): Boolean {
        val old = store.read<Pair<Long, String>>("winners", address)
        if (old != null &&
            (event.created_at < old.first || event.created_at == old.first && event.id >= old.second)
        ) {
            return false
        }
        store.save("winners", address, event.created_at to event.id)
        return true
    }
    private fun ingestChat(rumor: NostrEvent) {
        val me = requireNotNull(identity).publicKey
        if (rumor.content.isBlank() || rumor.content.length > 4000 || store.seen(rumor.id)) return
        val groupTag = rumor.tags.firstOrNull { it.firstOrNull() == "nyanime-group" }
        val conversation = if (groupTag != null) {
            val versionKey = groupTag.drop(1).joinToString(":")
            val group =
                store.read<PrivateGroup>("group-rosters", versionKey) ?: run {
                    defer(rumor)
                    return
                }
            if (!GroupPolicy.accepts(group, rumor, me)) return
            group.id
        } else {
            val members = (rumor.values("p") + rumor.pubkey).distinct()
            if (members.size != 2 || me !in members) return
            val peer = members.first { it != me }
            if (friend(peer).blocked) return
            if (!friend(peer).accepted) {
                defer(rumor)
                return
            }
            peer
        }
        store.save(
            "chats",
            rumor.id,
            ChatItem(rumor.id, rumor.pubkey, conversation, rumor.content, rumor.created_at),
            rumor.created_at * 1000,
        )
        if (rumor.pubkey != me &&
            rumor.created_at > System.currentTimeMillis() / 1000 - 120 &&
            store.read<Boolean>("muted", conversation) != true &&
            !preferences.incognitoMode().get()
        ) {
            notifications.message(
                conversation,
                store.read<PrivateGroup>("groups", conversation)?.name ?: state.value.profile(rumor.pubkey).name,
            )
        }
        store.markSeen(rumor.id)
    }
    private suspend fun ingestAction(rumor: NostrEvent) {
        if (store.seen(rumor.id)) return
        val me = requireNotNull(identity).publicKey
        val action = communityJson.decodeFromString<PrivateAction>(rumor.content)
        if (action.version != 1 ||
            action.body.length > 30_000 ||
            action.expires != 0L &&
            action.expires < System.currentTimeMillis()
        ) {
            return
        }
        val self = rumor.pubkey == me
        if (personalOnly &&
            (
                !self ||
                    !state.value.syncEnabled ||
                    preferences.incognitoMode().get() ||
                    !PersonalSyncPolicy.command(action.type)
                )
        ) {
            return
        }
        if (self && action.revision > 0 && action.revision / 1000 <= System.currentTimeMillis() + 300_000) {
            val remoteClock = SyncRevision(action.revision / 1000, (action.revision % 1000).toInt(), device)
            if (remoteClock > revision) {
                revision = remoteClock
                store.save("settings", "clock", revision)
            }
        }
        val peer = if (self) action.peer else rumor.pubkey
        when (action.type) {
            "friend.request", "friend.accept", "friend.remove" -> {
                if (!validKey(peer) || peer == me || !self && action.peer != me) return
                applyFriend(peer, action, self)
                transport?.query(peer)
                if (!self && action.type != "friend.remove") {
                    notifyPrivate(
                        rumor,
                        peer,
                        if (action.type ==
                            "friend.request"
                        ) {
                            "Vorrebbe aggiungerti agli amici"
                        } else {
                            "Ha accettato la tua amicizia"
                        },
                    )
                }
            }
            "group.update" -> {
                val group = action.group ?: return
                val previous = store.read<PrivateGroup>("group-heads", group.id)
                if (!group.valid() || group.owner != rumor.pubkey) return
                if (!self && previous == null && !friend(rumor.pubkey).accepted) {
                    defer(rumor)
                    return
                }
                if (previous != null && previous.owner != group.owner) return
                if (me in group.members) store.save("group-rosters", group.versionKey(), group)
                if (!GroupPolicy.newer(previous, group)) {
                    store.markSeen(rumor.id)
                    return
                }
                store.save("group-heads", group.id, group)
                if (!group.closed &&
                    me in group.members &&
                    store.read<Boolean>("left", group.id) != true
                ) {
                    if (self || store.contains("groups", group.id) || store.read<Boolean>("joined", group.id) == true) {
                        store.save("groups", group.id, group)
                        store.remove("group-invites", group.id)
                    } else {
                        store.save("group-invites", group.id, group)
                        if (previous == null) notifyPrivate(rumor, group.id, "Ti invita in un gruppo privato")
                    }
                } else {
                    store.remove("groups", group.id)
                    store.remove("group-invites", group.id)
                }
            }
            "group.leave" -> {
                val group = store.read<PrivateGroup>("groups", action.peer) ?: return
                if (group.owner != me || rumor.pubkey !in group.members || rumor.pubkey == me) return
                val next = group.copy(
                    members = group.members - rumor.pubkey,
                    revision = nextGroupRevision(group.revision),
                    closed =
                    group.members.size <= 2,
                )
                store.save("group-heads", next.id, next)
                store.save("group-rosters", next.versionKey(), next)
                if (!next.closed) store.save("groups", next.id, next) else store.remove("groups", group.id)
                sendAction(PrivateAction(type = "group.update", group = next), group.members)
            }
            "watch.invite" -> {
                if (!self && !friend(rumor.pubkey).accepted) return
                runCatching {
                    eu.kanade.tachiyomi.data.watch.WatchInvite.parse(action.body, System.currentTimeMillis())
                }.getOrNull()
                    ?: return
                val conversation = if (store.read<PrivateGroup>("groups", action.peer) !=
                    null
                ) {
                    action.peer
                } else if (self) {
                    action.peer
                } else {
                    rumor.pubkey
                }
                store.save(
                    "chats",
                    rumor.id,
                    ChatItem(
                        rumor.id,
                        rumor.pubkey,
                        conversation,
                        "Guardiamo qualcosa insieme?",
                        rumor.created_at,
                        action.body,
                        action.expires,
                    ),
                )
                if (!self) notifyPrivate(rumor, conversation, "Ti invita a guardare qualcosa insieme")
            }
            "watch.request" -> {
                val request = communityJson.decodeFromString<SocialWatchRequest>(action.body)
                if (!request.valid(System.currentTimeMillis()) ||
                    request.requester != rumor.pubkey ||
                    request.status != WatchRequestStatus.Pending ||
                    request.invite.isNotEmpty() ||
                    me !in listOf(request.requester, request.host)
                ) {
                    return
                }
                val other = if (self) request.host else request.requester
                if (!friend(other).accepted ||
                    friend(other).blocked ||
                    store.contains("watch-requests", request.id)
                ) {
                    return
                }
                saveWatchRequest(request, rumor.created_at)
                if (!self) notifyPrivate(rumor, other, "Vorrebbe guardare questo episodio insieme a te")
            }
            "watch.response" -> {
                val response = communityJson.decodeFromString<SocialWatchRequest>(action.body)
                val previous =
                    store.read<SocialWatchRequest>("watch-requests", response.id) ?: run {
                        defer(rumor)
                        return
                    }
                if (!previous.acceptsResponse(response, rumor.pubkey, System.currentTimeMillis())) return
                val other = if (me == response.host) response.requester else response.host
                if (!friend(other).accepted || friend(other).blocked) return
                store.save("watch-requests", response.id, response)
                if (!self) {
                    notifyPrivate(
                        rumor,
                        other,
                        if (response.status == WatchRequestStatus.Accepted) {
                            "Ha accettato: la vostra stanza è pronta"
                        } else {
                            "Ha risposto al tuo invito"
                        },
                    )
                }
            }
            "presence" -> if (!self && friend(peer).accepted) {
                val presence = communityJson.decodeFromString<SocialPresence>(action.body)
                if (presence.text.length <= 240 &&
                    presence.expires in System.currentTimeMillis()..System.currentTimeMillis() + 120_000 &&
                    presence.activity?.valid() != false &&
                    presence.expires > (store.read<SocialPresence>("presence", peer)?.expires ?: 0)
                ) {
                    store.save("presence", peer, presence)
                }
            }
            "preference" -> if (self) applyPrivateFlag(action)
            "device.offer", "device.ready", "device.take", "device.ack", "device.presence" -> if (self) {
                handoff.receive(
                    action,
                )
            }
        }
        store.markSeen(rumor.id)
    }

    /** Small materialized view: never decrypt the complete library on every playback tick. */
    private fun updateResume(record: SyncRecord) {
        if (!personalOnly) return
        val key = record.ref.copy(itemUrl = "").key()
        val previous = store.read<SyncRecord>("resume", key)
        if (record.deleted && record.ref.itemUrl.isEmpty()) {
            store.remove("resume", key)
        } else if (previous?.ref == record.ref && (record.history == 0L || record.seen || record.deleted)) {
            store.remove("resume", key)
        } else if (record.ref.itemUrl.isNotEmpty() &&
            record.history > 0 &&
            (previous == null || record.history >= previous.history || record.ref == previous.ref)
        ) {
            if (record.seen || record.deleted) {
                if (previous?.ref == record.ref) store.remove("resume", key)
            } else {
                store.save("resume", key, record)
            }
        }
    }

    private suspend fun refresh() {
        updateDeliveryState()
        val now = android.os.SystemClock.elapsedRealtime()
        if (state.value.ready && now - refreshedAt < 250) return
        refreshedAt = now
        if (personalOnly) {
            if (store.count("unresolved") > 0 && now - libraryAt >= 5000) {
                libraryAt = now
                val titles = library.library()
                mutable.update { it.copy(library = titles) }
            }
            mutable.update {
                it.copy(
                    loading = false,
                    me = identity?.let { key -> CommunityProfile(key.publicKey, "I miei dispositivi") },
                    recent = PersonalSyncPolicy.resume(store.list("resume", 50)),
                    unresolved = store.list("unresolved", 100),
                )
            }
            return
        }
        if (foreground && now - libraryAt >= 5000) {
            libraryAt = now
            val titles = library.library()
            mutable.update { it.copy(library = titles) }
        }
        if (refreshedGeneration == store.generation && state.value.ready) {
            mutable.update { current ->
                current.copy(
                    presence = current.presence.filterValues {
                        it.expires >
                            System.currentTimeMillis()
                    },
                )
            }
            return
        }
        val me = identity?.publicKey
        val profiles = store.list<CommunityProfile>("profiles").associateBy { it.key }.toMutableMap().apply {
            me?.let { key -> store.read<CommunityProfile>("profiles", key)?.let { put(key, it) } }
        }
        val friends = store.list<FriendState>("friends")
        val blocked = friends.filter { it.blocked }.map { it.peer }.toSet()
        val hidden = store.read<Set<String>>("moderation", "hidden").orEmpty()
        val pinned = store.read<Set<String>>("moderation", "pinned").orEmpty()
        val reactions = store.entries("reactions", 5000).filter { it.second == "true" }.map { it.first }
        val ownProfile = me?.let { profiles[it] }
        if (me != null &&
            ownProfile != null
        ) {
            store.list<CommunityItem>("posts", postLimit).filter {
                it.wall == me && it.author !in blocked
            }.forEach { item ->
                val allowed =
                    item.author == me ||
                        ownProfile.wall == WallAccess.Everyone ||
                        ownProfile.wall == WallAccess.Friends &&
                        friend(item.author).accepted
                if (allowed && store.read<Boolean>("admitted", "$me:${item.id}") != true) {
                    val address = "nyanime.wall.admit.v1:${item.id}"
                    store.save("admitted", "$me:${item.id}", true)
                    enqueuePublic(30078, item.id, listOf(listOf("d", address), listOf("t", "nyanime")), address)
                }
            }
        }
        val posts = store.list<CommunityItem>("posts", postLimit).filter { item ->
            item.author !in blocked &&
                item.id !in hidden &&
                (
                    item.wall.isEmpty() ||
                        item.author == item.wall ||
                        item.author == me ||
                        store.read<Boolean>("admitted", "${item.wall}:${item.id}") == true
                    ) &&
                item.id !in store.read<Set<String>>("wall-moderation", item.wall + "nyanime.hidden.v1").orEmpty()
        }.map { item ->
            item.copy(
                likes = reactions.filter { it.startsWith(item.id + ":") }.map { it.substringAfter(':') }.toSet(),
                pinned =
                item.id in pinned ||
                    item.id in store.read<Set<String>>("wall-moderation", item.wall + "nyanime.pinned.v1").orEmpty(),
            )
        }
        mutable.update {
            it.copy(
                ready = true, loading = false,
                me = me?.let { key ->
                    profiles[key]
                        ?: CommunityProfile(key, key.take(8))
                },
                profiles = profiles, friends = friends, posts = posts,
                chats = store.list<ChatItem>("chats", 2000).filter { chat ->
                    chat.author !in
                        blocked
                },
                groups = store.list("groups"),
                groupInvites = store.list("group-invites"),
                watchRequests = store.list<SocialWatchRequest>("watch-requests", 1000).associateBy { it.id },
                presence = store.entries("presence").mapNotNull { (key, value) ->
                    runCatching {
                        key to
                            communityJson.decodeFromString<SocialPresence>(value)
                    }.getOrNull()
                }.filter {
                    it.second.expires > System.currentTimeMillis() &&
                        it.second.text.isNotBlank() &&
                        it.first !in blocked
                }.toMap(),
                pending = store.pendingCount(),
                muted = store.entries("muted").filter {
                    it.second == "true"
                }.map { it.first }.toSet(),
                unresolved = store.list("unresolved"),
                profileDraft = store.read("drafts", "profile"), postDraft = store.read("drafts", "post"),
            )
        }
        refreshedGeneration = store.generation
    }
    private fun notifyPrivate(rumor: NostrEvent, conversation: String, detail: String) {
        if (rumor.created_at < System.currentTimeMillis() / 1000 - 120 ||
            store.read<Boolean>("muted", conversation) == true ||
            preferences.incognitoMode().get()
        ) {
            return
        }
        notifications.message(conversation, state.value.profile(rumor.pubkey).name, detail)
    }
    suspend fun exportRecovery(password: CharArray): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val bytes = requireNotNull(identity).exportSecret()
            try {
                val protected = IdentityRecovery.export(bytes, password)
                if (personalOnly) "NYS1." + protected else protected
            } finally {
                bytes.fill(0)
                password.fill('\u0000')
            }
        }
    }
    fun restoreRecovery(encoded: String, password: CharArray) = action {
        require(identity == null) { "Questo dispositivo è già collegato" }
        val secret = try {
            val value = encoded.trim()
            require(!personalOnly || value.startsWith("NYS1.")) { "Scegli il file di recupero di I miei dispositivi." }
            IdentityRecovery.restore(if (personalOnly) value.removePrefix("NYS1.") else value, password)
        } finally {
            password.fill('\u0000')
        }
        try {
            adopt(secret)
        } finally {
            secret.fill(0)
        }
    }
    internal suspend fun adopt(secret: ByteArray) {
        require(identity == null)
        val restored = CommunityIdentity(secret)
        vault.save(secret)
        identity = restored
        enablePersonal()
        refreshedGeneration = -1
        library.capture(state.value.syncEnabled && !preferences.incognitoMode().get())
        connect()
        refresh()
    }
    internal fun pairingSecret(): ByteArray = requireNotNull(identity).exportSecret()
    internal suspend fun stageImage(bytes: ByteArray): String = withContext(Dispatchers.IO) { imageDrafts.save(bytes) }

    private suspend fun prepareImage(value: String): String {
        val file = imageDrafts.file(value) ?: return value
        require(file.isFile) { "La foto della bozza non è più disponibile. Sceglila di nuovo." }
        return uploadPublicArtwork(file.inputStream().use { it.readBounded(2_000_000) })
    }

    internal suspend fun uploadPublicArtwork(bytes: ByteArray): String {
        var failure: Exception? = null
        for (host in (listOf(preferredImageHost) + BlossomImages.hosts).distinct()) {
            try {
                return uploadImage(bytes, host).also { preferredImageHost = host }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                failure = error
            }
        }
        throw requireNotNull(failure)
    }
    internal suspend fun uploadImage(bytes: ByteArray, host: String): String {
        val hash = sha256(bytes).hex()
        val cached = mutex.withLock { store.read<String>("uploaded-images", "$host:$hash") }
        if (cached != null) return cached
        val key = mutex.withLock { requireNotNull(identity) }
        return BlossomImages.upload(key, bytes, host).also { url ->
            mutex.withLock { store.save("uploaded-images", "$host:$hash", url) }
        }
    }
    internal suspend fun resumeIds(record: SyncRecord): Pair<Long, Long>? = withContext(Dispatchers.IO) {
        mutex.withLock { library.itemIds(mapped(record).ref) }
    }
    internal fun linkedPairing() = DevicePairing(this, relays(), state.value.me != null)
    internal fun attachPlayer(adapter: DeviceHandoff.Player) = action {
        if (identity != null && state.value.syncEnabled && !preferences.incognitoMode().get()) handoff.attach(adapter)
    }
    companion object {
        @Volatile private var instance: CommunityManager? = null

        @Volatile private var personalInstance: CommunityManager? = null
        internal fun hasPersonalIdentity(
            context: Context,
        ) = File(context.noBackupFilesDir, PersonalSyncPolicy.VAULT).exists()
        internal fun existing(): CommunityManager? {
            if (BuildConfig.PERSONAL_SYNC_ENABLED) {
                personalInstance?.takeIf {
                    Injekt.get<BasePreferences>().personalSyncEnabled().get()
                }?.let { return it }
            }
            return if (BuildConfig.COMMUNITY_ENABLED) instance else null
        }

        /** Called only after an explicit setup action, or for a previously configured device. */
        internal fun personal(context: Context): CommunityManager {
            check(BuildConfig.PERSONAL_SYNC_ENABLED) { "Personal sync is dormant in this build" }
            return personalInstance ?: synchronized(this) {
                personalInstance
                    ?: CommunityManager(context.applicationContext, personalOnly = true).also { personalInstance = it }
            }
        }
        fun get(context: Context): CommunityManager {
            check(BuildConfig.COMMUNITY_ENABLED) { "Community is dormant in this build" }
            return instance
                ?: synchronized(this) {
                    instance ?: CommunityManager(context.applicationContext).also { instance = it }
                }
        }
        fun lifecycle(context: Context, foreground: Boolean) {
            if (BuildConfig.PERSONAL_SYNC_ENABLED) {
                if (Injekt.get<BasePreferences>().personalSyncEnabled().get()) {
                    personal(context).onForeground(foreground)
                } else {
                    personalInstance?.onForeground(foreground)
                }
            }
            if (!BuildConfig.COMMUNITY_ENABLED) return
            if (instance != null ||
                File(context.noBackupFilesDir, "community.identity").exists()
            ) {
                get(context).onForeground(foreground)
            }
        }
    }
}
