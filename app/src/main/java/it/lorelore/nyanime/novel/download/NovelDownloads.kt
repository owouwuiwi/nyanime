package it.lorelore.nyanime.novel.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import it.lorelore.nyanime.novel.NovelBook
import it.lorelore.nyanime.novel.NovelDownloadPlan
import it.lorelore.nyanime.novel.NovelDownloadStore
import it.lorelore.nyanime.novel.NovelOnlineDocument
import it.lorelore.nyanime.novel.NovelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Explicit user intents finish their local journal/enqueue transaction after a screen closes. */
internal class NovelDownloads(
    context: Context,
    val store: NovelDownloadStore,
    private val library: NovelStore,
) {
    private val app = context.applicationContext
    private val work by lazy { WorkManager.getInstance(app) }
    private val actions = Mutex()
    private val availabilityWrites = Mutex()

    suspend fun enqueue(book: NovelBook, document: NovelOnlineDocument, selected: List<String>) =
        withContext(NonCancellable + Dispatchers.IO) {
            actions.withLock {
                val plan =
                    store.enqueue(
                        NovelDownloadPlan(book = book, document = document, selected = selected),
                    )
                enqueue(plan.book.id, plan.id)
            }
        }

    suspend fun pause(bookId: String) =
        withContext(NonCancellable + Dispatchers.IO) {
            actions.withLock {
                store.pause(bookId)
                work.cancelUniqueWork(name(bookId)).result.await()
                availability(bookId)
            }
        }

    suspend fun pauseIfCurrent(bookId: String, jobId: String) =
        withContext(NonCancellable + Dispatchers.IO) {
            actions.withLock {
                if (store.progress(bookId)?.id == jobId) {
                    store.pause(bookId)
                    work.cancelUniqueWork(name(bookId)).result.await()
                    availability(bookId)
                }
            }
        }

    suspend fun retry(bookId: String) =
        withContext(NonCancellable + Dispatchers.IO) {
            actions.withLock { store.retry(bookId)?.let { enqueue(bookId, it) } }
        }

    suspend fun remove(bookId: String) =
        withContext(NonCancellable + Dispatchers.IO) {
            actions.withLock {
                store.pause(bookId)
                work.cancelUniqueWork(name(bookId)).result.await()
                store.remove(bookId)
                availability(bookId)
            }
        }

    suspend fun availability(bookId: String) = availabilityWrites.withLock {
        val progress = store.progress(bookId)
        // This is local ownership only: no paths or downloaded flags are sent through Cloud.
        library.setOnlineOfflineAvailable(
            bookId,
            progress != null && progress.completedChapters > 0,
        )
    }

    private suspend fun enqueue(bookId: String, jobId: String) {
        val request =
            OneTimeWorkRequestBuilder<NovelDownloadWorker>()
                .setInputData(workDataOf(NovelDownloadWorker.JOB_ID to jobId))
                .addTag("novel-download")
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .build()
        work.enqueueUniqueWork(name(bookId), ExistingWorkPolicy.REPLACE, request).result.await()
    }

    private fun name(bookId: String) = "novel-download-$bookId"
}
