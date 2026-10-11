package eu.kanade.tachiyomi.data.library.anime

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkQuery
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import eu.kanade.tachiyomi.animesource.model.AnimeUpdateStrategy
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.data.download.anime.AnimeDownloadManager
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.createFileInCacheDir
import eu.kanade.tachiyomi.util.system.isConnectedToWifi
import eu.kanade.tachiyomi.util.system.isRunning
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import logcat.LogPriority
import mihon.domain.items.episode.interactor.FilterEpisodesForDownload
import mihon.domain.source.interactor.UpdateAnimeFromRemote
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getAndSet
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.entries.anime.interactor.AnimeFetchInterval
import tachiyomi.domain.entries.anime.interactor.GetAnime
import tachiyomi.domain.entries.anime.interactor.GetLibraryAnime
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.history.anime.interactor.GetAnimeHistory
import tachiyomi.domain.items.episode.model.Episode
import tachiyomi.domain.items.episode.model.NoEpisodesException
import tachiyomi.domain.items.season.interactor.GetAnimeSeasonsByParentId
import tachiyomi.domain.library.anime.LibraryAnime
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.library.service.LibraryPreferences.Companion.DEVICE_CHARGING
import tachiyomi.domain.library.service.LibraryPreferences.Companion.DEVICE_NETWORK_NOT_METERED
import tachiyomi.domain.library.service.LibraryPreferences.Companion.DEVICE_ONLY_ON_WIFI
import tachiyomi.domain.library.service.LibraryPreferences.Companion.ENTRY_HAS_UNVIEWED
import tachiyomi.domain.library.service.LibraryPreferences.Companion.ENTRY_NON_COMPLETED
import tachiyomi.domain.library.service.LibraryPreferences.Companion.ENTRY_NON_VIEWED
import tachiyomi.domain.library.service.LibraryPreferences.Companion.ENTRY_OUTSIDE_RELEASE_PERIOD
import tachiyomi.domain.source.anime.model.AnimeSourceNotInstalledException
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import tachiyomi.i18n.MR
import tachiyomi.i18n.aniyomi.AYMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.time.Instant
import java.time.ZonedDateTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class AnimeLibraryUpdateJob(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val sourceManager: AnimeSourceManager = Injekt.get()
    private val libraryPreferences: LibraryPreferences = Injekt.get()
    private val downloadManager: AnimeDownloadManager = Injekt.get()
    private val getLibraryAnime: GetLibraryAnime = Injekt.get()
    private val getAnime: GetAnime = Injekt.get()
    private val getAnimeHistory: GetAnimeHistory = Injekt.get()
    private val animeFetchInterval: AnimeFetchInterval = Injekt.get()
    private val filterEpisodesForDownload: FilterEpisodesForDownload = Injekt.get()
    private val getAnimeSeasonsByParentId: GetAnimeSeasonsByParentId = Injekt.get()
    private val updateAnimeFromRemote: UpdateAnimeFromRemote = Injekt.get()
    private val refreshSchedule: AnimeRefreshSchedule = Injekt.get()

    private val notifier = AnimeLibraryUpdateNotifier(context)

    private var animeToUpdate: List<LibraryAnime> = mutableListOf()
    private var watchedAnimeIds: Set<Long> = emptySet()
    private var hasAutomaticBacklog = false

    override suspend fun doWork(): Result {
        if (tags.contains(WORK_NAME_AUTO)) {
            eu.kanade.tachiyomi.data.releases.ReleaseMonitor.enqueue(context)
            return Result.success()
        }

        val automatic = tags.contains(WORK_NAME_AUTO)
        if (automatic) {
            if (libraryPreferences.autoUpdateInterval().get() <= 0) return Result.success()
            if (!autoJobActive.compareAndSet(false, true)) return Result.retry()
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                val restrictions = libraryPreferences.autoUpdateDeviceRestrictions().get()
                if ((DEVICE_ONLY_ON_WIFI in restrictions) && !context.isConnectedToWifi()) {
                    autoJobActive.set(false)
                    return Result.retry()
                }
            }
        }

        return try {
            try {
                setForeground(getForegroundInfo())
            } catch (e: IllegalStateException) {
                logcat(LogPriority.ERROR, e) { "Not allowed to set foreground job" }
            }
            runUpdate(automatic)
        } finally {
            if (automatic) autoJobActive.set(false)
        }
    }

    private suspend fun runUpdate(automatic: Boolean): Result {
        if (automatic) refreshSchedule.recordAutomaticBatch(System.currentTimeMillis())

        val categoryId = inputData.getLong(KEY_CATEGORY, -1L)
        addAnimeToQueue(categoryId)

        return withIOContext {
            try {
                updateEpisodeList()
                if (automatic && hasAutomaticBacklog) scheduleAutomaticFollowUp(context)
                libraryPreferences.lastUpdatedTimestamp().set(Instant.now().toEpochMilli())
                Result.success()
            } catch (e: Exception) {
                if (e is CancellationException) {
                    throw e
                } else {
                    logcat(LogPriority.ERROR, e)
                    Result.failure()
                }
            } finally {
                notifier.cancelProgressNotification()
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notifier = AnimeLibraryUpdateNotifier(context)
        return ForegroundInfo(
            Notifications.ID_LIBRARY_PROGRESS,
            notifier.progressNotificationBuilder.build(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },

        )
    }

    /**
     * Adds list of anime to be updated.
     *
     * @param categoryId the ID of the category to update, or -1 if no category specified.
     */
    private suspend fun addAnimeToQueue(categoryId: Long) {
        val libraryAnime = getLibraryAnime.await()
        val watchedAnime = if (categoryId == -1L) {
            val recent = Instant.now().minusSeconds(90L * 86_400).toEpochMilli()
            getAnimeHistory.subscribe("").first()
                .filter { (it.seenAt?.time ?: 0L) >= recent }
                .distinctBy { it.animeId }
                .mapNotNull { getAnime.await(it.animeId) }
                .filterNot { it.favorite }
                .map { anime -> LibraryAnime(anime, 0, 1, 1, 0, 0, 0, 0, 0) }
        } else {
            emptyList()
        }
        watchedAnimeIds = watchedAnime.map { it.anime.id }.toSet()

        val listToUpdate = if (categoryId != -1L) {
            libraryAnime.filter { it.category == categoryId }
        } else {
            val categoriesToUpdate = libraryPreferences.animeUpdateCategories().get().map { it.toLong() }
            val includedAnime = if (categoriesToUpdate.isNotEmpty()) {
                libraryAnime.filter { it.category in categoriesToUpdate }
            } else {
                libraryAnime
            }

            val categoriesToExclude = libraryPreferences.animeUpdateCategoriesExclude().get().map { it.toLong() }
            val excludedAnimeIds = if (categoriesToExclude.isNotEmpty()) {
                libraryAnime.filter { it.category in categoriesToExclude }.map { it.anime.id }
            } else {
                emptyList()
            }

            includedAnime
                .filterNot { it.anime.id in excludedAnimeIds }
                .distinctBy { it.anime.id }
        }

        val includeSeasons = libraryPreferences.updateSeasonOnLibraryUpdate().get()
        val lastToUpdateWithSeasons = (listToUpdate + watchedAnime).distinctBy { it.anime.id }.flatMap { libAnime ->
            when (libAnime.anime.fetchType) {
                FetchType.Seasons -> {
                    if (includeSeasons) {
                        val seasons = getAnimeSeasonsByParentId.await(libAnime.anime.id)
                        seasons
                            .filter { s ->
                                s.anime.fetchType == FetchType.Episodes && !s.anime.favorite
                            }
                            .map { it.toLibraryAnime() }
                    } else {
                        emptyList()
                    }
                }
                FetchType.Episodes -> listOf(libAnime)
            }
        }

        val restrictions = if (tags.contains(
                WORK_NAME_AUTO,
            )
        ) {
            libraryPreferences.autoUpdateItemRestrictions().get()
        } else {
            emptySet()
        }
        val skippedUpdates = mutableListOf<Pair<Anime, String?>>()
        val (_, fetchWindowUpperBound) = animeFetchInterval.getWindow(ZonedDateTime.now())

        val eligible = lastToUpdateWithSeasons
            .filter {
                when {
                    it.anime.updateStrategy != AnimeUpdateStrategy.ALWAYS_UPDATE -> {
                        skippedUpdates.add(
                            it.anime to context.stringResource(MR.strings.skipped_reason_not_always_update),
                        )
                        false
                    }

                    ENTRY_NON_COMPLETED in restrictions && it.anime.status.toInt() == SAnime.COMPLETED -> {
                        skippedUpdates.add(
                            it.anime to context.stringResource(MR.strings.skipped_reason_completed),
                        )
                        false
                    }

                    ENTRY_HAS_UNVIEWED in restrictions && it.unseenCount != 0L -> {
                        skippedUpdates.add(
                            it.anime to context.stringResource(MR.strings.skipped_reason_not_caught_up),
                        )
                        false
                    }

                    ENTRY_NON_VIEWED in restrictions && it.totalCount > 0L && !it.hasStarted -> {
                        skippedUpdates.add(
                            it.anime to context.stringResource(MR.strings.skipped_reason_not_started),
                        )
                        false
                    }

                    ENTRY_OUTSIDE_RELEASE_PERIOD in restrictions && it.anime.nextUpdate > fetchWindowUpperBound -> {
                        skippedUpdates.add(
                            it.anime to context.stringResource(MR.strings.skipped_reason_not_in_release_period),
                        )
                        false
                    }
                    else -> true
                }
            }

        animeToUpdate = if (tags.contains(WORK_NAME_AUTO)) {
            val now = System.currentTimeMillis()
            val due = eligible.asSequence()
                .filter { refreshSchedule.isDue(it.anime, now) }
                .sortedWith(
                    compareBy<LibraryAnime> { refreshSchedule.lastAttempt(it.anime.id) }
                        .thenBy { refreshSchedule.priority(it.anime) },
                )
                .toList()
            val selected = due.asSequence()
                .filter { refreshSchedule.reserve(it.anime.source, now) }
                .take(MAX_AUTO_UPDATE_PER_RUN)
                .toList()
            hasAutomaticBacklog = due.size > selected.size
            selected
        } else {
            eligible.sortedBy { it.anime.title }
        }

        notifier.showQueueSizeWarningNotificationIfNeeded(animeToUpdate)

        if (skippedUpdates.isNotEmpty()) {
            // TODO: surface skipped reasons to user?
            logcat {
                skippedUpdates
                    .groupBy { it.second }
                    .map { (reason, entries) -> "$reason: [${entries.map { it.first.title }.sorted().joinToString()}]" }
                    .joinToString()
            }
        }
    }

    /**
     * Method that updates anime in [animeToUpdate]. It's called in a background thread, so it's safe
     * to do heavy operations or network calls here.
     * For each anime it calls [updateAnime] and updates the notification showing the current
     * progress.
     *
     * @return an observable delivering the progress of each update.
     */
    private suspend fun updateEpisodeList() {
        val semaphore = Semaphore(5)
        val progressCount = AtomicInteger(0)
        val currentlyUpdatingAnime = CopyOnWriteArrayList<Anime>()
        val newUpdates = CopyOnWriteArrayList<Pair<Anime, Array<Episode>>>()
        val failedUpdates = CopyOnWriteArrayList<Pair<Anime, String?>>()
        val hasDownloads = AtomicBoolean(false)
        val fetchWindow = animeFetchInterval.getWindow(ZonedDateTime.now())

        coroutineScope {
            animeToUpdate.groupBy { it.anime.source }.values
                .map { animeInSource ->
                    async {
                        semaphore.withPermit {
                            animeInSource.forEach { libraryAnime ->
                                val anime = libraryAnime.anime
                                ensureActive()

                                // A recently watched title remains followed even without a library entry.
                                if (anime.parentId == null &&
                                    getAnime.await(anime.id)?.favorite != true &&
                                    anime.id !in watchedAnimeIds
                                ) {
                                    return@forEach
                                }

                                withUpdateNotification(
                                    currentlyUpdatingAnime,
                                    progressCount,
                                    anime,
                                ) {
                                    try {
                                        val newEpisodes = updateAnime(anime, fetchWindow)
                                            .sortedByDescending { it.sourceOrder }

                                        if (newEpisodes.isNotEmpty()) {
                                            val episodesToDownload = if (anime.favorite) {
                                                filterEpisodesForDownload.await(anime, newEpisodes)
                                            } else {
                                                emptyList()
                                            }

                                            if (episodesToDownload.isNotEmpty()) {
                                                hasDownloads.set(true)
                                            }

                                            libraryPreferences.newAnimeUpdatesCount()
                                                .getAndSet { it + newEpisodes.size }

                                            // Convert to the anime that contains new episodes
                                            newUpdates.add(anime to newEpisodes.toTypedArray())
                                        }
                                        if (tags.contains(WORK_NAME_AUTO)) {
                                            refreshSchedule.record(anime.id, System.currentTimeMillis(), true)
                                        }
                                    } catch (e: Throwable) {
                                        if (e is CancellationException) throw e
                                        if (tags.contains(WORK_NAME_AUTO)) {
                                            refreshSchedule.record(anime.id, System.currentTimeMillis(), false)
                                        }
                                        val errorMessage = when (e) {
                                            is NoEpisodesException -> context.stringResource(
                                                AYMR.strings.no_episodes_error,
                                            )
                                            // failedUpdates will already have the source, don't need to copy it into the message
                                            is AnimeSourceNotInstalledException -> context.stringResource(
                                                MR.strings.loader_not_implemented_error,
                                            )
                                            else -> e.message
                                        }
                                        failedUpdates.add(anime to errorMessage)
                                    }
                                }
                                if (tags.contains(WORK_NAME_AUTO)) delay(AUTO_REQUEST_SPACING_MS)
                            }
                        }
                    }
                }
                .awaitAll()
        }

        notifier.cancelProgressNotification()

        if (newUpdates.isNotEmpty()) {
            eu.kanade.tachiyomi.data.releases.ReleaseNotifications.flush(context)
            if (hasDownloads.get()) {
                downloadManager.startDownloads(userInitiated = false)
            }
        }

        if (failedUpdates.isNotEmpty()) {
            val errorFile = writeErrorFile(failedUpdates)
            notifier.showUpdateErrorNotification(
                failedUpdates.size,
                errorFile.getUriCompat(context),
            )
        }
    }

    private fun downloadEpisodes(anime: Anime, episodes: List<Episode>) {
        // We don't want to start downloading while the library is updating, because websites
        // may don't like it and they could ban the user.
        downloadManager.downloadEpisodes(anime, episodes, false)
    }

    /**
     * Updates the episodes for the given anime and adds them to the database.
     *
     * @param anime the anime to update.
     * @return a pair of the inserted and removed episodes.
     */
    private suspend fun updateAnime(anime: Anime, fetchWindow: Pair<Long, Long>): List<Episode> {
        val source = sourceManager.getOrStub(anime.source)

        val update = updateAnimeFromRemote.awaitEpisodesUpdate(
            source = source,
            anime = anime,
            fetchDetails = libraryPreferences.autoUpdateMetadata().get(),
            fetchEpisodes = true,
            fetchWindow = fetchWindow,
        )
            .getOrThrow()

        return if (update.anime.favorite || anime.id in watchedAnimeIds) update.newEpisodes else emptyList()
    }

    private suspend fun withUpdateNotification(
        updatingAnime: CopyOnWriteArrayList<Anime>,
        completed: AtomicInteger,
        anime: Anime,
        block: suspend () -> Unit,
    ) = coroutineScope {
        ensureActive()

        updatingAnime.add(anime)
        notifier.showProgressNotification(
            updatingAnime,
            completed.get(),
            animeToUpdate.size,
        )

        block()

        ensureActive()

        updatingAnime.remove(anime)
        completed.getAndIncrement()
        notifier.showProgressNotification(
            updatingAnime,
            completed.get(),
            animeToUpdate.size,
        )
    }

    /**
     * Writes basic file of update errors to cache dir.
     */
    private fun writeErrorFile(errors: List<Pair<Anime, String?>>): File {
        try {
            if (errors.isNotEmpty()) {
                val file = context.createFileInCacheDir("nyanime_update_errors.txt")
                file.bufferedWriter().use { out ->
                    out.write(
                        context.stringResource(MR.strings.library_errors_help, ERROR_LOG_HELP_URL) + "\n\n",
                    )
                    // Error file format:
                    // ! Error
                    //   # Source
                    //     - Anime
                    errors.groupBy({ it.second }, { it.first }).forEach { (error, animes) ->
                        out.write("\n! ${error}\n")
                        animes.groupBy { it.source }.forEach { (srcId, animes) ->
                            val source = sourceManager.getOrStub(srcId)
                            out.write("  # $source\n")
                            animes.forEach {
                                out.write("    - ${it.title}\n")
                            }
                        }
                    }
                }
                return file
            }
        } catch (_: Exception) {}
        return File("")
    }

    companion object {
        private const val TAG = "AnimeLibraryUpdate"
        private const val WORK_NAME_AUTO = "AnimeLibraryUpdate-auto"
        private const val WORK_NAME_MANUAL = "AnimeLibraryUpdate-manual"
        private const val WORK_NAME_CATCHUP = "AnimeLibraryUpdate-catchup"
        private const val RETIRED_HOME_WORK_NAME = "AnimeLibraryUpdate-home"

        private const val ERROR_LOG_HELP_URL = "https://github.com/owouwuiwi/nyanime/blob/main/docs/support.md"

        private const val ANIME_PER_SOURCE_QUEUE_WARNING_THRESHOLD = 60
        private const val MAX_AUTO_UPDATE_PER_RUN = 24
        private const val AUTO_REQUEST_SPACING_MS = 1_500L
        private const val CATCHUP_DELAY_HOURS = 1L
        private const val REOPEN_CHECK_INTERVAL_MS = 24 * 60 * 60_000L
        private val autoJobActive = AtomicBoolean(false)

        /**
         * Key for category to update.
         */
        private const val KEY_CATEGORY = "animeCategory"

        fun cancelAllWorks(context: Context) {
            context.workManager.cancelAllWorkByTag(TAG)
        }

        /** Stop a one-time Home job left by earlier previews without touching manual or periodic updates. */
        fun retireHomeRefresh(context: Context) {
            val store = Injekt.get<PreferenceStore>()
            val retired = store.getBoolean(Preference.appStateKey("recent_anime_home_work_retired"), false)
            if (retired.get()) return
            context.workManager.cancelUniqueWork(RETIRED_HOME_WORK_NAME).result.get()
            context.cancelNotification(Notifications.ID_LIBRARY_SIZE_WARNING)
            store.getLong(Preference.appStateKey("anime_home_refresh_request"), 0L).delete()
            store.getLong(Preference.appStateKey("anime_home_refresh_success"), 0L).delete()
            retired.set(true)
        }

        fun setupTask(
            context: Context,
            prefInterval: Int? = null,
        ) {
            eu.kanade.tachiyomi.data.releases.ReleaseMonitor.setup(context)
        }

        fun catchUpAfterReopen(context: Context) {
            eu.kanade.tachiyomi.data.releases.ReleaseMonitor.enqueue(context)
        }

        private fun scheduleAutomaticFollowUp(context: Context) {
            val nextSlot = System.currentTimeMillis() / TimeUnit.HOURS.toMillis(1) + 1L
            enqueueAutomaticBatch(context, "${WORK_NAME_CATCHUP}-$nextSlot", CATCHUP_DELAY_HOURS)
        }

        private fun enqueueAutomaticBatch(context: Context, name: String, delayHours: Long) {
            val preferences = Injekt.get<LibraryPreferences>()
            if (preferences.autoUpdateInterval().get() <= 0) return
            val request = OneTimeWorkRequestBuilder<AnimeLibraryUpdateJob>()
                .addTag(TAG)
                .addTag(WORK_NAME_AUTO)
                .addTag(WORK_NAME_CATCHUP)
                .setConstraints(automaticConstraints(preferences))
                .setInitialDelay(delayHours, TimeUnit.HOURS)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.MINUTES)
                .build()
            context.workManager.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request)
        }

        private fun automaticConstraints(preferences: LibraryPreferences): Constraints {
            val restrictions = preferences.autoUpdateDeviceRestrictions().get()
            val networkType = if (DEVICE_NETWORK_NOT_METERED in restrictions) {
                NetworkType.UNMETERED
            } else {
                NetworkType.CONNECTED
            }
            val networkRequestBuilder = NetworkRequest.Builder()
            if (DEVICE_ONLY_ON_WIFI in restrictions) {
                networkRequestBuilder.addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            }
            if (DEVICE_NETWORK_NOT_METERED in restrictions) {
                networkRequestBuilder.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            }
            return Constraints.Builder()
                // 'networkRequest' only applies to Android 9+, otherwise 'networkType' is used.
                .setRequiredNetworkRequest(networkRequestBuilder.build(), networkType)
                .setRequiresCharging(DEVICE_CHARGING in restrictions)
                .setRequiresBatteryNotLow(true)
                .build()
        }
        fun startNow(
            context: Context,
            category: Category? = null,
        ): Boolean {
            val wm = context.workManager
            if (wm.isRunning(TAG)) {
                // Already running either as a scheduled or manual job
                return false
            }

            val inputData = workDataOf(
                KEY_CATEGORY to category?.id,
            )
            val request = OneTimeWorkRequestBuilder<AnimeLibraryUpdateJob>()
                .addTag(TAG)
                .addTag(WORK_NAME_MANUAL)
                .setInputData(inputData)
                .build()
            wm.enqueueUniqueWork(WORK_NAME_MANUAL, ExistingWorkPolicy.KEEP, request)

            return true
        }

        fun stop(context: Context) {
            val wm = context.workManager
            val workQuery = WorkQuery.Builder.fromTags(listOf(TAG))
                .addStates(listOf(WorkInfo.State.RUNNING))
                .build()
            wm.getWorkInfos(workQuery).get()
                // Should only return one work but just in case
                .forEach {
                    wm.cancelWorkById(it.id)

                    // Re-enqueue cancelled scheduled work
                    if (it.tags.contains(WORK_NAME_AUTO)) {
                        setupTask(context)
                    }
                }
        }
    }
}
