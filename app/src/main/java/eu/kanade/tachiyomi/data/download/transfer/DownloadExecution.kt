package eu.kanade.tachiyomi.data.download.transfer

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.WorkManager
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.download.anime.AnimeDownloadJob
import eu.kanade.tachiyomi.data.download.anime.AnimeDownloadManager
import eu.kanade.tachiyomi.data.download.anime.model.AnimeDownload
import eu.kanade.tachiyomi.data.download.manga.MangaDownloadJob
import eu.kanade.tachiyomi.data.download.manga.MangaDownloadManager
import eu.kanade.tachiyomi.data.download.manga.model.MangaDownload
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.activeNetworkState
import eu.kanade.tachiyomi.util.system.notificationBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tachiyomi.domain.download.service.DownloadPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

enum class DownloadKind(val jobId: Int) { ANIME(19821), MANGA(19822) }

/** Execution adapters share the existing managers. They never own a second queue. */
object DownloadExecution {
    private val scheduling = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scheduleLocks = DownloadKind.entries.associateWith { Mutex() }
    private val executionLocks = DownloadKind.entries.associateWith { Mutex() }
    private var observingPreferences = false
    private val running = MutableStateFlow(emptySet<DownloadKind>())
    private fun scheduler(context: Context) = context.getSystemService(JobScheduler::class.java)
    fun isUserJob(context: Context, kind: DownloadKind) = Build.VERSION.SDK_INT >= 34 &&
        scheduler(context).getPendingJob(kind.jobId) != null
    fun userJobFlow(kind: DownloadKind) = running.map { kind in it }

    fun enqueue(context: Context, kind: DownloadKind, userInitiated: Boolean, fallback: () -> Unit) {
        observePreferences(context.applicationContext)
        scheduling.launch {
            scheduleLocks.getValue(kind).withLock {
                if (Injekt.get<DownloadPreferences>().downloadManuallyPaused(
                        kind == DownloadKind.ANIME,
                    ).get()
                ) {
                    return@withLock
                }
                waitingReason(context)?.let { reason ->
                    if (kind == DownloadKind.ANIME) {
                        Injekt.get<AnimeDownloadManager>().downloaderWait(reason)
                    } else {
                        Injekt.get<MangaDownloadManager>().downloaderWait(reason)
                    }
                }
                if (isUserJob(context, kind)) return@withLock
                val workName = if (kind == DownloadKind.ANIME) "AnimeDownloader" else "MangaDownloader"
                val existingWorker = WorkManager.getInstance(context).getWorkInfosForUniqueWork(workName).get().any {
                    !it.state.isFinished
                }
                if (!existingWorker && start(context, kind, userInitiated)) return@withLock
                // WorkManager serializes cancel and KEEP-enqueue operations, including rapid pause/resume.
                fallback()
            }
        }
    }

    @Synchronized
    private fun observePreferences(context: Context) {
        if (observingPreferences) return
        observingPreferences = true
        scheduling.launch {
            Injekt.get<DownloadPreferences>().downloadOnlyOverWifi().changes().drop(1).collect {
                val anime = Injekt.get<AnimeDownloadManager>()
                val manga = Injekt.get<MangaDownloadManager>()
                anime.awaitReady()
                manga.awaitReady()
                val preferences = Injekt.get<DownloadPreferences>()
                if (anime.queueState.value.isNotEmpty() && !preferences.downloadManuallyPaused(true).get()) {
                    anime.downloaderWait(TransferWait.SYSTEM)
                    AnimeDownloadJob.stop(context)
                    AnimeDownloadJob.start(context, userInitiated = false)
                }
                if (manga.queueState.value.isNotEmpty() && !preferences.downloadManuallyPaused(false).get()) {
                    manga.downloaderWait(TransferWait.SYSTEM)
                    MangaDownloadJob.stop(context)
                    MangaDownloadJob.start(context, userInitiated = false)
                }
            }
        }
    }

