package it.lorelore.nyanime.studio

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import app.cash.quickjs.QuickJs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Isolated UID, no host objects, network bridge, filesystem paths or Java reflection in JS. */
class StudioScriptService : Service() {
    override fun onCreate() {
        super.onCreate()
        check(Process.myUid() != applicationInfo.uid) { "The script worker requires an isolated UID" }
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val watchdog = Executors.newSingleThreadScheduledExecutor()
    private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            if (message.what != 1) return
            val code = message.data.getString("code").orEmpty()
            val input = message.data.getString("input").orEmpty()
            val reply = message.replyTo ?: return
            worker.execute {
                // Native evaluate may ignore thread interruption. Killing only this isolated UID
                // bounds an infinite loop or allocation without terminating the reader/player.
                val heapBefore = Debug.getNativeHeapAllocatedSize() + Runtime.getRuntime().totalMemory()
                val timeout = watchdog.schedule({ Process.killProcess(Process.myPid()) }, 2, TimeUnit.SECONDS)
                val memory = watchdog.scheduleAtFixedRate({
                    val heap = Debug.getNativeHeapAllocatedSize() + Runtime.getRuntime().totalMemory()
                    if (heap - heapBefore > 64L * 1024 * 1024) Process.killProcess(Process.myPid())
                }, 50, 50, TimeUnit.MILLISECONDS)
                val result = Bundle()
                try {
                    require(code.length <= 65_536 && input.toByteArray().size <= 196_608) { "Script input too large" }
                    QuickJs.create().use { engine ->
                        val quotedInput = JsonPrimitive(input).toString()
                        val evaluated = engine.evaluate(
                            "'use strict'; JSON.stringify((function(input){\n$code\n})(JSON.parse($quotedInput)))",
                            "user-source.js",
                        ) as? String ?: error("Return a JSON value")
                        require(evaluated.toByteArray().size <= 196_608) { "Script output too large" }
                        result.putString("value", evaluated)
                    }
                } catch (_: Throwable) {
                    // Never include script contents, API payloads or account information in logs.
                    result.putString("error", "Script failed or returned an unsupported value")
                } finally {
                    timeout.cancel(false)
                    memory.cancel(false)
                }
                runCatching { reply.send(Message.obtain(null, 2).apply { data = result }) }
            }
        }
    })
    override fun onBind(intent: Intent?): IBinder = messenger.binder
    override fun onDestroy() {
        worker.shutdownNow()
        watchdog.shutdownNow()
        super.onDestroy()
        // Cancellation must also stop native code that ignores thread interruption.
        Process.killProcess(Process.myPid())
    }
}

/** Allocated only for an approved SCRIPT block and unbound after its single invocation. */
class StudioScriptClient(context: Context) : StudioScript {
    private val context = context.applicationContext
    override suspend fun evaluate(code: String, input: JsonElement): JsonElement = withContext(Dispatchers.Main) {
        val bound = CompletableDeferred<Messenger>()
        val result = CompletableDeferred<String>()
        val reply = Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(message: Message) {
                message.data.getString("error")?.let { result.completeExceptionally(IllegalStateException(it)) }
                    ?: result.complete(message.data.getString("value") ?: "null")
            }
        })
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (service != null) bound.complete(Messenger(service))
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                result.completeExceptionally(IllegalStateException("Script stopped: time or memory limit"))
            }
            override fun onBindingDied(name: ComponentName?) {
                onServiceDisconnected(name)
            }
            override fun onNullBinding(
                name: ComponentName?,
            ) {
                bound.completeExceptionally(IllegalStateException("Script process unavailable"))
            }
        }
        var didBind = false
        try {
            withTimeout(5_000) {
                didBind =
                    context.bindService(
                        Intent(context, StudioScriptService::class.java),
                        connection,
                        Context.BIND_AUTO_CREATE,
                    )
                check(didBind) { "Script process unavailable" }
                bound.await().send(
                    Message.obtain(null, 1).apply {
                        replyTo = reply
                        data = Bundle().apply {
                            putString("code", code)
                            putString("input", input.toString())
                        }
                    },
                )
                StudioDocuments.json.parseToJsonElement(result.await())
            }
        } finally {
            if (didBind) context.unbindService(connection)
        }
    }
}
