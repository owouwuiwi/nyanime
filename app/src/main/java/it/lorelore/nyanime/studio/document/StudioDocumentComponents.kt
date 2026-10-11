package it.lorelore.nyanime.studio.document

import android.content.Context
import android.util.AtomicFile
import eu.kanade.tachiyomi.data.rin.RinLoader
import eu.kanade.tachiyomi.data.rin.RinRecord
import eu.kanade.tachiyomi.data.rin.RinRepository
import eu.kanade.tachiyomi.extension.ExtensionPackageConsent
import it.lorelore.nyanime.source.document.ResolvedSourceDocument
import it.lorelore.nyanime.source.document.SourceDocumentBackedSource
import it.lorelore.nyanime.source.document.SourceDocumentCollectionResolver
import it.lorelore.nyanime.source.document.SourceDocumentReader
import it.lorelore.nyanime.source.document.SourceDocumentResolver
import it.lorelore.nyanime.source.document.SourceStreamingDocumentResolver
import it.lorelore.nyanime.source.novel.NovelSource
import it.lorelore.nyanime.studio.DocumentOriginPolicy
import it.lorelore.nyanime.studio.SourceStudioRepository
import it.lorelore.nyanime.studio.StudioBlueprints
import it.lorelore.nyanime.studio.StudioDocuments
import it.lorelore.nyanime.studio.StudioSource
import it.lorelore.nyanime.studio.studioText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.concurrent.ConcurrentHashMap

internal data class StudioDocumentComponent(
    val record: RinRecord,
    val sourceId: Long,
    val id: String,
    val label: String,
    val hosts: Set<String>,
    val collection: Boolean,
    val streaming: Boolean,
) {
    val permission: String get() = DocumentWorkerWire.hash(
        (
            record.manifest.packageName +
                ":" +
                record.publisherSigners.sorted().joinToString(":") +
                ":$sourceId:$id:" +
                hosts.sorted().joinToString(":")
            ).toByteArray(),
    )
}

