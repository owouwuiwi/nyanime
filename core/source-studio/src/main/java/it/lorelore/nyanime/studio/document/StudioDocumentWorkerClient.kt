package it.lorelore.nyanime.studio.document

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.Process
import it.lorelore.nyanime.studio.DocumentOriginPolicy
import it.lorelore.nyanime.studio.studioText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.net.Proxy
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class StudioDocumentInvocation(
    val apk: File,
    val sha256: String,
    val factories: List<String>,
    val sourceId: Long,
    val resolverId: String,
    val hosts: Set<String>,
)

data class StudioDocumentReply(val metadata: JsonObject, val bytes: ByteArray?, val isolatedUid: Int)

/** Lazy, single-use isolated worker. APK verification and consent are rechecked by the caller. */
class StudioDocumentWorkerClient internal constructor(
    context: Context,
    private val testNetwork: okhttp3.Call.Factory? = null,
    private val timeoutMillis: Long = 120_000,
) {
    constructor(context: Context) : this(context, null, 120_000)
    private val app = context.applicationContext

    suspend fun run(
        invocation: StudioDocumentInvocation,
        operation: String,
        reference: String,
        validate: suspend () -> Unit,
    ): StudioDocumentReply = worker.withLock {
        withTimeout(timeoutMillis) {
            validate()
            require(operation in setOf("describe", "document", "list") && reference.length in 1..8192)
            require(invocation.apk.length() in 1..DocumentWorkerWire.MAX_APK_BYTES.toLong())
            val policy = DocumentOriginPolicy(invocation.hosts)
            val token = UUID.randomUUID().toString()
            val scope =
                CoroutineScope(SupervisorJob(currentCoroutineContext()[kotlinx.coroutines.Job]) + Dispatchers.IO)
            val done = CompletableDeferred<Message>()
            val connected = CompletableDeferred<Messenger>()
            val calls = ConcurrentHashMap.newKeySet<Call>()
            val descriptors = ConcurrentHashMap.newKeySet<ParcelFileDescriptor>()
            val requests = ConcurrentHashMap.newKeySet<String>()
            val remaining = AtomicInteger(16)
            val reader = Executors.newSingleThreadExecutor()
            val client = OkHttpClient.Builder()
                .dns { hostname -> policy.requirePublic(Dns.SYSTEM.lookup(hostname)) }
                .followRedirects(false).followSslRedirects(false)
                .cookieJar(CookieJar.NO_COOKIES).cache(null)
                .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE).proxy(Proxy.NO_PROXY)
                .connectTimeout(
                    15,
                    TimeUnit.SECONDS,
                ).readTimeout(30, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build()

            fun replyNetwork(target: Messenger, kind: Int, request: String, values: Bundle = Bundle()) {
                values.putString("token", token)
                values.putString("request", request)
                target.send(Message.obtain(null, kind).apply { data = values })
            }

            fun network(message: Message) {
                val target = message.replyTo ?: return
                val data = message.data
                val id = data.getString("request").orEmpty()
                scope.launch {
                    try {
                        require(
                            UUID.fromString(
                                id,
                            ).toString() == id &&
                                requests.add(id) &&
                                remaining.decrementAndGet() >= 0,
                        )
                        validate()
                        currentCoroutineContext().ensureActive()
                        val input = DocumentWorkerWire.request(
                            DocumentWorkerWire.json(data, "metadata"),
                            data.getByteArray("body") ?: byteArrayOf(),
                        )
                        policy.requireUrl(input.url)
                        val headers = input.headers.filterKeys { name ->
                            require(
                                !name.equals("Host", true) &&
                                    !name.equals("Content-Length", true) &&
                                    !name.equals("Cookie", true) &&
                                    !name.equals("Connection", true),
                            )
                            true
                        }
                        val request = Request.Builder().url(input.url).apply {
                            headers.forEach { (name, value) -> header(name, value) }
                            if (input.method == "POST") {
                                post(
                                    input.body.toRequestBody(
                                        headers.entries
                                            .firstOrNull { it.key.equals("Content-Type", true) }?.value?.toMediaType(),
                                    ),
                                )
                            } else {
                                require(input.body.isEmpty())
                                get()
                            }
                        }.build()
                        val limit = if (data.getBoolean(
                                "stream",
                            )
                        ) {
                            DocumentWorkerWire.MAX_TRANSFER_BYTES
                        } else {
                            DocumentWorkerWire.MAX_RESPONSE_BYTES
                        }
                        val call = (testNetwork ?: client).newCall(request)
                        calls += call
                        call.enqueue(object : Callback {
                            override fun onFailure(call: Call, e: IOException) {
                                calls -= call
                                runCatching { replyNetwork(target, DocumentWorkerWire.NETWORK_FAILED, id) }
                            }
                            override fun onResponse(call: Call, response: Response) {
                                var output: ParcelFileDescriptor? = null
                                try {
                                    response.use { current ->
                                        require(current.body.contentLength() <= limit)
                                        val pipe = ParcelFileDescriptor.createPipe()
                                        output = pipe[1]
                                        descriptors += pipe[1]
                                        try {
                                            replyNetwork(
                                                target,
                                                DocumentWorkerWire.NETWORK_RESPONSE,
                                                id,
                                                Bundle().apply {
                                                    putInt("status", current.code)
                                                    putParcelable("body", pipe[0])
                                                },
                                            )
                                        } finally {
                                            pipe[0].close()
                                        }
                                        var transferred = 0L
                                        ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { sink ->
                                            current.body.byteStream().use { stream ->
                                                val buffer = ByteArray(32 * 1024)
                                                while (true) {
                                                    val count = stream.read(buffer)
                                                    if (count < 0) break
                                                    transferred += count
                                                    require(transferred <= limit)
                                                    sink.write(buffer, 0, count)
                                                }
                                            }
                                        }
                                        replyNetwork(
                                            target,
                                            DocumentWorkerWire.NETWORK_COMPLETE,
                                            id,
                                            Bundle().apply {
                                                putLong("transferred", transferred)
                                            },
                                        )
                                    }
                                } catch (_: Exception) {
                                    runCatching { replyNetwork(target, DocumentWorkerWire.NETWORK_FAILED, id) }
                                } finally {
                                    output?.let {
                                        descriptors -= it
                                        runCatching { it.close() }
                                    }
                                    calls -= call
                                }
                            }
                        })
                    } catch (_: Exception) {
                        runCatching { replyNetwork(target, DocumentWorkerWire.NETWORK_FAILED, id) }
                    }
                }
            }

            val receiver = Messenger(
                Handler(Looper.getMainLooper()) { message ->
                    val data = message.data
                    if (data.getString("token") != token ||
                        message.sendingUid < 0 ||
                        message.sendingUid == Process.myUid()
                    ) {
                        DocumentWorkerWire.descriptor(data, "body")?.close()
                        return@Handler true
                    }
                    when (message.what) {
                        DocumentWorkerWire.NETWORK -> network(message)
                        DocumentWorkerWire.RESULT, DocumentWorkerWire.FAILED -> {
                            val body = DocumentWorkerWire.descriptor(data, "body")
                            body?.let(descriptors::add)
                            if (!done.complete(Message.obtain(message))) {
                                body?.let {
                                    descriptors -= it
                                    it.close()
                                }
                            }
                        }
                    }
                    true
                },
            )
            val connection = object : ServiceConnection {
                override fun onServiceConnected(
                    name: ComponentName,
                    service: IBinder,
                ) {
                    connected.complete(Messenger(service))
                }
                override fun onServiceDisconnected(name: ComponentName) {
                    val failure = IllegalStateException("Document worker disconnected")
                    connected.completeExceptionally(failure)
                    done.completeExceptionally(failure)
                }
                override fun onNullBinding(
                    name: ComponentName,
                ) {
                    done.completeExceptionally(IllegalStateException("Document worker unavailable"))
                }
                override fun onBindingDied(name: ComponentName) {
                    onServiceDisconnected(name)
                }
            }
            var bound = false
            try {
                bound = withContext(Dispatchers.Main) {
                    app.bindService(
                        Intent(app, StudioDocumentWorkerService::class.java),
                        connection,
                        Context.BIND_AUTO_CREATE,
                    )
                }
                check(bound) { "Document worker unavailable" }
                val remote = connected.await()
                withContext(Dispatchers.IO) {
                    // Private immutable package versions are opened read-only; the worker verifies their exact hash.
                    val apk = ParcelFileDescriptor.open(invocation.apk, ParcelFileDescriptor.MODE_READ_ONLY)
                    try {
                        remote.send(
                            Message.obtain(null, DocumentWorkerWire.RUN).apply {
                                replyTo = receiver
                                data = Bundle().apply {
                                    putString("token", token)
                                    putString("operation", operation)
                                    putString("reference", reference)
                                    putString("resolver", invocation.resolverId)
                                    putString("sha256", invocation.sha256)
                                    putLong("source", invocation.sourceId)
                                    putParcelable("apk", apk)
                                    putStringArrayList("factories", ArrayList(invocation.factories))
                                    putString("hosts", JsonArray(invocation.hosts.map(::JsonPrimitive)).toString())
                                }
                            },
                        )
                    } finally {
                        apk.close()
                    }
                }
                val message = done.await()
                check(message.what == DocumentWorkerWire.RESULT) {
                    val stage = message.data.getString("stage").takeIf {
                        it in setOf(
                            "validate", "load", "capability", "resolve", "read_package", "verify_package",
                            "read_dex", "load_dex", "instantiate",
                        )
                    }.orEmpty()
                    val category = message.data.getString("category").takeIf {
                        it in
                            setOf(
                                "class_missing",
                                "linkage",
                                "security",
                                "invalid",
                                "state",
                                "io",
                                "reflection",
                                "failed",
                            )
                    }.orEmpty()
                    "The document component could not complete the operation ($stage:$category)"
                }
                val data = message.data
                check(data.getInt("uid") == message.sendingUid && data.getInt("uid") != Process.myUid())
                val metadata = DocumentWorkerWire.json(data, "metadata")
                val bytes = DocumentWorkerWire.descriptor(data, "body")?.let { body ->
                    descriptors += body
                    suspendCancellableCoroutine<ByteArray> { continuation ->
                        continuation.invokeOnCancellation { runCatching { body.close() } }
                        reader.execute {
                            try {
                                val bytes = ParcelFileDescriptor.AutoCloseInputStream(body).use {
                                    DocumentWorkerWire.read(it, DocumentWorkerWire.MAX_RESPONSE_BYTES)
                                }
                                if (continuation.isActive) continuation.resume(bytes)
                            } catch (failure: Exception) {
                                if (continuation.isActive) continuation.resumeWithException(failure)
                            } finally {
                                descriptors -= body
                            }
                        }
                    }.also {
                        require(
                            metadata.getValue("size").studioText().toInt() == it.size &&
                                metadata.getValue("sha256").studioText() == DocumentWorkerWire.hash(it),
                        ) {
                            "Document copy is incomplete"
                        }
                    }
                }
                require((operation == "document") == (bytes != null))
                validate()
                currentCoroutineContext().ensureActive()
                StudioDocumentReply(metadata, bytes, message.sendingUid)
            } finally {
                scope.cancel()
                calls.forEach(Call::cancel)
                descriptors.forEach { runCatching { it.close() } }
                reader.shutdownNow()
                if (bound) {
                    withContext(NonCancellable + Dispatchers.Main) {
                        runCatching { app.unbindService(connection) }
                    }
                }
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        }
    }

    private companion object {
        val worker = Mutex()
    }
}
