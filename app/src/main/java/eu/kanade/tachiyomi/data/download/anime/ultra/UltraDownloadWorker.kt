package eu.kanade.tachiyomi.data.download.anime.ultra

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.download.service.DownloadPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/** Isolated from the player. Only a verified completed copy can replace the original at playback. */
@UnstableApi
class UltraDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val title = inputData.getString(TITLE).orEmpty()
    private val folderUri = inputData.getString(FOLDER).orEmpty()
    private val key = UltraTask.key(folderUri)
    private val store = UltraTaskStore.get(context)
    private var stage = "Preparazione Ultra"
    private var percent = 0

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val builder = NotificationCompat.Builder(applicationContext, Notifications.CHANNEL_DOWNLOADER_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Ultra · $title")
            .setContentText(stage)
            .setProgress(100, percent, percent == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        val animeId = store.tasks.value[key]?.animeId ?: -1
        if (animeId > 0) {
            builder.setContentIntent(NotificationReceiver.openAnimeEntryPendingActivity(applicationContext, animeId))
        }
        return ForegroundInfo(
            NOTIFICATION_ID,
            builder.build(),
            if (Build.VERSION.SDK_INT >= 35) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            },
        )
    }

    override suspend fun doWork(): Result {
        val result = runAttempt()
        if (result !is Result.Retry) return result
        return try {
            // The GPU and incomplete clip are already released before handing off to another worker.
            UltraDownloads.defer(applicationContext, key, id.toString())
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logcat(LogPriority.WARN, error) { "Unable to schedule the next Ultra resource check" }
            Result.retry()
        }
    }

    private suspend fun runAttempt(): Result = withContext(Dispatchers.IO) {
        store.load()
        store.change(key) {
            when {
                it == null -> UltraTask(folderUri, title, phase = UltraPhase.QUEUED, workId = id.toString())
                it.workId.isEmpty() && it.phase == UltraPhase.AVAILABLE -> it.copy(
                    phase = UltraPhase.QUEUED,
                    workId = id.toString(),
                )
                else -> it
            }
        }
        val task = store.tasks.value.getValue(key)
        if (!task.active || task.workId != id.toString()) return@withContext Result.success()
        percent = task.progress
        val control = UltraProcessingControl(
            applicationContext,
            Injekt.get<DownloadPreferences>(),
            cooling = task.coolingRequired,
        )
        val waiting = control.sample()
        if (waiting != null) {
            waitFor(waiting)
            return@withContext Result.retry()
        }
        store.updateWorker(key, id.toString()) { it.copy(coolingRequired = false, coolingUntil = 0) }
        if (!gpu.tryLock()) {
            waitFor("In coda · un altro episodio è in elaborazione")
            return@withContext Result.retry()
        }
        activeKey = key
        if (store.tasks.value[key]?.let { it.active && it.workId == id.toString() } != true) {
            activeKey = null
            gpu.unlock()
            return@withContext Result.success()
        }
        val scratch = UltraDownloads.scratch(applicationContext, key)
        val video = File(scratch, "video.mp4")
        var segments: UltraSegments? = null
        var folder: UniFile? = null
        var published = false
        var preserveCheckpoint = false
        var originalDuration = 0L
        try {
            folder = UniFile.fromUri(applicationContext, Uri.parse(folderUri))
            require(folder?.exists() == true) { "Il download originale non è più disponibile" }
            if (UltraFiles.completed(applicationContext, folder) != null) {
                update("Ultra pronto · riproducibile dalla scheda del titolo", 100, UltraPhase.READY)
                published = true
                return@withContext Result.success()
            }
            val original = requireNotNull(UltraFiles.original(folder)) { "Il download originale non è più disponibile" }
            stage = "Preparazione a basso consumo"
            setForeground(getForegroundInfo())
            File(applicationContext.cacheDir, "ultra").deleteRecursively()
            check(scratch.isDirectory || scratch.mkdirs()) { "Impossibile preparare lo spazio Ultra" }
            val originalMedia = UltraExporter.inspect(applicationContext, original.uri)
            originalDuration = originalMedia.durationMs
            val identity = "${original.uri}|${original.length()}|${original.lastModified()}|" +
                "$originalMedia|A+HQ-segments-v1"
            val identityFile = File(scratch, "input.txt")
            if (!identityFile.exists() || identityFile.readText() != identity) {
                scratch.listFiles().orEmpty().forEach { it.delete() }
                video.delete()
                identityFile.writeText(identity)
                percent = 0
            }
            video.delete()
            val (w, h) = UltraShaderGraph.target(originalMedia.width, originalMedia.height)
            val estimatedBytes = originalMedia.durationMs * (w.toLong() * h * 6).coerceIn(8_000_000, 60_000_000) / 8000
            require(scratch.usableSpace > estimatedBytes * 2 + 256L * 1024 * 1024) {
                "Spazio insufficiente per preparare Ultra; l'originale è conservato"
            }
            val planFile = File(scratch, "timeline.json")
            val plan = runCatching { Json.decodeFromString<List<Long>>(planFile.readText()) }.getOrNull()
                ?: UltraExporter.segmentPlan(applicationContext, original.uri, originalDuration).also {
                    planFile.writeText(Json.encodeToString(it))
                }
            val clips = UltraSegments(scratch, plan).also { segments = it }
            val completed = clips.completed()
            percent = (plan[completed] * 90 / plan.last()).toInt()
            update(if (completed > 0) "Ripresa dei segmenti salvati" else "Preparazione a basso consumo", percent)
            val started = android.os.SystemClock.elapsedRealtime()
            for (index in completed until clips.count) {
                control.sample()?.let { throw Deferred(it) }
                val start = plan[index]
                val end = plan[index + 1]
                UltraExporter.export(applicationContext, original.uri, video, start, end, control) { value ->
                    currentCoroutineContext().ensureActive()
                    control.sample()?.let { throw Deferred(it) }
                    if (value >= 0) {
                        val processed = start + (end - start) * value / 100
                        val label = "A+ HQ · ${time(processed)} di ${time(plan.last())}"
                        update(label, (processed * 90 / plan.last()).toInt())
                    }
                }
                currentCoroutineContext().ensureActive()
                clips.commit(index, video)
                if (index < clips.count - 1 && android.os.SystemClock.elapsedRealtime() - started > 20 * 60_000) {
                    throw Deferred("Pausa breve · ripresa automatica dai segmenti salvati")
                }
            }
            val media = UltraExporter.Media(w, h, originalDuration)
            update("Conservazione di audio e sottotitoli", 92)
            val muxed = File(scratch, "complete.mkv")
            UltraWorkMonitor.run(check = { control.sample()?.let { throw Deferred(it) } }) {
                UltraMuxer.remux(applicationContext, clips.concatFile(), original.uri, muxed)
            }
            val verified = UltraExporter.inspect(applicationContext, Uri.fromFile(muxed))
            check(
                verified.width == media.width &&
                    verified.height == media.height &&
                    kotlin.math.abs(verified.durationMs - originalMedia.durationMs) <=
                    (originalMedia.durationMs / 100).coerceIn(100L, 1500L),
            ) { "Verifica del file Ultra fallita" }
            currentCoroutineContext().ensureActive()
            update("Salvataggio nella cartella dell'episodio", 97)
            folder.findFile(UltraFiles.PART)?.delete()
            val temporary = checkNotNull(folder.createFile(UltraFiles.PART))
            applicationContext.contentResolver.openOutputStream(temporary.uri, "wt")!!.use { destination ->
                muxed.inputStream().use { source ->
                    val buffer = ByteArray(256 * 1024)
                    var lastResourceCheck = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastResourceCheck >= 500) {
                            control.sample()?.let { throw Deferred(it) }
                            lastResourceCheck = now
                        }
                        val read = source.read(buffer)
                        if (read == -1) break
                        destination.write(buffer, 0, read)
                    }
                }
            }
            check(temporary.length() == muxed.length()) { "Copia Ultra incompleta" }
            currentCoroutineContext().ensureActive()
            folder.findFile(UltraFiles.MARKER)?.delete()
            folder.findFile(UltraFiles.VIDEO)?.delete()
            check(temporary.renameTo(UltraFiles.VIDEO)) { "Impossibile salvare Ultra" }
            UltraFiles.markComplete(applicationContext, folder, checkNotNull(folder.findFile(UltraFiles.VIDEO)), media)
            published = true
            update("Ultra pronto · riproducibile dalla scheda del titolo", 100, UltraPhase.READY)
            Result.success()
        } catch (cancelled: CancellationException) {
            preserveCheckpoint = true
            throw cancelled
        } catch (error: Exception) {
            val reason = (error as? Deferred)?.reason ?: control.sample()
            preserveCheckpoint = true
            if (reason != null) {
                waitFor(reason)
                Result.retry()
            } else {
                val message = generateSequence<Throwable>(error) { it.cause }.last().message?.take(240)
                    ?: "Il dispositivo non supporta questa conversione"
                update(message, percent, UltraPhase.FAILED)
                Result.success(workDataOf(ERROR to message))
            }
        } finally {
            control.stop()
            try {
                withContext(NonCancellable + Dispatchers.IO) {
                    if (published) store.recordPublished(key, id.toString())
                    val current = store.tasks.value[key]
                    val discarded = current == null || current.phase == UltraPhase.CANCELLED
                    if (preserveCheckpoint && !discarded && originalDuration > 0) {
                        video.delete()
                        File(scratch, "complete.mkv").delete()
                        val saved = segments?.let {
                            (it.boundariesUs[it.completed()] * 90 / it.boundariesUs.last()).toInt()
                        } ?: 0
                        store.change(key) {
                            if (it?.workId != id.toString()) {
                                it
                            } else {
                                it.copy(
                                    progress = saved,
                                    phase = if (it.phase == UltraPhase.RUNNING) UltraPhase.WAITING else it.phase,
                                    message = if (it.phase ==
                                        UltraPhase.RUNNING
                                    ) {
                                        "In attesa · ripresa dal punto salvato"
                                    } else {
                                        it.message
                                    },
                                )
                            }
                        }
                    } else if (published || discarded) {
                        scratch.deleteRecursively()
                    }
                    if (!published && folder?.let { UltraFiles.completed(applicationContext, it) } == null) {
                        folder?.findFile(UltraFiles.PART)?.delete()
                        folder?.findFile(UltraFiles.MARKER)?.delete()
                        folder?.findFile(UltraFiles.VIDEO)?.delete()
                    }
                }
            } finally {
                activeKey = null
                gpu.unlock()
            }
        }
    }

    private fun time(microseconds: Long): String =
        "%d:%02d".format(microseconds / 60_000_000, microseconds / 1_000_000 % 60)

    private suspend fun waitFor(reason: String) {
        store.updateWorker(key, id.toString()) {
            it.copy(
                phase = UltraPhase.WAITING,
                message = reason,
                coolingRequired = it.coolingRequired || reason == UltraProcessingPolicy.COOLING,
                // Scheduling supplies the cooling interval; wall-clock changes cannot extend it.
                coolingUntil = 0,
            )
        }
    }

    private suspend fun update(message: String, progress: Int, phase: UltraPhase = UltraPhase.RUNNING) {
        if (stage == message &&
            percent == progress &&
            phase == UltraPhase.RUNNING &&
            store.tasks.value[key]?.phase == UltraPhase.RUNNING
        ) {
            return
        }
        stage = message
        percent = progress
        store.updateWorker(key, id.toString()) {
            it.copy(phase = phase, progress = progress, message = message, updatedAt = System.currentTimeMillis())
        }
        setProgress(workDataOf(TITLE to title, STAGE to stage, PROGRESS to percent))
        setForeground(getForegroundInfo())
    }

    private class Deferred(val reason: String) : Exception(reason)

    companion object {
        const val TAG = "Anime4KUltra"
        const val TITLE = "title"
        const val FOLDER = "folder"
        const val STAGE = "stage"
        const val PROGRESS = "progress"
        const val ERROR = "error"
        private const val NOTIFICATION_ID = -230
        internal val gpu = Mutex()

        @Volatile internal var activeKey: String? = null

        suspend fun enqueue(context: Context, folder: Uri, title: String, animeId: Long = -1, episodeId: Long = -1) =
            UltraDownloads.enqueue(context, UltraTask(folder.toString(), title, animeId, episodeId))
    }
}
