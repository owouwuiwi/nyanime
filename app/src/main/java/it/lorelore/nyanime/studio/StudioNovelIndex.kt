package it.lorelore.nyanime.studio

import android.content.Context
import it.lorelore.nyanime.novel.source.NovelPreparationPhase
import it.lorelore.nyanime.novel.source.NovelPreparationProgress
import it.lorelore.nyanime.source.novel.NovelChapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import nyanime.telegram.TelegramAccountSessions
import java.security.MessageDigest

internal object StudioNovelIndexKey {
    fun digest(raw: String): String =
        MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") {
            "%02x".format(it)
        }

    fun of(request: StudioIndexRequest, access: String): String =
        digest(
            request.source.uuid +
                ":" +
                StudioDocuments.fingerprint(request.source) +
                ":" +
                request.preview +
                ":" +
                request.scripts +
                ":" +
                access +
                ":" +
                request.reference,
        )
}

internal data class StudioIndexRequest(
    val source: StudioSource,
    val reference: String,
    val preview: Boolean,
    val scripts: Boolean,
)

/** One lazy host instance shares jobs; a cancelled caller does not cancel another screen's scan. */
internal class StudioNovelIndex
internal constructor(
    private val journal: StudioNovelIndexJournal,
    private val execute: suspend (StudioIndexRequest, String?) -> JsonElement,
    private val access: suspend (StudioIndexRequest) -> String,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val scope =
        CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val gate = Any()
    private val jobs = mutableMapOf<String, Deferred<List<JsonObject>>>()
    private val mutableProgress =
        MutableStateFlow<Map<String, NovelPreparationProgress>>(emptyMap())
    private val permits = Semaphore(2)

    suspend fun chapters(
        source: StudioSource,
        reference: String,
        draftPreview: Boolean = false,
        approvedDraftScripts: Boolean = false,
        force: Boolean = false,
    ): List<JsonObject> {
        val request = StudioIndexRequest(source, reference, draftPreview, approvedDraftScripts)
        require(reference.length in 1..8192)
        val stamp = access(request)
        val key = StudioNovelIndexKey.of(request, stamp)
        val job =
            synchronized(gate) {
                require(jobs[key] != null || jobs.size < 16) {
                    "Too many chapter preparations are waiting"
                }
                jobs[key]
                    ?: scope
                        .async(start = CoroutineStart.LAZY) { scan(request, stamp, key, force) }
                        .also { created ->
                            jobs[key] = created
                            created.invokeOnCompletion {
                                synchronized(gate) { jobs.remove(key, created) }
                            }
                        }
            }
        job.start()
        return job.await().also { check(access(request) == stamp) { "Source access changed" } }
    }

    fun observe(request: StudioIndexRequest): Flow<NovelPreparationProgress> = flow {
        val stamp = access(request)
        val key = StudioNovelIndexKey.of(request, stamp)
        if (key !in mutableProgress.value) {
            val saved = withContext(io) { journal.read(key) }
            mutableProgress.update { values ->
                if (key in values) values else values + (key to progress(saved, false))
            }
        }
        emitAll(
            mutableProgress.map { it[key] ?: NovelPreparationProgress() }.distinctUntilChanged(),
        )
    }

    suspend fun pause(request: StudioIndexRequest) {
        val key = StudioNovelIndexKey.of(request, access(request))
        synchronized(gate) { jobs[key] }?.cancelAndJoin()
    }

    private suspend fun scan(
        request: StudioIndexRequest,
        stamp: String,
        key: String,
        force: Boolean,
    ): List<JsonObject> =
        withContext(io) {
            journal.pin(key).use {
                var saved = journal.read(key)
                val cached = saved.ready
                if (
                    !force &&
                    saved.staging == null &&
                    cached != null &&
                    clock() - cached.updatedAt in 0..300_000
                ) {
                    publish(key, progress(saved, false))
                    return@withContext result(request, cached.id)
                }
                publish(key, progress(saved, false).copy(phase = NovelPreparationPhase.WAITING))
                try {
                    permits.withPermit {
                        currentCoroutineContext().ensureActive()
                        check(access(request) == stamp) { "Source access changed" }
                        var stage = journal.begin(key, clock())
                        saved = journal.read(key)
                        publish(key, progress(saved, true))
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            check(access(request) == stamp) { "Source access changed" }
                            val raw = execute(request, stage.cursor)
                            currentCoroutineContext().ensureActive()
                            check(access(request) == stamp) { "Source access changed" }
                            saved = journal.append(key, stage.id, page(raw), clock())
                            if (saved.staging == null) {
                                publish(key, progress(saved, false))
                                return@withPermit result(request, requireNotNull(saved.ready).id)
                            }
                            stage = requireNotNull(saved.staging)
                            publish(key, progress(saved, true))
                        }
                        @Suppress("UNREACHABLE_CODE")
                        emptyList()
                    }
                } catch (cancel: CancellationException) {
                    publish(key, progress(saved, false).copy(phase = NovelPreparationPhase.PAUSED))
                    throw cancel
                } catch (failure: Exception) {
                    publish(key, progress(saved, false).copy(phase = NovelPreparationPhase.FAILED))
                    throw failure
                }
            }
        }

    private fun result(request: StudioIndexRequest, generation: Long): List<JsonObject> {
        val entries = journal.entries(generation)
        val newestFirst =
            request.source.flows[StudioFlow.ENTRIES]
                ?.nodes
                ?.singleOrNull { it.kind == StudioKind.OUTPUT }
                ?.parameters
                ?.get("order") == "newest-first"
        return if (newestFirst) entries.reversed() else entries
    }

    private fun progress(saved: StudioIndexSaved, running: Boolean): NovelPreparationProgress {
        val edition = saved.staging ?: saved.ready
        return NovelPreparationProgress(
            when {
                running -> NovelPreparationPhase.RUNNING
                saved.staging != null -> NovelPreparationPhase.PAUSED
                saved.ready != null -> NovelPreparationPhase.READY
                else -> NovelPreparationPhase.WAITING
            },
            edition?.pages ?: 0,
            edition?.found ?: 0,
            saved.ready != null,
        )
    }

    private fun publish(key: String, progress: NovelPreparationProgress) {
        mutableProgress.update { before ->
            val next = before + (key to progress)
            if (next.size <= 128) {
                next
            } else {
                val active = synchronized(gate) { jobs.keys.toSet() }
                val removed = next.keys.firstOrNull { it != key && it !in active }
                if (removed == null) next else next - removed
            }
        }
    }

    private fun page(raw: JsonElement): StudioIndexPage {
        val envelope = raw as? JsonObject
        val items =
            requireNotNull((envelope?.get("items") ?: raw) as? JsonArray) { "Return chapter items" }
        require(items.size <= 2000)
        val cursor = envelope?.get("cursor")?.studioText()?.takeIf(String::isNotBlank)
        val declared = envelope?.get("hasNext")
        val hasNext =
            declared?.jsonPrimitive?.booleanOrNull
                ?: run {
                    require(declared == null) { "Declare a boolean next-page flag" }
                    cursor != null
                }
        require(!hasNext || cursor != null) { "Declare a cursor for the next chapter page" }
        val metadata = items.map { value ->
            val item = value.jsonObject
            NovelChapter(
                requireNotNull(item["url"]?.studioText()),
                requireNotNull(item["title"]?.studioText()),
                item["number"]?.jsonPrimitive?.doubleOrNull,
                item["group"]?.studioText(),
                item["uploaded"]?.jsonPrimitive?.longOrNull,
            )
            JsonObject(
                item.filterKeys { it in setOf("url", "title", "number", "group", "uploaded") },
            )
        }
        return StudioIndexPage(metadata, cursor, !hasNext)
    }

    companion object {
        @Volatile private var instance: StudioNovelIndex? = null

        fun get(context: Context, repository: SourceStudioRepository): StudioNovelIndex =
            instance
                ?: synchronized(this) {
                    instance
                        ?: create(context.applicationContext, repository).also { instance = it }
                }

        private fun create(app: Context, repository: SourceStudioRepository) =
            StudioNovelIndex(
                StudioSqliteIndexJournal(app),
                execute = { request, cursor ->
                    repository
                        .execute(
                            request.source,
                            StudioFlow.ENTRIES,
                            buildJsonObject {
                                put("url", request.reference)
                                put("reference", request.reference)
                                put("cursor", cursor.orEmpty())
                            },
                            allowDraftScripts = request.scripts,
                            allowDraftCapabilities = request.preview,
                        )
                        .value
                },
                access = { request ->
                    repository.awaitReady()
                    val record =
                        requireNotNull(
                            repository.records.value.singleOrNull {
                                it.draft.uuid == request.source.uuid
                            },
                        )
                    check(
                        if (request.preview) {
                            record.draft == request.source
                        } else {
                            record.enabled && record.active == request.source
                        },
                    )
                    check(
                        !StudioBlueprints.hasScripts(request.source) ||
                            record.trusts(request.source) ||
                            (request.preview && request.scripts),
                    )
                    val requirements =
                        SourceActivationRequirements.declared(request.source, request.preview)
                    val proof =
                        requireNotNull(
                            SourceActivationRequirements.proof(app, requirements, live = false),
                        )
                    if (request.source.documentResolvers.isNotEmpty()) {
                        it.lorelore.nyanime.studio.document.StudioDocumentComponents.get(app)
                            .checkAccess(request.source)
                    }
                    val stamp =
                        if (requirements.isEmpty()) {
                            "local"
                        } else {
                            val session = TelegramAccountSessions.get(app).state.value
                            val grants = StudioTelegramGrantStore.get(app).snapshot().grants
                            requirements.joinToString("|") { requirement ->
                                val grant = grants.single { it.owner == requirement.owner }
                                session.revision + ":" + grant.generation
                            }
                        }
                    check(SourceActivationRequirements.current(app, proof))
                    stamp
                },
            )
    }
}
