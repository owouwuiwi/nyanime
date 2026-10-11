package it.lorelore.nyanime.exchange

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.main.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** Ephemeral, non-exported process. No host database, extension loader or application DI. */
class SourceExchangeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handle = AtomicLong()
    private var operation: Job? = null
    private var expiry: Job? = null
    private var peer: Messenger? = null
    private var snapshot = ExchangeSnapshot()
    private val inbox = Messenger(
        Handler(Looper.getMainLooper()) { message ->
            message.replyTo?.let { peer = it }
            when (message.what) {
                ExchangeProtocol.OBSERVE -> {
                    peer = message.replyTo
                    publish(snapshot)
                }
                ExchangeProtocol.SHARE, ExchangeProtocol.RECEIVE ->
                    if (operation?.isCompleted != false) start(message) else publish(snapshot)
                ExchangeProtocol.STOP -> stop()
            }
            true
        },
    )

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.exchange_title), NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onBind(intent: Intent?): IBinder = inbox.binder
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stop() else foreground()
        return START_NOT_STICKY
    }

    private fun foreground() {
        val stop = PendingIntent.getService(
            this,
            8244,
            Intent(this, SourceExchangeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            this,
            8244,
            Intent(
                this,
                MainActivity::class.java,
            ).setAction(Intent.ACTION_VIEW).setData(Uri.parse("nyanime://sources/v1")),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        startForeground(
            NOTIFICATION,
            NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_foreground).setContentTitle(getString(R.string.exchange_title))
                .setContentText(
                    getString(
                        if (snapshot.phase ==
                            ExchangePhase.SHARING
                        ) {
                            R.string.exchange_keep_open
                        } else {
                            R.string.exchange_working
                        },
                    ),
                )
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .addAction(0, getString(R.string.exchange_stop), stop).build(),
        )
    }

    private fun publish(value: ExchangeSnapshot) {
        snapshot = value
        runCatching {
            peer?.send(
                Message.obtain(null, ExchangeProtocol.SNAPSHOT).apply {
                    data =
                        Bundle().apply { putString("state", ExchangeProtocol.json.encodeToString(value)) }
                },
            )
        }.onFailure { peer = null }
    }

    private fun start(message: Message) {
        val data = message.data
        val internet = data.getBoolean("internet")
        val sharing = message.what == ExchangeProtocol.SHARE
        val incoming = data.getString("ticket").orEmpty()
        val source = data.getString("uri").orEmpty()
        val name = data.getString("name").orEmpty()
        val expiresAt = SystemClock.elapsedRealtime() + ExchangeProtocol.LIFETIME
        publish(
            ExchangeSnapshot(
                if (sharing) ExchangePhase.PREPARING else ExchangePhase.CONNECTING,
                name = name,
                internet = internet,
                expiresAt = expiresAt,
            ),
        )
        foreground()
        expiry?.cancel()
        expiry = scope.launch {
            delay(ExchangeProtocol.LIFETIME)
            stop()
        }
        operation = scope.launch {
            var directory: File? = null
            var keep = false
            try {
                withContext(Dispatchers.IO) {
                    NativeExchange.load(this@SourceExchangeService)
                    directory = ExchangeStorage.newSession(this@SourceExchangeService)
                }
                val session = requireNotNull(directory)
                if (sharing) {
                    val staged = withContext(Dispatchers.IO) {
                        ExchangeStorage.copy(this@SourceExchangeService, Uri.parse(source), session, name)
                    }
                    val ticket = withContext(Dispatchers.IO) {
                        val engine = NativeExchange.open(File(session, "store").path, true, internet)
                        handle.set(engine)
                        NativeExchange.share(engine, staged.path, name)
                    }
                    publish(snapshot.copy(phase = ExchangePhase.SHARING, ticket = ticket, expected = staged.length()))
                    foreground()
                    // The ticket remains usable only while this explicit foreground session is open.
                    delay(ExchangeProtocol.LIFETIME)
                } else {
                    val ticket = ExchangeProtocol.ticket(incoming)
                    val metadata =
                        withContext(Dispatchers.IO) {
                            ExchangeProtocol.json.parseToJsonElement(NativeExchange.inspect(ticket)).jsonObject
                        }
                    val expected = metadata["bytes"]?.jsonPrimitive?.longOrNull
                    publish(
                        snapshot.copy(name = metadata["name"]?.jsonPrimitive?.content.orEmpty(), expected = expected),
                    )
                    withContext(Dispatchers.IO) {
                        handle.set(NativeExchange.open(File(session, "store").path, false, internet))
                    }
                    publish(snapshot.copy(phase = ExchangePhase.RECEIVING))
                    val progress = launch {
                        while (true) {
                            delay(350)
                            val bytes = withContext(Dispatchers.IO) { NativeExchange.progress(handle.get()) }
                            publish(snapshot.copy(bytes = bytes.coerceAtLeast(snapshot.bytes)))
                        }
                    }
                    val received = try {
                        withContext(Dispatchers.IO) {
                            ExchangeProtocol.json.decodeFromString<List<ExchangeFile>>(
                                NativeExchange.receive(handle.get(), ticket, File(session, "received").path),
                            )
                        }
                    } finally {
                        progress.cancel()
                    }
                    require(received.isNotEmpty() && received.size <= 32)
                    withContext(Dispatchers.IO) {
                        received.forEach { ExchangeStorage.received(this@SourceExchangeService, it) }
                    }
                    keep = true
                    publish(
                        snapshot.copy(
                            phase = ExchangePhase.REVIEW,
                            files = received,
                            bytes = received.sumOf {
                                it.bytes
                            },
                            ticket = "",
                        ),
                    )
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                publish(
                    snapshot.copy(
                        phase = ExchangePhase.FAILED,
                        ticket = "",
                        error = when {
                            error is UnsatisfiedLinkError -> "engine"
                            error.message == "space" -> "space"
                            error is IllegalArgumentException -> "file"
                            else -> "transfer"
                        },
                    ),
                )
            } finally {
                expiry?.cancel()
                finishExchangeSession(close = {
                    val engine = handle.getAndSet(0)
                    runCatching { NativeExchange.cancel(engine) }
                    // close(0) also releases a runtime created by a failed open.
                    runCatching { NativeExchange.close(engine) }
                    if (!keep) directory?.deleteRecursively()
                }, finished = {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    if (snapshot.busy) publish(ExchangeSnapshot(ExchangePhase.STOPPED))
                })
            }
        }
    }

    private fun stop() {
        // Cancellation enters JNI separately from the blocking receive operation.
        val current = handle.get()
        if (current != 0L) scope.launch(Dispatchers.IO) { runCatching { NativeExchange.cancel(current) } }
        operation?.cancel()
        if (operation == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        expiry?.cancel()
        val current = handle.get()
        if (current != 0L) runCatching { NativeExchange.cancel(current) }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "it.lorelore.nyanime.exchange.STOP"
        private const val CHANNEL = "source-exchange"
        private const val NOTIFICATION = 8244
    }
}
