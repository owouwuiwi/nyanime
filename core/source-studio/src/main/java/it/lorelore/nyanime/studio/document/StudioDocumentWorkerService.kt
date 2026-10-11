package it.lorelore.nyanime.studio.document

import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.Process
import dalvik.system.InMemoryDexClassLoader
import it.lorelore.nyanime.source.document.SourceDocumentCollectionResolver
import it.lorelore.nyanime.source.document.SourceDocumentDescriptor
import it.lorelore.nyanime.source.document.SourceDocumentNetwork
import it.lorelore.nyanime.source.document.SourceDocumentRequest
import it.lorelore.nyanime.source.document.SourceDocumentResolver
import it.lorelore.nyanime.source.document.SourceDocumentResponse
import it.lorelore.nyanime.source.document.SourceDocumentSink
import it.lorelore.nyanime.source.document.SourceDocumentTransfer
import it.lorelore.nyanime.source.document.SourceStreamingDocumentResolver
import it.lorelore.nyanime.source.novel.NovelSource
import it.lorelore.nyanime.source.novel.NovelSourceFactory
import it.lorelore.nyanime.studio.StudioDocuments
import it.lorelore.nyanime.studio.studioText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

/** Provider document code runs under a separate UID with no account, database or network permission. */
class StudioDocumentWorkerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val watchdog = Executors.newSingleThreadScheduledExecutor()
    private val busy = AtomicBoolean()
    private var network: RemoteNetwork? = null
    private val incoming = Messenger(
        Handler(Looper.getMainLooper()) { message ->
            if (message.sendingUid != applicationInfo.uid) {
                DocumentWorkerWire.descriptor(message.data, "body")?.close()
                DocumentWorkerWire.descriptor(message.data, "apk")?.close()
                return@Handler true
            }
            when (message.what) {
                DocumentWorkerWire.RUN -> start(message)
                DocumentWorkerWire.NETWORK_RESPONSE,
                DocumentWorkerWire.NETWORK_COMPLETE,
                DocumentWorkerWire.NETWORK_FAILED,
                ->
                    network?.receive(message) ?: DocumentWorkerWire.descriptor(message.data, "body")?.close()
            }
            true
        },
    )

    override fun onCreate() {
        super.onCreate()
        check(Process.myUid() != applicationInfo.uid) { "Document worker must use an isolated UID" }
    }
    override fun onBind(intent: Intent): IBinder = incoming.binder

    private fun start(message: Message) {
        val reply = message.replyTo ?: return
        val data = message.data
        val token = data.getString("token").orEmpty()
        if (!busy.compareAndSet(false, true)) {
            DocumentWorkerWire.descriptor(data, "apk")?.close()
            send(reply, DocumentWorkerWire.FAILED, token, Bundle())
            return
        }
        scope.launch {
            var stage = "validate"
            val baseline = heap()
            val started = System.nanoTime()
            val guard = watchdog.scheduleAtFixedRate({
                if (TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) > 120 ||
                    heap() - baseline > 96L * 1024 * 1024
                ) {
                    Process.killProcess(Process.myPid())
                }
            }, 100, 100, TimeUnit.MILLISECONDS)
            try {
                withTimeout(120_000) {
                    require(UUID.fromString(token).toString() == token)
                    val reference = data.getString("reference").orEmpty().also { require(it.length in 1..8192) }
                    stage = "load"
                    val source = load(data) { stage = it }
                    stage = "capability"
                    val resolver = source as? SourceDocumentResolver ?: error("No document capability")
                    require(resolver.documentResolverId == data.getString("resolver"))
                    val hosts = (
                        StudioDocuments.json.parseToJsonElement(
                            data.getString("hosts").orEmpty(),
                        ) as JsonArray
                        )
                        .map { it.studioText() }.toSet()
                    require(resolver.documentResolverHosts == hosts)
                    val broker = RemoteNetwork(reply, token).also { network = it }
                    stage = "resolve"
                    when (data.getString("operation")) {
                        "describe" -> {
                            val descriptor = if (resolver is SourceStreamingDocumentResolver) {
                                resolver.describeDocument(reference, broker)
                            } else {
                                resolver.resolveDocument(reference, broker).let {
                                    SourceDocumentDescriptor(
                                        it.reference,
                                        it.title,
                                        it.mimeType,
                                        it.bytes.size.toLong(),
                                        DocumentWorkerWire.hash(it.bytes),
                                    )
                                }
                            }
                            result(reply, token, DocumentWorkerWire.descriptor(descriptor))
                        }
                        "list" -> {
                            require(resolver is SourceDocumentCollectionResolver)
                            result(reply, token, DocumentWorkerWire.entries(resolver.listDocuments(reference, broker)))
                        }
                        "document" -> {
                            val resolved = resolver.resolveDocument(reference, broker)
                            require(resolved.reference == reference)
                            result(
                                reply,
                                token,
                                buildJsonObject {
                                    put("reference", resolved.reference)
                                    put("title", resolved.title)
                                    put("mimeType", resolved.mimeType)
                                    put("size", resolved.bytes.size)
                                    put("sha256", DocumentWorkerWire.hash(resolved.bytes))
                                },
                                resolved.bytes,
                            )
                        }
                        else -> error("Unknown operation")
                    }
                }
            } catch (failure: Throwable) {
                // Never return arbitrary provider exception text, links, keys or document contents.
                val category = when (failure) {
                    is ClassNotFoundException -> "class_missing"
                    is LinkageError -> "linkage"
                    is SecurityException -> "security"
                    is IllegalArgumentException -> "invalid"
                    is IllegalStateException -> "state"
                    is java.io.IOException -> "io"
                    is ReflectiveOperationException -> "reflection"
                    else -> "failed"
                }
                runCatching {
                    send(
                        reply,
                        DocumentWorkerWire.FAILED,
                        token,
                        Bundle().apply {
                            putString("stage", stage)
                            putString("category", category)
                        },
                    )
                }
            } finally {
                network?.close()
                network = null
                guard.cancel(true)
                busy.set(false)
            }
        }
    }

    private fun load(data: Bundle, stage: (String) -> Unit): NovelSource {
        stage("read_package")
        val apk = requireNotNull(DocumentWorkerWire.descriptor(data, "apk"))
        val bytes = ParcelFileDescriptor.AutoCloseInputStream(apk).use {
            DocumentWorkerWire.read(it, DocumentWorkerWire.MAX_APK_BYTES)
        }
        stage("verify_package")
        require(DocumentWorkerWire.hash(bytes) == data.getString("sha256"))
        stage("read_dex")
        val dex = mutableListOf<ByteBuffer>()
        var total = 0
        var scanned = 0
        var entries = 0
        val names = mutableSetOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++entries <= 4096)
                if (entry.name.matches(Regex("classes(?:[2-9]|[1-9][0-9]+)?\\.dex"))) {
                    require(names.add(entry.name))
                    require(dex.size < 16)
                    val content = DocumentWorkerWire.read(zip, DocumentWorkerWire.MAX_APK_BYTES - total)
                    total += content.size
                    scanned += content.size
                    // Android's in-memory DEX loader accesses array() for heap buffers.
                    // A read-only heap view throws before loading; this private buffer is never exposed.
                    dex += ByteBuffer.wrap(content)
                } else {
                    val discard = ByteArray(32 * 1024)
                    while (true) {
                        val count = zip.read(discard)
                        if (count < 0) break
                        scanned += count
                        require(scanned <= 32 * 1024 * 1024) { "Component archive is too large" }
                    }
                }
                require(scanned <= 32 * 1024 * 1024)
                zip.closeEntry()
            }
        }
        require(dex.isNotEmpty() && (Build.VERSION.SDK_INT >= 27 || dex.size == 1)) {
            "Unsupported component bytecode layout"
        }
        stage("load_dex")
        val loader = if (Build.VERSION.SDK_INT >= 27) {
            InMemoryDexClassLoader(dex.toTypedArray(), classLoader)
        } else {
            InMemoryDexClassLoader(dex.single(), classLoader)
        }
        val factories = requireNotNull(data.getStringArrayList("factories")).also { require(it.size in 1..32) }
        val sources = factories.flatMap { name ->
            require(name.length <= 512)
            stage("instantiate")
            when (val instance = Class.forName(name, true, loader).getDeclaredConstructor().newInstance()) {
                is NovelSourceFactory -> instance.createNovelSources()
                is NovelSource -> listOf(instance)
                else -> error("Unsupported component factory")
            }
        }
        require(sources.size in 1..128)
        return sources.single { it.id == data.getLong("source") }
    }

    private fun result(reply: Messenger, token: String, metadata: JsonObject, bytes: ByteArray? = null) {
        val encoded = metadata.toString()
        require(encoded.toByteArray().size <= DocumentWorkerWire.MAX_METADATA_BYTES)
        val bundle = Bundle().apply { putString("metadata", encoded) }
        if (bytes == null) {
            send(reply, DocumentWorkerWire.RESULT, token, bundle)
        } else {
            require(bytes.size <= DocumentWorkerWire.MAX_RESPONSE_BYTES)
            val pipe = ParcelFileDescriptor.createPipe()
            try {
                bundle.putParcelable("body", pipe[0])
                send(reply, DocumentWorkerWire.RESULT, token, bundle)
                pipe[0].close()
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(bytes) }
            } finally {
                runCatching { pipe[0].close() }
                runCatching { pipe[1].close() }
            }
        }
    }

    private fun send(reply: Messenger, type: Int, token: String, payload: Bundle) {
        payload.putString("token", token)
        payload.putInt("uid", Process.myUid())
        reply.send(Message.obtain(null, type).apply { data = payload })
    }

    private fun heap(): Long {
        val managed = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        return Debug.getNativeHeapAllocatedSize() + managed
    }

    private inner class RemoteNetwork(private val host: Messenger, private val token: String) : SourceDocumentNetwork {
        private inner class Pending {
            val ready = CompletableDeferred<Bundle>()
            val complete = CompletableDeferred<Long>()

            @Volatile var body: ParcelFileDescriptor? = null
        }
        private val pending = ConcurrentHashMap<String, Pending>()

        fun receive(message: Message) {
            val data = message.data
            val body = DocumentWorkerWire.descriptor(data, "body")
            val operation = pending[data.getString("request")]
            if (data.getString("token") != token || operation == null) {
                body?.close()
                return
            }
            when (message.what) {
                DocumentWorkerWire.NETWORK_RESPONSE -> {
                    operation.body = body
                    if (!operation.ready.complete(data)) body?.close()
                }
                DocumentWorkerWire.NETWORK_COMPLETE -> operation.complete.complete(data.getLong("transferred"))
                DocumentWorkerWire.NETWORK_FAILED -> {
                    val error = IllegalStateException("Document network operation failed")
                    operation.ready.completeExceptionally(error)
                    operation.complete.completeExceptionally(error)
                    operation.body?.close()
                }
            }
        }

        override suspend fun request(request: SourceDocumentRequest): SourceDocumentResponse {
            val output = java.io.ByteArrayOutputStream()
            val result = read(
                request,
                false,
                object : SourceDocumentSink {
                    override suspend fun write(bytes: ByteArray) {
                        require(output.size() + bytes.size <= DocumentWorkerWire.MAX_RESPONSE_BYTES)
                        output.write(bytes)
                    }
                },
                writeErrors = true,
            )
            return SourceDocumentResponse(result.status, output.toByteArray())
        }

        override suspend fun transfer(request: SourceDocumentRequest, consumer: SourceDocumentSink) = read(
            request,
            true,
            consumer,
            false,
        )

        private suspend fun read(
            request: SourceDocumentRequest,
            stream: Boolean,
            consumer: SourceDocumentSink,
            writeErrors: Boolean,
        ): SourceDocumentTransfer = withTimeout(65_000) {
            val id = UUID.randomUUID().toString()
            val operation = Pending()
            require(pending.putIfAbsent(id, operation) == null)
            try {
                host.send(
                    Message.obtain(null, DocumentWorkerWire.NETWORK).apply {
                        replyTo = incoming
                        data = Bundle().apply {
                            putString("token", token)
                            putString("request", id)
                            putString("metadata", DocumentWorkerWire.request(request).toString())
                            putByteArray("body", request.body)
                            putBoolean("stream", stream)
                        }
                    },
                )
                val response = operation.ready.await()
                val status = response.getInt("status").also { require(it in 100..599) }
                val body = requireNotNull(DocumentWorkerWire.descriptor(response, "body"))
                val transferred = withContext(Dispatchers.IO) {
                    var count = 0L
                    ParcelFileDescriptor.AutoCloseInputStream(body).use { input ->
                        val chunk = ByteArray(SourceDocumentSink.MAX_CHUNK_BYTES)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val bytes = input.read(chunk)
                            if (bytes < 0) break
                            count += bytes
                            require(
                                count <=
                                    if (stream) {
                                        DocumentWorkerWire.MAX_TRANSFER_BYTES
                                    } else {
                                        DocumentWorkerWire.MAX_RESPONSE_BYTES
                                    },
                            )
                            if (writeErrors || status in 200..299) consumer.write(chunk.copyOf(bytes))
                        }
                    }
                    count
                }
                require(operation.complete.await() == transferred) { "Document response was interrupted" }
                SourceDocumentTransfer(status, transferred)
            } finally {
                pending.remove(id)
                operation.body?.close()
            }
        }

        fun close() {
            pending.values.forEach {
                it.body?.close()
                it.ready.cancel()
                it.complete.cancel()
            }
            pending.clear()
        }
    }

    override fun onDestroy() {
        network?.close()
        scope.cancel()
        watchdog.shutdownNow()
        super.onDestroy()
        // Also terminates arbitrary provider code that ignores coroutine cancellation.
        Process.killProcess(Process.myPid())
    }
}
