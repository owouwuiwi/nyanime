package eu.kanade.domain.track.manga.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import it.lorelore.nyanime.tracking.TrackingEditWorker
import it.lorelore.nyanime.tracking.TrackingLegacyMigration
import tachiyomi.core.common.util.lang.withIOContext
import kotlin.time.Duration

/** Kept for WorkManager requests persisted by older versions. Never guesses an old account or medium. */
class DelayedMangaTrackingUpdateJob(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {
    override suspend fun doWork(): Result = withIOContext {
        TrackingLegacyMigration.migrate(context)
        TrackingEditWorker.resumePending(context)
        Result.success()
    }

    companion object {
        fun setupTask(context: Context, initialDelay: Duration = Duration.ZERO) {
            TrackingEditWorker.resumePending(context)
        }
    }
}
