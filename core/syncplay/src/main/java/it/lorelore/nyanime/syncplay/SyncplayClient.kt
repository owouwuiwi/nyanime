package it.lorelore.nyanime.syncplay

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

data class SyncplayTimeline(val remote: SyncplayRemote, val receivedAtMillis: Long)

/** One connection per instance. Closing the retained TCP transport interrupts TLS and blocked I/O. */
class SyncplayClient(private val options: SyncplayOptions) {
    private sealed interface Command {
        data class Change(val local: SyncplayLocal, val seek: Boolean) : Command
        data class Chat(val value: String) : Command
    }
    private val commands = Channel<Command>(16)
    private val local = AtomicReference(SyncplayLocal())
    private val transport = AtomicReference<Socket?>()
    private val activeSocket = AtomicReference<Socket?>()
    private val writingAt = AtomicLong()
    private val lastRead = AtomicLong()
    private val mutableState = MutableStateFlow(SyncplayState())
    val state = mutableState.asStateFlow()
    private val mutableTimeline = MutableStateFlow<SyncplayTimeline?>(null)
    val timeline = mutableTimeline.asStateFlow()
    private var task: Job? = null
    private var used = false

    @Synchronized fun start(scope: CoroutineScope) {
        check(!used)
        options.validate()
        used = true
        mutableState.value = SyncplayState(phase = SyncplayPhase.Connecting, room = options.room)
        task = scope.launch { runConnection() }
    }

    fun observe(value: SyncplayLocal) {
        require(value.valid())
        local.set(value)
    }
    fun change(value: SyncplayLocal, seek: Boolean = false): Boolean =
        value.valid() && value.file.isNotBlank() && commands.trySend(Command.Change(value, seek)).isSuccess
    fun chat(value: String): Boolean = value.isNotBlank() &&
        value.length <= 150 &&
        value.none(Char::isISOControl) &&
        commands.trySend(Command.Chat(value)).isSuccess

    fun close() {
        task?.cancel()
        closeTransport()
        commands.close()
        mutableState.value = SyncplayState()
        mutableTimeline.value = null
    }

    private fun closeTransport() {
        runCatching { transport.getAndSet(null)?.close() }
        runCatching { activeSocket.getAndSet(null)?.close() }
    }

    private suspend fun runConnection() {
        try {
            withContext(Dispatchers.IO) {
                coroutineScope {
                    val plain = Socket()
                    transport.set(plain)
                    plain.tcpNoDelay = true
                    plain.sendBufferSize = 8192
                    plain.connect(InetSocketAddress(options.endpoint.host, options.endpoint.port), 10000)
                    plain.soTimeout = 10000
                    var socket: Socket = plain
                    if (options.tls) {
                        write(plain, buildJsonObject { put("TLS", buildJsonObject { put("startTLS", "send") }) })
                        val response = readLine(plain.getInputStream(), 2048)
                        if (SyncplayProtocol.parse(response).obj("TLS")?.text("startTLS") != "true") {
                            throw SyncplayFailure("tls")
                        }
                        val secure = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                            .createSocket(plain, options.endpoint.host, options.endpoint.port, false) as SSLSocket
                        activeSocket.set(secure)
                        secure.sslParameters = secure.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                        secure.soTimeout = 10000
                        secure.startHandshake()
                        socket = secure
                    } else {
                        activeSocket.set(plain)
                    }
                    ensureActive()
                    socket.soTimeout = 250
                    val protocol =
                        SyncplayProtocol(options, { System.currentTimeMillis() / 1000.0 }, { now() / 1000.0 })
                    write(socket, protocol.hello())
                    lastRead.set(now())
                    val watchdog = launch(Dispatchers.Default) {
                        while (isActive) {
                            delay(500)
                            val writing = writingAt.get()
                            if (now() - lastRead.get() > 12000 || writing > 0 && now() - writing > 5000) {
                                closeTransport()
                                break
                            }
                        }
                    }
                    try {
                        val reader = BoundedLineReader(socket.getInputStream())
                        var heartbeatAt = 0L
                        var file: Pair<String, Double>? = null
                        var ready: Boolean? = null
                        while (isActive) {
                            repeat(8) {
                                val command = commands.tryReceive().getOrNull() ?: return@repeat
                                if (protocol.state.phase != SyncplayPhase.Connected) return@repeat
                                when (command) {
                                    is Command.Change -> write(
                                        socket,
                                        protocol.outgoing(command.local, true, command.seek),
                                    )
                                    is Command.Chat -> if (protocol.state.chat) {
                                        write(
                                            socket,
                                            SyncplayProtocol.chat(command.value),
                                        )
                                    }
                                }
                            }
                            if (protocol.state.phase == SyncplayPhase.Connected) {
                                val sample = local.get()
                                val currentFile = sample.file to sample.duration
                                if (file != currentFile) {
                                    write(socket, protocol.file(sample))
                                    write(socket, SyncplayProtocol.list())
                                    file = currentFile
                                }
                                if (protocol.state.readiness && ready != sample.ready) {
                                    write(socket, protocol.readiness(sample.ready))
                                    ready = sample.ready
                                }
                                if (now() - heartbeatAt >= 900) {
                                    write(socket, protocol.outgoing(sample))
                                    heartbeatAt = now()
                                }
                            }
                            val line = try {
                                reader.next()
                            } catch (_: SocketTimeoutException) {
                                null
                            }
                            if (line != null) {
                                lastRead.set(now())
                                val timeline = protocol.receive(SyncplayProtocol.parse(line))
                                mutableState.value = protocol.state
                                if (timeline != null) mutableTimeline.value = SyncplayTimeline(timeline, now())
                            }
                        }
                    } finally {
                        watchdog.cancel()
                    }
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            val error = when (failure) {
                is SyncplayFailure -> failure.code
                is SSLException -> "tls"
                else -> "connection"
            }
            mutableState.value = mutableState.value.copy(phase = SyncplayPhase.Failed, error = error)
        } finally {
            closeTransport()
        }
    }

    private fun write(socket: Socket, value: JsonObject) {
        val bytes = (value.toString() + "\r\n").toByteArray(Charsets.UTF_8)
        require(bytes.size <= 65536)
        writingAt.set(now())
        try {
            socket.getOutputStream().apply {
                write(bytes)
                flush()
            }
        } finally {
            writingAt.set(0)
        }
    }

    companion object {
        fun now(): Long = System.nanoTime() / 1_000_000
        private fun readLine(stream: InputStream, limit: Int): String = BoundedLineReader(stream, limit).next()
    }
}

/** Retains partial lines across read timeouts and does not read ahead across STARTTLS. */
internal class BoundedLineReader(private val input: InputStream, private val limit: Int = 65536) {
    private val pending = ByteArrayOutputStream()
    fun next(): String {
        while (true) {
            val byte = input.read()
            if (byte == -1) throw EOFException()
            if (byte == 10) {
                val bytes = pending.toByteArray()
                pending.reset()
                return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString().trimEnd('\r')
            }
            if (pending.size() >= limit) throw SyncplayFailure("protocol")
            pending.write(byte)
        }
    }
}