    fun start(context: Context, kind: DownloadKind, userInitiated: Boolean): Boolean {
        if (isUserJob(context, kind)) return true
        if (!userInitiated ||
            Build.VERSION.SDK_INT < 34 ||
            !ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        ) {
            return false
        }
        val network = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).apply {
            if (Injekt.get<DownloadPreferences>().downloadOnlyOverWifi().get()) {
                addTransportType(
                    NetworkCapabilities.TRANSPORT_WIFI,
                )
            }
        }.build()
        val info = JobInfo.Builder(kind.jobId, ComponentName(context, UserDownloadService::class.java))
            .setUserInitiated(
                true,
            ).setPersisted(true).setRequiredNetwork(network).setRequiresStorageNotLow(true).build()
        return runCatching { scheduler(context).schedule(info) == JobScheduler.RESULT_SUCCESS }.getOrDefault(false)
    }

    fun stop(context: Context, kind: DownloadKind) {
        scheduler(context).cancel(kind.jobId)
    }

    private fun waitingReason(context: Context): TransferWait? {
        val network = context.activeNetworkState()
        return when {
            DownloadPlaybackGate.active.value -> TransferWait.PLAYBACK
            !network.isOnline -> TransferWait.NETWORK
            Injekt.get<DownloadPreferences>().downloadOnlyOverWifi().get() && !network.isWifi -> TransferWait.WIFI
            else -> null
        }
    }

    @Synchronized
    fun running(kind: DownloadKind, value: Boolean) {
        running.value =
            if (value) running.value + kind else running.value - kind
    }

    suspend fun run(context: Context, kind: DownloadKind) = executionLocks.getValue(kind).withLock {
        val preferences = Injekt.get<DownloadPreferences>()
        val anime = if (kind == DownloadKind.ANIME) Injekt.get<AnimeDownloadManager>() else null
        val manga = if (kind == DownloadKind.MANGA) Injekt.get<MangaDownloadManager>() else null
        anime?.awaitReady()
        manga?.awaitReady()
        var wait: TransferWait? = null
        try {
            while (!preferences.downloadManuallyPaused(kind == DownloadKind.ANIME).get()) {
                val hasPending =
                    anime?.queueState?.value?.any { it.status.value <= AnimeDownload.State.DOWNLOADING.value }
                        ?: manga!!.queueState.value.any { it.status.value <= MangaDownload.State.DOWNLOADING.value }
                if (!hasPending) break
                val reason = waitingReason(context)
                if (reason != null) {
                    if (reason != wait) {
                        anime?.downloaderWait(reason)
                        manga?.downloaderWait(reason)
                    }
                } else if (wait != null || !(anime?.isRunning ?: manga!!.isRunning)) {
                    anime?.awaitStopped()
                    manga?.awaitStopped()
                    anime?.downloaderStart()
                    manga?.downloaderStart()
                }
                wait = reason
                delay(250)
            }
        } finally {
            // System interruption retains QUEUE and partial files. Manual pause has its own preference.
            val reason = if (preferences.downloadManuallyPaused(
                    kind == DownloadKind.ANIME,
                ).get()
            ) {
                TransferWait.MANUAL
            } else {
                waitingReason(context) ?: TransferWait.SYSTEM
            }
            anime?.downloaderWait(reason)
            manga?.downloaderWait(reason)
        }
    }
}

class UserDownloadService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val jobs = mutableMapOf<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        if (Build.VERSION.SDK_INT < 34) return false
        val kind = DownloadKind.entries.firstOrNull { it.jobId == params.jobId } ?: return false
        val notification = notificationBuilder(Notifications.CHANNEL_DOWNLOADER_PROGRESS) {
            setContentTitle(getString(R.string.download_notifier_downloader_title))
            setSmallIcon(android.R.drawable.stat_sys_download)
            setOnlyAlertOnce(true)
            setOngoing(true)
        }.build()
        val id = if (kind ==
            DownloadKind.ANIME
        ) {
            Notifications.ID_DOWNLOAD_EPISODE_PROGRESS
        } else {
            Notifications.ID_DOWNLOAD_CHAPTER_PROGRESS
        }
        setNotification(params, id, notification, JOB_END_NOTIFICATION_POLICY_REMOVE)
        DownloadExecution.running(kind, true)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                DownloadExecution.run(applicationContext, kind)
            } finally {
                if (jobs[params.jobId] === currentCoroutineContext()[Job]) {
                    DownloadExecution.running(kind, false)
                    jobs.remove(params.jobId)
                    jobFinished(params, false)
                }
            }
        }
        jobs[params.jobId] = job
        job.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        jobs.remove(params.jobId)?.cancel()
        val kind = DownloadKind.entries.firstOrNull { it.jobId == params.jobId } ?: return false
        DownloadExecution.running(kind, false)
        if (Build.VERSION.SDK_INT >= 31 && params.stopReason == JobParameters.STOP_REASON_USER) {
            Injekt.get<DownloadPreferences>().downloadManuallyPaused(kind == DownloadKind.ANIME).set(true)
        }
        return !Injekt.get<DownloadPreferences>().downloadManuallyPaused(kind == DownloadKind.ANIME).get()
    }

    override fun onDestroy() {
        jobs.values.toList().forEach { it.cancel() }
        super.onDestroy()
    }
}
