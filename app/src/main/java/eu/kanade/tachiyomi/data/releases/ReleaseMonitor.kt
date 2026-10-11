package eu.kanade.tachiyomi.data.releases

import android.content.Context
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.entries.manga.interactor.UpdateManga
import eu.kanade.domain.entries.manga.model.toSManga
import eu.kanade.domain.items.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.source.anime.interactor.GetAnimeIncognitoState
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.data.discovery.ResumeVisibility
import eu.kanade.tachiyomi.data.library.anime.AnimeLibraryUpdateJob
import eu.kanade.tachiyomi.data.library.manga.MangaLibraryUpdateJob
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.MangaSourceUpdateGate
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import mihon.domain.source.interactor.UpdateAnimeFromRemote
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.entries.anime.repository.AnimeRepository
import tachiyomi.domain.entries.manga.repository.MangaRepository
import tachiyomi.domain.items.chapter.repository.ChapterRepository
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import tachiyomi.domain.source.manga.service.MangaSourceManager
import tachiyomi.source.local.entries.anime.isLocal
import tachiyomi.source.local.entries.manga.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

class ReleaseMonitor(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    private val store = ReleaseStore()
    private val preferences = ReleasePreferences()
    private val base: BasePreferences = Injekt.get()
    private data class Candidate(
        val medium: ReleaseMedium,
        val id: Long,
        val source: Long,
        val state: ReleaseCheckState,
    )

    override suspend fun doWork(): Result {
        if (ReleaseRestoreGuard.active) return Result.retry()
        if (!preferences.enabled.get() ||
            base.incognitoMode().get() ||
            base.downloadedOnly().get()
        ) {
            return Result.success()
        }
        if (!running.tryLock()) return Result.retry()
        try {
            val now = System.currentTimeMillis()
            val candidates = mutableListOf<Candidate>()
            AiringRefreshJob.enqueue(applicationContext)
            for (medium in ReleaseMedium.entries) {
                for (id in store.monitoredIds(medium)) {
                    val source = eligibleSource(medium, id) ?: continue
                    if (ReleaseSourceCooldown.active(medium, source, now)) continue
                    val state = store.check(medium, id)
                    if (ReleasePolicy.isDue(state, now)) candidates += Candidate(medium, id, source, state)
                }
            }
            // Aging takes precedence over popularity; every eligible title eventually gets a turn.
            val batch = candidates.sortedBy { it.state.lastSuccess }.take(24)
            coroutineScope {
                batch.groupBy { it.medium to it.source }.values.map { entries ->
                    async {
                        permits.withPermit {
                            for (entry in entries) {
                                if (!preferences.enabled.get() || base.incognitoMode().get()) break
                                if (eligibleSource(entry.medium, entry.id) == null) continue
                                val deferred = ReleaseSourceCooldown.active(
                                    entry.medium,
                                    entry.source,
                                    System.currentTimeMillis(),
                                )
                                if (deferred) break
                                check(entry)
                                delay(3_000)
                            }
                        }
                    }
                }.awaitAll()
            }
            ReleaseNotifications.flush(applicationContext)
            ReleaseReminders.schedule(applicationContext)
            if (candidates.size > batch.size) enqueue(applicationContext, followUp = true)
            return Result.success()
        } finally {
            running.unlock()
        }
    }

    private suspend fun check(candidate: Candidate) {
        val now = System.currentTimeMillis()
        val details = Injekt.get<LibraryPreferences>().autoUpdateMetadata().get() ||
            now - candidate.state.metadataAt >= ReleasePolicy.DAY
        store.markAttempt(candidate.medium, candidate.id, now)
        try {
            withTimeout(45_000) {
                when (candidate.medium) {
                    ReleaseMedium.ANIME -> {
                        val anime = Injekt.get<AnimeRepository>().getAnimeById(candidate.id)
                        val update = Injekt.get<UpdateAnimeFromRemote>()
                        if (anime.fetchType == FetchType.Seasons) {
                            update.awaitSeasonsUpdate(anime, fetchDetails = details, fetchSeasons = true).getOrThrow()
                            store.committed(ReleaseMedium.ANIME, anime.id, emptyList(), false, completed = false)
                        } else {
                            update.awaitEpisodesUpdate(
                                anime,
                                fetchDetails = details,
                                fetchEpisodes = true,
                            ).getOrThrow()
                        }
                        val fresh = Injekt.get<AnimeRepository>().getAnimeById(anime.id)
                        AiringRepository().refresh(fresh)
                        store.committed(
                            ReleaseMedium.ANIME,
                            anime.id,
                            emptyList(),
                            false,
                            fresh.status == 2L,
                            AiringRepository().effectiveEntryEvents(anime.id).firstOrNull {
                                it.entryId == anime.id &&
                                    it.airingAt > System.currentTimeMillis()
                            }?.airingAt,
                        )
                    }
                    ReleaseMedium.MANGA -> {
                        ReleaseUpdateGate.withEntry(ReleaseMedium.MANGA, candidate.id) {
                            val manga = Injekt.get<MangaRepository>().getMangaById(candidate.id)
                            val source = Injekt.get<MangaSourceManager>().get(manga.source)
                                ?: error("Source unavailable")
                            val result = MangaSourceUpdateGate.await(
                                source,
                                manga.toSManga(),
                                emptyList(),
                                details || !manga.initialized,
                                true,
                            )
                            Injekt.get<UpdateManga>().awaitUpdateFromSource(manga, result.manga, false)
                            Injekt.get<SyncChaptersWithSource>().await(result.chapters, manga, source)
                            ChapterScheduleRepository().refresh(manga)
                        }
                    }
                }
            }
            if (details) store.metadataChecked(candidate.medium, candidate.id, System.currentTimeMillis())
        } catch (_: TimeoutCancellationException) {
            store.markFailure(candidate.medium, candidate.id, System.currentTimeMillis())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val throttled = generateSequence<Throwable>(e) { it.cause }.any {
                it is HttpException && it.code in setOf(429, 503)
            }
            if (throttled) {
                ReleaseSourceCooldown.defer(candidate.medium, candidate.source, System.currentTimeMillis())
            }
            store.markFailure(
                candidate.medium,
                candidate.id,
                System.currentTimeMillis(),
                if (throttled) 15 * ReleasePolicy.MINUTE else 0,
            )
        }
    }

    private suspend fun eligibleSource(medium: ReleaseMedium, id: Long) = ReleaseEligibility.source(medium, id)

    companion object {
        private const val PERIODIC = "release-monitor-periodic"
        private const val IMMEDIATE = "release-monitor-now"
        private const val FOLLOW_UP = "release-monitor-followup"
        private val running = Mutex()
        private val permits = Semaphore(3)

        fun initialize(context: Context) {
            val store = Injekt.get<PreferenceStore>()
            val migrated = store.getBoolean(Preference.appStateKey("release_monitor_migrated_v1"), false)
            if (!migrated.get()) {
                val library = Injekt.get<LibraryPreferences>()
                // Preserve deliberate connectivity restrictions, replace the default Wi-Fi-only rule.
                if (!library.autoUpdateDeviceRestrictions().isSet()) {
                    library.autoUpdateDeviceRestrictions().set(
                        emptySet(),
                    )
                }
                ReleasePreferences().enabled.set(true)
                migrated.set(true)
            }
            setup(context)
            enqueue(context)
        }

        fun setup(context: Context) {
            // The legacy workers remain available for explicit library refreshes only.
            context.workManager.cancelAllWorkByTag("AnimeLibraryUpdate-auto")
            context.workManager.cancelAllWorkByTag("LibraryUpdate-auto")
            context.workManager.cancelUniqueWork("AnimeLibraryUpdate-auto")
            context.workManager.cancelUniqueWork("LibraryUpdate-auto")
            context.workManager.cancelAllWorkByTag("AnimeLibraryUpdate-catchup")
            if (!ReleasePreferences().enabled.get()) {
                listOf(PERIODIC, IMMEDIATE, FOLLOW_UP).forEach(context.workManager::cancelUniqueWork)
                AiringRefreshJob.cancel(context)
                ReleaseReminders.cancel(context)
                return
            }
            val request = PeriodicWorkRequestBuilder<ReleaseMonitor>(15, TimeUnit.MINUTES)
                .setConstraints(constraints()).build()
            context.workManager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun enqueue(context: Context, followUp: Boolean = false) {
            if (!ReleasePreferences().enabled.get()) return
            if (!followUp) AiringRefreshJob.enqueue(context)
            val request = OneTimeWorkRequestBuilder<ReleaseMonitor>().setConstraints(constraints()).build()
            context.workManager.enqueueUniqueWork(
                if (followUp) FOLLOW_UP else IMMEDIATE,
                if (followUp) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
                request,
            )
        }

        internal fun constraints(): Constraints {
            val rules = Injekt.get<LibraryPreferences>().autoUpdateDeviceRestrictions().get()
            val network = if (LibraryPreferences.DEVICE_ONLY_ON_WIFI in rules ||
                LibraryPreferences.DEVICE_NETWORK_NOT_METERED in rules
            ) {
                NetworkType.UNMETERED
            } else {
                NetworkType.CONNECTED
            }
            val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            if (LibraryPreferences.DEVICE_ONLY_ON_WIFI in
                rules
            ) {
                request.addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            }
            if (LibraryPreferences.DEVICE_NETWORK_NOT_METERED in
                rules
            ) {
                request.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            }
            return Constraints.Builder().setRequiredNetworkRequest(request.build(), network)
                .setRequiresCharging(LibraryPreferences.DEVICE_CHARGING in rules).build()
        }
    }
}
