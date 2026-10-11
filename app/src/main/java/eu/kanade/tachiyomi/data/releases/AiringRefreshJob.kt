package eu.kanade.tachiyomi.data.releases

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.entries.anime.repository.AnimeRepository
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import tachiyomi.domain.track.anime.repository.AnimeTrackRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Calendar catch-up is durable and independent of slow episode-list updates. */
class AiringRefreshJob(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (ReleaseRestoreGuard.active) return Result.retry()
        if (!canRun()) return Result.success()
        if (!running.tryLock()) return Result.retry()
        try {
            return refreshBatch()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logcat(LogPriority.WARN, error) { "Unable to complete the airing verification batch" }
            return Result.retry()
        } finally {
            running.unlock()
        }
    }

    private suspend fun refreshBatch(): Result {
        val initialized = withTimeoutOrNull(15_000) {
            Injekt.get<AnimeSourceManager>().isInitialized.first { it }
        }
        if (initialized != true) return Result.retry()
        val repository = AiringRepository()
        val enhanced = AnimeScheduleRepository()
        enhanced.refreshTimetables()
        val now = System.currentTimeMillis()
        val candidates = loadCandidates(repository, now)
        val batch = AiringRefreshQueue.batch(candidates, now)
        for (candidate in batch) {
            if (!canRun()) return Result.success()
            if (ReleaseEligibility.source(ReleaseMedium.ANIME, candidate.entryId) == null) continue
            val entry = Injekt.get<AnimeRepository>().getAnimeById(candidate.entryId)
            val startedAt = System.currentTimeMillis()
            val result = withTimeoutOrNull(45_000) { repository.refresh(entry) }
            if (result == null) {
                repository.markUnavailable(candidate.entryId, startedAt)
                logcat(LogPriority.WARN) { "Airing check timed out for entry ${candidate.entryId}" }
            }
            if (candidate.scheduleDue) enhanced.refresh(entry)
        }
        ReleaseStore().alignSchedule(repository.effectiveEvents().first())
        ReleaseReminders.schedule(applicationContext)
        eu.kanade.tachiyomi.data.releases.ReleaseAgendaWidget.refresh(applicationContext)
        if (AiringRefreshQueue.hasColdBacklog(candidates, batch.map { it.entryId }.toSet())) {
            enqueue(applicationContext, continuation = true)
        }
        return Result.success()
    }

    private suspend fun loadCandidates(repository: AiringRepository, now: Long): List<AiringRefreshQueue.Candidate> {
        val upcoming = repository.events().first().filter { it.airingAt > now }.map { it.entryId }.toSet()
        val candidates = mutableListOf<AiringRefreshQueue.Candidate>()
        for (id in ReleaseStore().monitoredIds(ReleaseMedium.ANIME)) {
            if (ReleaseEligibility.source(ReleaseMedium.ANIME, id) == null) continue
            val entry = Injekt.get<AnimeRepository>().getAnimeById(id)
            val tracks = Injekt.get<AnimeTrackRepository>().getTracksByAnimeId(id)
            val hasId = AiringCatalogReference.from(entry, tracks).hasId ||
                tracks.any { it.trackerId == TrackerManager.SIMKL && it.remoteId > 0 }
            val enhanced = AnimeScheduleRepository().needsMapping(entry, now)
            if (hasId ||
                enhanced.first
            ) {
                candidates +=
                    AiringRefreshQueue.Candidate(
                        id,
                        repository.cache(id),
                        id in upcoming,
                        enhanced.first,
                        enhanced.second,
                    )
            }
        }
        return candidates
    }

    companion object {
        private const val NAME = "release-airing-verification"
        private val running = Mutex()

        private fun canRun(): Boolean {
            val base: BasePreferences = Injekt.get()
            return ReleasePreferences().enabled.get() && !base.incognitoMode().get() && !base.downloadedOnly().get()
        }

        fun enqueue(context: Context, continuation: Boolean = false) {
            if (!canRun()) return
            val request = OneTimeWorkRequestBuilder<AiringRefreshJob>().setConstraints(
                ReleaseMonitor.constraints(),
            ).build()
            context.workManager.enqueueUniqueWork(
                NAME,
                if (continuation) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
                request,
            )
        }

        fun cancel(context: Context) = context.workManager.cancelUniqueWork(NAME)
    }
}
