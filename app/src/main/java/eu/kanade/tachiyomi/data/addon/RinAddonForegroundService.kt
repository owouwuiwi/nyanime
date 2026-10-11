package eu.kanade.tachiyomi.data.addon

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.work.Worker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nyanime.addon.api.RinAddonHost
import nyanime.addon.api.RinAddonTask

/** Owns the Android lifecycle; authenticated addon code implements each task. */
class RinAddonForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loaded: RinAddonRuntime.Loaded? = null
    private var packageNameForTask: String? = null
    private var taskName: String? = null
    private var finished = false
    private var ownsLease = false
    private val notificationId = 29015

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "cancel") {
            if (intent.getStringExtra("addon") == packageNameForTask || packageNameForTask == null) cancel()
            return START_NOT_STICKY
        }
        if (packageNameForTask != null) return START_NOT_STICKY
        val initial = intent?.getParcelableExtra<Notification>("initial") ?: return stop().let { START_NOT_STICKY }
        startForeground(notificationId, initial)
        ownsLease = true
        packageNameForTask = intent.getStringExtra("addon")
        taskName = intent.getStringExtra("task")
        scope.launch {
            try {
                val item = withContext(Dispatchers.IO) {
                    RinAddonRuntime.load(this@RinAddonForegroundService, requireNotNull(packageNameForTask))
                }
                if (finished) return@launch
                loaded = item
                item.entry.startTask(
                    item.context,
                    requireNotNull(taskName),
                    intent.getBundleExtra("args") ?: android.os.Bundle(),
                    object : RinAddonTask {
                        override fun host(): RinAddonHost = item.host
                        override fun update(progress: Notification) {
                            scope.launch {
                                if (!finished && item.host.enabled()) {
                                    getSystemService(NotificationManager::class.java).notify(notificationId, progress)
                                } else if (!finished) {
                                    cancel()
                                }
                            }
                        }
                        override fun finish(result: Notification?) {
                            scope.launch {
                                if (finished) return@launch
                                finished = true
                                stopForeground(STOP_FOREGROUND_REMOVE)
                                if (result != null && item.host.enabled()) {
                                    getSystemService(NotificationManager::class.java)
                                        .notify(item.host.storageNamespace().hashCode(), result)
                                }
                                stop()
                            }
                        }
                    },
                )
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (_: LinkageError) {
                cancel()
            } catch (_: Exception) {
                cancel()
            }
        }
        return START_NOT_STICKY
    }

    private fun cancel() {
        if (!finished) loaded?.let { runCatching { it.entry.cancelTask(it.context, taskName.orEmpty()) } }
        finished = true
        scope.cancel()
        stop()
    }
    private fun stop() {
        if (ownsLease) {
            ownsLease = false
            RinAddonRuntime.busy.value = false
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        cancel()
    }
    override fun onDestroy() {
        cancel()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}

class RinAddonWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val packageName = inputData.getString("addon") ?: return Result.failure()
        val name = inputData.getString("task") ?: return Result.failure()
        if (eu.kanade.tachiyomi.data.rin.RinSessions.busy.value ||
            !RinAddonRuntime.busy.compareAndSet(false, true)
        ) {
            return Result.retry()
        }
        return try {
            val item = runCatching { RinAddonRuntime.load(applicationContext, packageName) }.getOrNull()
                ?: return Result.success()
            when (item.entry.runPeriodicTask(item.context, name, item.host)) {
                0 -> Result.success()
                1 -> Result.retry()
                else -> Result.failure()
            }
        } catch (_: LinkageError) {
            Result.failure()
        } catch (_: Exception) {
            Result.retry()
        } finally {
            RinAddonRuntime.busy.value = false
        }
    }
}
