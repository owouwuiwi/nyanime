package it.lorelore.nyanime.studio

import android.content.Context
import android.util.AtomicFile
import eu.kanade.tachiyomi.data.rin.RinSessions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.io.File

@Serializable
data class StudioRecord(
    val draft: StudioSource,
    val active: StudioSource? = null,
    val previous: StudioSource? = null,
    val enabled: Boolean = false,
    val pending: Boolean = false,
    val trustedScriptFingerprint: String? = null,
    val activeScriptFingerprint: String? = null,
) {
    fun trusts(source: StudioSource): Boolean {
        val fingerprint = StudioDocuments.fingerprint(source)
        return fingerprint == trustedScriptFingerprint ||
            (active == source && fingerprint == activeScriptFingerprint)
    }

    /** Called only after the new snapshot's explicit script consent has been checked. */
    fun publishApproved(published: StudioSource, deferred: Boolean): StudioRecord {
        val fingerprint = StudioDocuments.fingerprint(published)
        return if (deferred) {
            copy(
                draft = published,
                pending = true,
                trustedScriptFingerprint = fingerprint,
                activeScriptFingerprint = active?.takeIf(::trusts)?.let(StudioDocuments::fingerprint),
            )
        } else {
            copy(
                draft = published,
                active = published,
                previous = active,
                enabled = true,
                pending = false,
                trustedScriptFingerprint = fingerprint,
                activeScriptFingerprint = fingerprint,
            )
        }
    }

    fun withDraft(source: StudioSource): StudioRecord =
        if (draft == source) this else copy(draft = source, pending = false)

    fun afterRestore(): StudioRecord = copy(
        enabled = enabled && !StudioBlueprints.hasScripts(draft) && active?.let(StudioBlueprints::hasScripts) != true,
        pending = false,
        trustedScriptFingerprint = null,
        activeScriptFingerprint = null,
        // A previous version was approved on another installation. Do not let rollback
        // turn that foreign approval into local permission to execute its scripts.
        previous = previous?.takeUnless(StudioBlueprints::hasScripts),
    )
}

