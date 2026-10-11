package eu.kanade.tachiyomi.data.download.anime.ultra

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.hippo.unifile.UniFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import tachiyomi.domain.download.service.DownloadPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.concurrent.TimeUnit

internal object UltraDownloads {
    private val commands = Mutex()
    fun scratch(context: Context, key: String): File {
        require(key.matches(Regex("[a-f0-9]{64}")))
        return File(context.noBackupFilesDir, "ultra/work/$key")
    }

    fun observe(context: Context) = flow {
        val store = UltraTaskStore.get(context)
        store.load()
        emitAll(
            combine(store.tasks, WorkManager.getInstance(context).getWorkInfosByTagFlow(UltraDownloadWorker.TAG)) {
                    _,
                    jobs,
                ->
                for (job in jobs) {
                    val folder = job.tags.firstOrNull { it.startsWith("ultra-folder:") }
                        ?.removePrefix("ultra-folder:") ?: continue
                    val key = UltraTask.key(folder)
                    val existing = store.tasks.value[key]
                    if (existing == null) {
                        if ("ultra-journal-v1" in job.tags || job.state == WorkInfo.State.CANCELLED) continue
                        val title = job.tags.firstOrNull { it.startsWith("ultra-title:") }
                            ?.removePrefix("ultra-title:").orEmpty()
                        val directory = UniFile.fromUri(context, folder.toUri())
                        if (directory?.exists() != true) continue
                        val ready = UltraFiles.completed(context, directory) != null
                        store.change(key) {
                            it ?: UltraTask(
                                folder,
                                title,
                                workId = job.id.toString(),
                                phase = when {
                                    ready -> UltraPhase.READY
                                    job.state == WorkInfo.State.CANCELLED -> UltraPhase.CANCELLED
                                    job.state.isFinished -> UltraPhase.FAILED
                                    else -> UltraPhase.WAITING
                                },
                                progress = if (ready) 100 else job.progress.getInt(UltraDownloadWorker.PROGRESS, 0),
                                message = when {
                                    ready -> "Ultra pronto"
                                    job.state.isFinished -> job.outputData.getString(UltraDownloadWorker.ERROR)
                                        ?.takeIf { it.isNotBlank() }
                                        ?: "Elaborazione interrotta · originale disponibile"
                                    else -> "In coda · attendi le condizioni di elaborazione"
                                },
                            )
                        }
                    } else if (existing.workId.isBlank() &&
                        existing.phase == UltraPhase.AVAILABLE &&
                        !job.state.isFinished
                    ) {
                        store.change(key) {
                            if (it?.workId?.isBlank() == true) {
                                it.copy(
                                    workId = job.id.toString(),
                                    phase = UltraPhase.WAITING,
                                    message = "In coda · elaborazione delicata",
                                )
                            } else {
                                it
                            }
                        }
                    } else if (existing.workId == job.id.toString() &&
                        existing.phase == UltraPhase.RUNNING &&
                        job.state == WorkInfo.State.ENQUEUED &&
                        existing.updatedAt < System.currentTimeMillis() - 5000
                    ) {
                        store.updateWorker(key, existing.workId) {
                            it.copy(phase = UltraPhase.WAITING, message = "In attesa di ripresa da Android")
                        }
                    } else if (existing.workId == job.id.toString() && existing.active && job.state.isFinished) {
                        store.updateWorker(key, existing.workId) {
                            it.copy(
                                phase = if (job.state ==
                                    WorkInfo.State.CANCELLED
                                ) {
                                    UltraPhase.CANCELLED
                                } else {
                                    UltraPhase.FAILED
                                },
                                message = "Elaborazione interrotta · puoi riprenderla",
                            )
                        }
                    }
                }
                val knownWork = jobs.mapTo(hashSetOf()) { it.id.toString() }
                store.tasks.value.values.filter {
                    it.active && it.workId !in knownWork && it.updatedAt < System.currentTimeMillis() - 30_000
                }.forEach { task ->
                    store.updateWorker(task.key, task.workId) {
                        it.copy(phase = UltraPhase.FAILED, message = "Avvio interrotto · tocca per riprendere Ultra")
                    }
                }
                store.tasks.value.values.sortedByDescending { it.updatedAt }
            },
        )
    }.flowOn(Dispatchers.IO)

    suspend fun describe(context: Context, folder: UniFile, title: String, animeId: Long, episodeId: Long): UltraTask? =
        withContext(Dispatchers.IO) {
            commands.withLock {
                if (!folder.exists()) return@withLock null
                val store = UltraTaskStore.get(context)
                val key = UltraTask.key(folder.uri.toString())
                val ready = UltraFiles.completed(context, folder) != null
                store.change(key) { previous ->
                    val task = previous ?: UltraTask(folder.uri.toString(), title, animeId, episodeId)
                    task.copy(
                        animeId = animeId,
                        episodeId = episodeId,
                        title = title,
                        phase = if (ready) {
                            UltraPhase.READY
                        } else if (task.phase ==
                            UltraPhase.READY
                        ) {
                            UltraPhase.AVAILABLE
                        } else {
                            task.phase
                        },
                        progress = if (ready) {
                            100
                        } else if (task.phase == UltraPhase.READY) {
                            0
                        } else {
                            task.progress
                        },
                        message = if (ready) {
                            "Ultra pronto"
                        } else if (task.phase ==
                            UltraPhase.READY
                        ) {
                            "Originale scaricato"
                        } else {
                            task.message
                        },
                    )
                }
                store.tasks.value.getValue(key)
            }
        }

