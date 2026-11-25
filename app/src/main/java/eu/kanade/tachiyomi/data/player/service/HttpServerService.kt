package eu.kanade.tachiyomi.data.player.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.os.Build
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.animesource.model.HttpServer
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.notificationBuilder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import nyanime.extension.internal.layerHttpServer
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import tachiyomi.i18n.MR
import tachiyomi.i18n.aniyomi.AYMR
import uy.kohesive.injekt.injectLazy
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class HttpServerService : Service() {

    private var httpServer: HttpServer? = null
    private val sourceManager: AnimeSourceManager by injectLazy()

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (isRunning.value) {
            isRunning.update { _ -> false }
            httpServer?.stop()
        }

        val sourceId = intent?.getLongExtra(EXTRA_SOURCE_ID, -1L) ?: -1L
        val server = (sourceManager.get(sourceId) as? AnimeHttpSource)?.layerHttpServer()

        if (server == null) {
            stopSelf()
            return START_NOT_STICKY
        } else {
            httpServer = server
        }

        try {
            httpServer?.start()
            port = httpServer?.listeningPort ?: 0
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to start http server" }
            stopSelf()
            return START_NOT_STICKY
        }

        isRunning.update { _ -> true }
        val stopIntent = Intent(this, HttpServerService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStopIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = notificationBuilder(Notifications.CHANNEL_HTTP_SERVER) {
            setLargeIcon(BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher))
            setSmallIcon(R.drawable.ic_dns_24dp)
            addAction(
                R.drawable.ic_close_24dp,
                stringResource(MR.strings.action_close),
                pendingStopIntent,
            )
            setContentText(stringResource(AYMR.strings.http_server_is_running))
            setAutoCancel(false)
            setOngoing(true)
            setUsesChronometer(true)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                Notifications.ID_HTTP_SERVER,
                builder.build(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(Notifications.ID_HTTP_SERVER, builder.build())
        }

        return START_STICKY
    }

    override fun onDestroy() {
        isRunning.update { _ -> false }
        httpServer?.stop()
        super.onDestroy()
    }

    companion object {
        private const val NAME = "HttpServerSerivce"
        private val ACTION_STOP = "${BuildConfig.APPLICATION_ID}.$NAME.ACTION_STOP"
        const val EXTRA_SOURCE_ID = "$NAME.EXTRA.SOURCE.ID"

        private val isRunning = MutableStateFlow(false)

        @Volatile
        private var port: Int = 0

        internal suspend fun start(
            context: Context,
            sourceId: Long,
            timeout: Duration = 5.seconds,
        ): Pair<Boolean, Int> {
            isRunning.value = false
            context.startService(
                Intent(context, HttpServerService::class.java).putExtra(EXTRA_SOURCE_ID, sourceId),
            )
            val ready = withTimeoutOrNull(timeout) { isRunning.first { it } }
            return Pair(ready == true, port)
        }
    }
}
