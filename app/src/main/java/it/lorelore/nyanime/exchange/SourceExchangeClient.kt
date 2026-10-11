package it.lorelore.nyanime.exchange

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A small IPC client survives navigation; the native engine lives entirely in its worker. */
internal class SourceExchangeClient private constructor(private val context: Context) {
    private val current = MutableStateFlow(ExchangeSnapshot())
    val state = current.asStateFlow()
    private var remote: Messenger? = null
    private var bound = false
    private var pending: Message? = null
    private val reply = Messenger(
        Handler(Looper.getMainLooper()) { message ->
            if (message.what == ExchangeProtocol.SNAPSHOT) {
                val encoded = message.data.getString("state").orEmpty()
                if (encoded.length <= 128 * 1024) {
                    runCatching { ExchangeProtocol.json.decodeFromString<ExchangeSnapshot>(encoded) }.getOrNull()?.let {
                        current.value = it
                        if (!it.busy && it.phase != ExchangePhase.IDLE) disconnect()
                    }
                }
            }
            true
        },
    )
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (!bound) return
            remote = Messenger(service)
            val command = pending ?: Message.obtain(null, ExchangeProtocol.OBSERVE)
            pending = null
            send(command)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
            if (current.value.busy) current.value = ExchangeSnapshot(ExchangePhase.FAILED, error = "transfer")
            disconnect()
        }
        override fun onBindingDied(name: ComponentName?) = onServiceDisconnected(name)
        override fun onNullBinding(name: ComponentName?) = onServiceDisconnected(name)
    }

    fun observe() {
        if (current.value.phase == ExchangePhase.IDLE || current.value.busy) connect()
    }

    fun leave() {
        if (!current.value.busy) disconnect()
    }

    fun share(uri: Uri, name: String, internet: Boolean) {
        if (current.value.busy) return
        ExchangeProtocol.filename(name)
        start(
            Message.obtain(null, ExchangeProtocol.SHARE).apply {
                data = Bundle().apply {
                    putString("uri", uri.toString())
                    putString("name", name)
                    putBoolean("internet", internet)
                }
            },
            name,
        )
    }

    fun receive(ticket: String, internet: Boolean) {
        if (current.value.busy) return
        val parsed = ExchangeProtocol.ticket(ticket)
        start(
            Message.obtain(null, ExchangeProtocol.RECEIVE).apply {
                data = Bundle().apply {
                    putString("ticket", parsed)
                    putBoolean("internet", internet)
                }
            },
            "",
        )
    }

    fun stop() {
        if (remote != null) {
            send(Message.obtain(null, ExchangeProtocol.STOP))
        } else {
            pending = null
            current.value = ExchangeSnapshot(ExchangePhase.STOPPED)
            context.stopService(Intent(context, SourceExchangeService::class.java))
            disconnect()
        }
    }

    private fun start(message: Message, name: String) {
        current.value = ExchangeSnapshot(ExchangePhase.PREPARING, name = name)
        try {
            ContextCompat.startForegroundService(context, Intent(context, SourceExchangeService::class.java))
            if (remote != null) {
                send(message)
            } else {
                pending = message
                connect()
            }
        } catch (_: Exception) {
            pending = null
            current.value = ExchangeSnapshot(ExchangePhase.FAILED, error = "transfer")
            disconnect()
        }
    }

    private fun send(message: Message) {
        try {
            message.replyTo = reply
            remote?.send(message)
        } catch (_: Exception) {
            current.value = ExchangeSnapshot(ExchangePhase.FAILED, error = "transfer")
            disconnect()
        }
    }

    private fun connect() {
        if (bound) return
        bound =
            context.bindService(
                Intent(context, SourceExchangeService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        if (!bound) current.value = ExchangeSnapshot(ExchangePhase.FAILED, error = "engine")
    }

    private fun disconnect() {
        if (bound) {
            bound = false
            runCatching { context.unbindService(connection) }
        }
        remote = null
    }

    companion object {
        @Volatile private var instance: SourceExchangeClient? = null
        fun get(context: Context): SourceExchangeClient = instance ?: synchronized(this) {
            instance ?: SourceExchangeClient(context.applicationContext).also { instance = it }
        }
    }
}