    suspend fun enqueue(context: Context, task: UltraTask, replace: Boolean = false) = withContext(Dispatchers.IO) {
        commands.withLock {
            val store = UltraTaskStore.get(context)
            store.load()
            val old = store.tasks.value[task.key]
            if (!replace && (old?.active == true || old?.phase == UltraPhase.READY)) return@withLock
            val preferences = Injekt.get<DownloadPreferences>()
            val work = request(task)
            val reason = UltraProcessingControl(context, preferences, cooling = old?.coolingRequired == true).sample()
            store.change(task.key) {
                (it ?: task).copy(
                    phase = UltraPhase.QUEUED,
                    workId = work.id.toString(),
                    message = reason ?: "In coda · elaborazione delicata",
                    updatedAt = System.currentTimeMillis(),
                )
            }
            try {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    "${UltraDownloadWorker.TAG}:${task.key}",
                    ExistingWorkPolicy.REPLACE,
                    work,
                ).result.await()
            } catch (error: Exception) {
                store.updateWorker(task.key, work.id.toString()) {
                    it.copy(phase = UltraPhase.FAILED, message = "Impossibile mettere Ultra in coda. Riprova.")
                }
                throw error
            }
        }
    }

    private fun request(task: UltraTask, delaySeconds: Long = 0): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<UltraDownloadWorker>()
            .addTag(UltraDownloadWorker.TAG)
            .addTag("ultra-journal-v1")
            .addTag(UltraScheduling.POLICY_TAG)
            .addTag("ultra-title:${task.title}")
            .addTag("ultra-folder:${task.folder}")
            .setInputData(
                workDataOf(
                    UltraDownloadWorker.FOLDER to task.folder,
                    UltraDownloadWorker.TITLE to task.title,
                ),
            )
            // Charging/screen preferences are sampled live, including for existing waiting jobs.
            .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
            .build()

    suspend fun defer(context: Context, key: String, workerId: String) = withContext(Dispatchers.IO) {
        commands.withLock {
            val store = UltraTaskStore.get(context)
            val task = store.tasks.value[key]?.takeIf { it.active && it.workId == workerId } ?: return@withLock
            val next = request(task, UltraScheduling.RECHECK_SECONDS)
            withContext(NonCancellable) {
                UltraScheduling.handOff(store, key, workerId, next.id.toString()) {
                    WorkManager.getInstance(context).enqueueUniqueWork(
                        "${UltraDownloadWorker.TAG}:$key",
                        ExistingWorkPolicy.APPEND_OR_REPLACE,
                        next,
                    ).result.await()
                }
            }
        }
    }

    /** Upgrade pending work as well as defaults; keep saved segments and explicit user pauses. */
    suspend fun refreshPolicy(context: Context) = withContext(Dispatchers.IO) {
        val store = UltraTaskStore.get(context)
        store.load()
        val workManager = WorkManager.getInstance(context)
        for (job in workManager.getWorkInfosByTag(UltraDownloadWorker.TAG).await()) {
            val task = store.tasks.value.values.firstOrNull { it.workId == job.id.toString() } ?: continue
            if (!UltraScheduling.needsRefresh(
                    task,
                    job.id.toString(),
                    job.tags,
                    job.state == WorkInfo.State.RUNNING,
                    job.state.isFinished,
                )
            ) {
                continue
            }
            commands.withLock {
                val current = store.tasks.value[task.key]
                    ?.takeIf { it.active && it.workId == task.workId } ?: return@withLock
                val next = request(current)
                withContext(NonCancellable) {
                    UltraScheduling.handOff(store, current.key, current.workId, next.id.toString()) {
                        workManager.enqueueUniqueWork(
                            "${UltraDownloadWorker.TAG}:${current.key}",
                            ExistingWorkPolicy.REPLACE,
                            next,
                        ).result.await()
                    }
                }
            }
        }
    }

    suspend fun pause(context: Context, task: UltraTask) = commands.withLock {
        val store = UltraTaskStore.get(context)
        store.change(task.key) {
            if (it?.active ==
                true
            ) {
                it.copy(phase = UltraPhase.PAUSED, message = "In pausa · progresso conservato")
            } else {
                it
            }
        }
        WorkManager.getInstance(context).cancelUniqueWork("${UltraDownloadWorker.TAG}:${task.key}").result.await()
    }

    suspend fun cancel(context: Context, task: UltraTask, remove: Boolean = false) = withContext(Dispatchers.IO) {
        commands.withLock {
            val store = UltraTaskStore.get(context)
            store.change(task.key) {
                if (remove) {
                    null
                } else {
                    it?.copy(
                        phase = UltraPhase.CANCELLED,
                        progress = 0,
                        message = "Ultra annullato · originale disponibile",
                    )
                }
            }
            WorkManager.getInstance(context).cancelUniqueWork("${UltraDownloadWorker.TAG}:${task.key}").result.await()
            // Never wait for an unrelated episode to finish its conversion.
            while (UltraDownloadWorker.activeKey == task.key) delay(50)
            scratch(context, task.key).deleteRecursively()
            // Publication may have won a last-millisecond cancellation. Display the real file state.
            val folder = UniFile.fromUri(context, task.folder.toUri())
            if (!remove && folder != null && UltraFiles.completed(context, folder) != null) {
                store.change(task.key) {
                    it?.copy(phase = UltraPhase.READY, progress = 100, message = "Ultra pronto")
                }
            }
        }
    }

    suspend fun removeFolder(context: Context, folder: Uri) = withContext(Dispatchers.IO) {
        commands.withLock {
            val key = UltraTask.key(folder.toString())
            WorkManager.getInstance(context).cancelUniqueWork("${UltraDownloadWorker.TAG}:$key").result.await()
            while (UltraDownloadWorker.activeKey == key) delay(50)
            scratch(context, key).deleteRecursively()
            // An unreadable optional journal must not prevent deleting a normal download.
            try {
                UltraTaskStore.get(context).change(key) { null }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
            }
        }
    }

    /** Deletes only the selected episode, or just our named Ultra files, after its writer has stopped. */
    suspend fun delete(context: Context, task: UltraTask, onlyUltra: Boolean) = withContext(Dispatchers.IO) {
        commands.withLock {
            val store = UltraTaskStore.get(context)
            try {
                UltraRemoval.run(
                    stopWriter = {
                        // Invalidate the old attempt before cancellation, including late file publication.
                        store.change(task.key) {
                            it?.copy(phase = UltraPhase.PAUSED, workId = "", message = "Eliminazione in corso…")
                        }
                        check(
                            withTimeoutOrNull(30_000) {
                                WorkManager.getInstance(context)
                                    .cancelUniqueWork("${UltraDownloadWorker.TAG}:${task.key}").result.await()
                                while (UltraDownloadWorker.activeKey == task.key) delay(50)
                                true
                            } == true,
                        ) { "Ultra si sta ancora arrestando. Attendi qualche secondo e riprova." }
                    },
                    removeFiles = {
                        val folder = requireNotNull(UniFile.fromUri(context, task.folder.toUri())) {
                            "Cartella non accessibile. Controlla le autorizzazioni dei download."
                        }
                        if (onlyUltra) {
                            // Never delete original video, subtitles or unrelated files in this mode.
                            listOf(UltraFiles.VIDEO, UltraFiles.PART, UltraFiles.MARKER).forEach { name ->
                                folder.findFile(name)?.let { file ->
                                    check(file.delete() && !file.exists()) {
                                        "Non riesco a eliminare $name. Controlla la cartella dei download."
                                    }
                                }
                            }
                        } else if (folder.exists()) {
                            check(folder.delete() && !folder.exists()) {
                                "Download non eliminato. Controlla le autorizzazioni della cartella."
                            }
                        }
                    },
                    removeTemporary = {
                        val temporary = scratch(context, task.key)
                        check(!temporary.exists() || temporary.deleteRecursively()) {
                            "Alcuni file temporanei non sono stati eliminati. Riprova per completare la pulizia."
                        }
                    },
                    recordCompletion = {
                        store.change(task.key) {
                            if (onlyUltra) {
                                (it ?: task).copy(
                                    phase = UltraPhase.AVAILABLE,
                                    progress = 0,
                                    workId = "",
                                    message = "Copia Ultra eliminata · originale disponibile",
                                    coolingRequired = false,
                                    coolingUntil = 0,
                                )
                            } else {
                                null
                            }
                        }
                    },
                )
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                store.change(task.key) {
                    it?.copy(phase = UltraPhase.FAILED, message = "Eliminazione incompleta · riprova")
                }
                throw error
            }
        }
    }

    suspend fun export(
        context: Context,
        task: UltraTask,
        destination: Uri,
        ultra: Boolean,
        onProgress: (Int) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val folder = requireNotNull(UniFile.fromUri(context, task.folder.toUri()))
        val video = requireNotNull(
            if (ultra) UltraFiles.completed(context, folder) else UltraFiles.original(folder),
        ) { "Il file scelto non è più disponibile" }
        require(destination != video.uri) { "Scegli una posizione diversa dall'originale" }
        val expectedBytes = video.length()
        var copied = 0L
        var lastProgress = -1
        try {
            context.contentResolver.openInputStream(video.uri)!!.use { input ->
                context.contentResolver.openOutputStream(destination, "wt")!!.use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                        val progress = (copied * 100 / expectedBytes.coerceAtLeast(1)).toInt().coerceIn(0, 100)
                        if (progress != lastProgress) {
                            onProgress(progress)
                            lastProgress = progress
                        }
                    }
                }
            }
            check(copied == expectedBytes) { "Copia incompleta. Il file nell'app è conservato." }
        } catch (error: Exception) {
            UniFile.fromUri(context, destination)?.delete()
            throw error
        }
    }
}