/** A single atomic document owns drafts and published snapshots; sources never read a half edit. */
class SourceStudioRepository private constructor(context: Context) {
    private val context = context.applicationContext
    private val file = AtomicFile(File(context.filesDir, "source-studio-v1.json"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val ready = CompletableDeferred<Unit>()
    private val _records = MutableStateFlow<List<StudioRecord>>(emptyList())
    val records = _records.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    val active = records.map { list -> list.filter { it.enabled }.mapNotNull { it.active } }.distinctUntilChanged()
    private val leases = mutableMapOf<String, Int>()
    private val vault by lazy { StudioSecretVault(this.context) }
    private val cookies = mutableMapOf<String, MutableList<Cookie>>()
    private val http = OkHttpClient()

    init {
        scope.launch {
            try {
                if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
                    val text = file.openRead().use { it.readBytes() }.toString(Charsets.UTF_8)
                    _records.value = decodeRecords(text)
                }
            } catch (_: Exception) {
                _error.value = "Saved source definitions could not be read. The original file has been preserved."
            } finally {
                ready.complete(Unit)
            }
            RinSessions.busy.collect { if (!it) applyPending() }
        }
    }

    suspend fun saveDraft(source: StudioSource) = update { records ->
        StudioDocuments.decode(StudioDocuments.encode(source))
        val old = records.firstOrNull { it.draft.uuid == source.uuid }
        require(old == null || old.draft.medium == source.medium || old.active == null) {
            "An active source cannot change its medium"
        }
        val next = old?.withDraft(source) ?: StudioRecord(source)
        records.filterNot { it.draft.uuid == source.uuid } + next
    }

    suspend fun activate(uuid: String, allowScripts: Boolean = false): StudioRecord {
        var publication: StudioRecord? = null
        update { records ->
            records.map { record ->
                if (record.draft.uuid != uuid) {
                    record
                } else {
                    val issues = StudioValidation.source(record.draft)
                    require(issues.isEmpty()) { issues.firstOrNull()?.message ?: "Invalid source" }
                    val published = record.draft.copy(
                        revision = maxOf(
                            record.draft.revision,
                            (record.active?.revision ?: 0) + 1,
                        ),
                    )
                    val fingerprint = StudioDocuments.fingerprint(published)
                    require(
                        !StudioBlueprints.hasScripts(published) ||
                            allowScripts ||
                            record.trustedScriptFingerprint == fingerprint,
                    ) {
                        "Review and authorize JavaScript before activating this source"
                    }
                    val next = record.publishApproved(published, deferred = isBusy(uuid))
                    publication = next
                    next
                }
            }
        }
        return requireNotNull(publication) { "Source definition no longer available" }
    }

    suspend fun disable(uuid: String) = update { records ->
        require(!isBusy(uuid)) { "Wait until reading or playback has finished" }
        records.map { if (it.draft.uuid == uuid) it.copy(enabled = false, pending = false) else it }
    }

    suspend fun rollback(uuid: String) = update { records ->
        require(!isBusy(uuid)) { "Wait until reading or playback has finished" }
        records.map { record ->
            if (record.draft.uuid != uuid) {
                record
            } else {
                val old = requireNotNull(record.previous) { "No previous version" }
                // The previous snapshot was already explicitly approved on this installation.
                record.copy(
                    draft = old,
                    active = old,
                    previous = record.active,
                    pending = false,
                    enabled = true,
                    trustedScriptFingerprint = StudioDocuments.fingerprint(old),
                    activeScriptFingerprint = StudioDocuments.fingerprint(old),
                )
            }
        }
    }

    suspend fun remove(uuid: String) = update { records ->
        require(!isBusy(uuid)) { "Wait until reading or playback has finished" }
        records.filterNot { it.draft.uuid == uuid }
    }

    suspend fun importDocument(text: String, duplicateExisting: Boolean = false): String {
        var source = withContext(Dispatchers.Default) { StudioDocuments.decode(text) }
        ready.await()
        if (duplicateExisting && records.value.any { it.draft.uuid == source.uuid }) source = source.duplicate()
        saveDraft(source)
        return source.uuid
    }

    suspend fun draft(uuid: String): StudioSource {
        awaitReady()
        return requireNotNull(
            records.value.firstOrNull {
                it.draft.uuid == uuid
            }?.draft,
        ) { "Source definition no longer available" }
    }

    suspend fun awaitReady() {
        ready.await()
        check(error.value == null) { error.value.orEmpty() }
    }

    suspend fun backup(): String {
        ready.await()
        return StudioDocuments.json.encodeToString(records.value)
    }

    suspend fun restore(text: String) = update { existing ->
        val restored = decodeRecords(text)
        // A restored graph cannot inherit script permission from another installation.
        val reviewed = restored.map(StudioRecord::afterRestore)
        (existing + reviewed.filter { incoming -> existing.none { it.draft.uuid == incoming.draft.uuid } })
    }

    suspend fun setSecret(uuid: String, name: String, value: String) = withContext(Dispatchers.IO) {
        require(name.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Invalid secret name" }
        vault.set(draft(uuid), name, value)
    }

    suspend fun execute(
        source: StudioSource,
        flow: StudioFlow,
        variables: JsonObject,
        until: String? = null,
        allowDraftScripts: Boolean = false,
        trace: (StudioTrace) -> Unit = {},
        overrideGraph: StudioGraph? = null,
    ): StudioResult {
        ready.await()
        val trusted = records.value.firstOrNull { it.draft.uuid == source.uuid }?.trusts(source) == true
        val scripts = StudioScript { code, input ->
            check(trusted || allowDraftScripts) { "Review and authorize JavaScript before running it" }
            StudioScriptClient(context).evaluate(code, input)
        }
        val cookieJar = object : CookieJar {
            override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(cookies) {
                cookies[source.uuid].orEmpty().filter { it.expiresAt > System.currentTimeMillis() && it.matches(url) }
            }
            override fun saveFromResponse(url: HttpUrl, incoming: List<Cookie>) = synchronized(cookies) {
                val session = cookies.getOrPut(source.uuid) { mutableListOf() }
                incoming.forEach { cookie ->
                    session.removeAll {
                        it.name == cookie.name &&
                            it.domain == cookie.domain &&
                            it.path == cookie.path
                    }
                    session.add(cookie)
                }
                session.removeAll { it.expiresAt <= System.currentTimeMillis() }
                while (session.size > 128) session.removeAt(0)
                Unit
            }
        }
        val network =
            StudioNetwork(http.newBuilder().cookieJar(cookieJar).build()) { definition, name ->
                vault.get(definition, name)
            }
        mutex.withLock { leases[source.uuid] = (leases[source.uuid] ?: 0) + 1 }
        return try {
            StudioExecutor(network, scripts).run(source, flow, variables, until, trace, overrideGraph)
        } finally {
            withContext(NonCancellable) {
                mutex.withLock { leases[source.uuid] = ((leases[source.uuid] ?: 1) - 1).coerceAtLeast(0) }
                applyPending()
            }
        }
    }

    private fun isBusy(uuid: String) = RinSessions.busy.value || (leases[uuid] ?: 0) > 0
    private suspend fun applyPending() {
        if (_records.value.none { it.pending } || error.value != null) return
        update { list ->
            list.map {
                if (it.pending &&
                    !isBusy(it.draft.uuid)
                ) {
                    it.copy(
                        active = it.draft,
                        previous = it.active,
                        enabled = true,
                        pending = false,
                        activeScriptFingerprint = it.trustedScriptFingerprint,
                    )
                } else {
                    it
                }
            }
        }
    }
    private suspend fun update(change: (List<StudioRecord>) -> List<StudioRecord>) = withContext(Dispatchers.IO) {
        ready.await()
        mutex.withLock {
            check(error.value == null) { "Resolve the saved definitions error before editing" }
            val next = change(records.value)
            require(next.size <= 64) { "Source limit reached" }
            val bytes = StudioDocuments.json.encodeToString(next).toByteArray()
            require(bytes.size <= 16 * 1024 * 1024) { "Source definitions are too large" }
            val output = file.startWrite()
            try {
                output.write(bytes)
                file.finishWrite(output)
            } catch (
                failure: Throwable,
            ) {
                file.failWrite(output)
                throw failure
            }
            _records.value = next
        }
    }

    private fun decodeRecords(text: String): List<StudioRecord> {
        require(text.toByteArray().size <= 16 * 1024 * 1024) { "Backup too large" }
        return StudioDocuments.json.decodeFromString<List<StudioRecord>>(text).also { list ->
            require(list.size <= 64 && list.map { it.draft.uuid }.distinct().size == list.size)
            list.forEach { record ->
                StudioDocuments.decode(StudioDocuments.encode(record.draft))
                listOfNotNull(record.active, record.previous).forEach { definition ->
                    StudioDocuments.decode(StudioDocuments.encode(definition))
                    require(definition.uuid == record.draft.uuid && definition.medium == record.draft.medium)
                    require(StudioValidation.source(definition).isEmpty())
                }
            }
        }
    }

    companion object {
        @Volatile private var instance: SourceStudioRepository? = null
        fun get(context: Context): SourceStudioRepository = instance ?: synchronized(this) {
            instance ?: SourceStudioRepository(context).also { instance = it }
        }
    }
}