/** Generic component inventory, per-source consent and revocable invocation ownership. */
internal class StudioDocumentComponents private constructor(context: Context) {
    private val app = context.applicationContext
    private val packages by lazy { RinRepository.get(app) }
    private val definitions by lazy { SourceStudioRepository.get(app) }
    private val permissions = AtomicFile(File(app.noBackupFilesDir, "source-document-permissions-v1.json"))
    private val mutex = Mutex()
    private var saved: Map<String, Set<String>>? = null
    private val cache = object : LinkedHashMap<String, List<StudioDocumentComponent>>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<StudioDocumentComponent>>) =
            size > 64
    }
    private data class Active(
        val scope: String,
        val component: StudioDocumentComponent,
        val job: Job,
        val validate: suspend () -> Unit,
    )
    private val active = ConcurrentHashMap.newKeySet<Active>()
    private val observer = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        observer.launch {
            try {
                combine(packages.installed, definitions.records) { records, _ -> records }.collect { records ->
                    active.toList().forEach { operation ->
                        val installed = records.any {
                            it.sha256 == operation.component.record.sha256 &&
                                it.manifest.packageName == operation.component.record.manifest.packageName &&
                                trusted(it)
                        }
                        if (!installed) {
                            operation.job.cancel()
                        } else {
                            try {
                                operation.validate()
                            } catch (
                                cancel: CancellationException,
                            ) {
                                throw cancel
                            } catch (_: Exception) {
                                operation.job.cancel()
                            }
                        }
                    }
                }
            } catch (
                cancel: CancellationException,
            ) {
                throw cancel
            } catch (_: Exception) {
                active.forEach { it.job.cancel() }
            }
        }
    }

    fun scope(source: StudioSource) = "studio:${source.uuid}:${source.sourceId}"
    fun scope(record: RinRecord, source: NovelSource) = scope(record, source.id)
    private fun scope(record: RinRecord, sourceId: Long) = "package:${record.manifest.packageName}:" +
        DocumentWorkerWire.hash(record.publisherSigners.sorted().joinToString(":").toByteArray()) +
        ":$sourceId"

    suspend fun available(): List<StudioDocumentComponent> = withContext(Dispatchers.IO) {
        packages.installed.value.filter(::trusted).flatMap { record ->
            val key = record.manifest.packageName + ":" + record.sha256
            synchronized(cache) { cache[key] } ?: try {
                RinLoader.instantiate(app, record).filterIsInstance<NovelSource>().mapNotNull { source ->
                    (source as? SourceDocumentResolver)?.let { metadata(record, source, it) }
                }.also { synchronized(cache) { cache[key] = it } }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    suspend fun find(id: String): StudioDocumentComponent = available().filter { it.id == id }.singleOrNull()
        ?: error("Install one unambiguous document component for this dependency")

    suspend fun granted(scope: String, component: StudioDocumentComponent): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock { component.permission in read()[key(scope)].orEmpty() }
    }

    suspend fun grant(scope: String, component: StudioDocumentComponent) = withContext(Dispatchers.IO) {
        checkCurrent(component)
        mutex.withLock {
            val current = read()
            write(current + (key(scope) to (current[key(scope)].orEmpty() + component.permission)))
        }
    }

    suspend fun revoke(scope: String) = withContext(Dispatchers.IO) {
        active.filter { it.scope == scope }.forEach { it.job.cancel() }
        mutex.withLock { write(read() - key(scope)) }
    }

    suspend fun checkAccess(source: StudioSource) {
        source.documentResolvers.forEach { id ->
            val component = find(id)
            checkCurrent(component)
            check(granted(scope(source), component)) { "Authorize the document component for this source" }
        }
    }

    /** Supplying a reader never grants permission or starts a remote operation. */
    fun attach(record: RinRecord, source: NovelSource) {
        if (source is SourceDocumentBackedSource) {
            val ids = source.documentResolverIds.toSet().also { declared ->
                require(declared.size in 1..16 && declared.all { it.matches(Regex("[a-zA-Z][a-zA-Z0-9_.-]{0,159}")) })
            }
            source.attachDocuments(object : SourceDocumentReader {
                private suspend fun selected(id: String?): SourceDocumentReader {
                    val chosen = id ?: ids.singleOrNull() ?: error("Select a declared document component")
                    require(chosen in ids)
                    val component = if (source is SourceDocumentResolver && source.documentResolverId == chosen) {
                        metadata(record, source, source)
                    } else {
                        find(chosen)
                    }
                    return reader(scope(record, source), component) {
                        check(
                            packages.installed.value.any {
                                it.manifest.packageName == record.manifest.packageName &&
                                    it.sha256 == record.sha256 &&
                                    it.publisherSigners == record.publisherSigners &&
                                    trusted(it)
                            },
                        ) {
                            "Source changed or is no longer enabled"
                        }
                        checkCurrent(component)
                    }
                }
                override suspend fun describe(
                    reference: String,
                    resolverId: String?,
                ) = selected(resolverId).describe(reference)
                override suspend fun document(
                    reference: String,
                    resolverId: String?,
                ) = selected(resolverId).document(reference)
                override suspend fun list(reference: String, resolverId: String?) = selected(resolverId).list(reference)
            })
        }
    }

    suspend fun reader(source: StudioSource, resolver: String, preview: Boolean): SourceDocumentReader {
        require(resolver in source.documentResolvers)
        val component = find(resolver)
        return reader(scope(source), component) {
            definitions.awaitReady()
            val record = definitions.records.value.singleOrNull { it.draft.uuid == source.uuid }
                ?: error("Source no longer exists")
            val matches = if (preview) {
                record.draft == source
            } else {
                record.enabled &&
                    record.active == source &&
                    (!StudioBlueprints.hasScripts(source) || record.trusts(source))
            }
            check(matches) { "Source snapshot changed" }
            checkCurrent(component)
        }
    }

    private fun reader(
        scope: String,
        component: StudioDocumentComponent,
        validate: suspend () -> Unit,
    ): SourceDocumentReader = object : SourceDocumentReader {
        private suspend fun request(operation: String, reference: String): StudioDocumentReply {
            val job = Job(currentCoroutineContext()[Job])
            val invocation = Active(scope, component, job, validate)
            active += invocation
            return try {
                withContext(job) {
                    StudioDocumentWorkerClient(app).run(
                        StudioDocumentInvocation(
                            packages.storage.file(component.record),
                            component.record.sha256,
                            component.record.manifest.entryPoints,
                            component.sourceId,
                            component.id,
                            component.hosts,
                        ),
                        operation,
                        reference,
                    ) {
                        validate()
                        check(granted(scope, component)) { "Authorize the document component for this source" }
                    }
                }
            } finally {
                active -= invocation
                job.complete()
            }
        }
        override suspend fun describe(
            reference: String,
            resolverId: String?,
        ): it.lorelore.nyanime.source.document.SourceDocumentDescriptor {
            require(resolverId == null || resolverId == component.id)
            return DocumentWorkerWire.descriptor(request("describe", reference).metadata)
        }
        override suspend fun list(
            reference: String,
            resolverId: String?,
        ): List<it.lorelore.nyanime.source.document.SourceDocumentEntry> {
            require(resolverId == null || resolverId == component.id)
            return DocumentWorkerWire.entries(request("list", reference).metadata)
        }
        override suspend fun document(reference: String, resolverId: String?): ResolvedSourceDocument {
            require(resolverId == null || resolverId == component.id)
            val answer = request("document", reference)
            val data = answer.metadata
            require(data.getValue("reference").studioText() == reference)
            return ResolvedSourceDocument(
                reference,
                data.getValue("title").studioText(),
                data.getValue("mimeType").studioText(),
                requireNotNull(answer.bytes),
            )
        }
    }

    private fun metadata(
        record: RinRecord,
        source: NovelSource,
        resolver: SourceDocumentResolver,
    ): StudioDocumentComponent {
        require(
            record.manifest.sources.any {
                it.id == source.id && it.name == source.name && it.language == source.lang
            },
        )
        val id = resolver.documentResolverId.also { require(it.matches(Regex("[a-zA-Z][a-zA-Z0-9_.-]{0,159}"))) }
        val label = resolver.documentResolverName.also { require(it.length in 1..160) }
        val hosts = resolver.documentResolverHosts.toSet()
        DocumentOriginPolicy(hosts)
        return StudioDocumentComponent(
            record,
            source.id,
            id,
            label,
            hosts,
            source is SourceDocumentCollectionResolver,
            source is SourceStreamingDocumentResolver,
        )
    }

    private fun trusted(record: RinRecord) =
        record.format == "apk" &&
            record.manifest.medium == "novel" &&
            record.enabled &&
            record.trusted &&
            ExtensionPackageConsent.trusted(record) &&
            RinRepository.compatible(record.manifest, record.format)

    private fun checkCurrent(component: StudioDocumentComponent) {
        check(
            packages.installed.value.any {
                it.manifest.packageName == component.record.manifest.packageName &&
                    it.sha256 == component.record.sha256 &&
                    it.publisherSigners == component.record.publisherSigners &&
                    trusted(it)
            },
        ) {
            "Document component changed or is no longer enabled"
        }
    }

    private fun key(scope: String) = DocumentWorkerWire.hash(scope.toByteArray())
    private fun read(): Map<String, Set<String>> {
        saved?.let { return it }
        return (
            if (permissions.baseFile.exists() || File(permissions.baseFile.path + ".bak").exists()) {
                val raw = permissions.openRead().use { DocumentWorkerWire.read(it, 512 * 1024) }
                (
                    StudioDocuments.json.parseToJsonElement(
                        raw.toString(Charsets.UTF_8),
                    ) as JsonObject
                    ).mapValues { (_, value) ->
                    (value as JsonArray).map {
                        it.studioText().also { require(it.matches(Regex("[a-f0-9]{64}"))) }
                    }.toSet()
                }.also {
                    require(
                        it.size <= 256 &&
                            it.all { (key, value) ->
                                key.matches(Regex("[a-f0-9]{64}")) && value.size <= 32
                            },
                    )
                }
            } else {
                emptyMap()
            }
            ).also { saved = it }
    }
    private fun write(values: Map<String, Set<String>>) {
        require(values.size <= 256 && values.values.all { it.size <= 32 })
        val bytes = JsonObject(
            values.mapValues {
                JsonArray(it.value.sorted().map(::JsonPrimitive))
            },
        ).toString().toByteArray()
        require(bytes.size <= 512 * 1024)
        val output = permissions.startWrite()
        try {
            output.write(bytes)
            permissions.finishWrite(output)
            saved = values
        } catch (
            failure: Exception,
        ) {
            permissions.failWrite(output)
            throw failure
        }
    }

    companion object {
        @Volatile private var instance: StudioDocumentComponents? = null
        suspend fun revokePackageAccess(context: Context, record: RinRecord) {
            val file = File(context.noBackupFilesDir, "source-document-permissions-v1.json")
            if (instance == null && !file.exists() && !File(file.path + ".bak").exists()) return
            val registry = get(context)
            record.manifest.sources.forEach { registry.revoke(registry.scope(record, it.id)) }
        }
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: StudioDocumentComponents(context).also { instance = it }
        }
    }
}
