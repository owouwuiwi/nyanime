package eu.kanade.tachiyomi.data.releases

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.data.discovery.ResumeVisibility
import eu.kanade.tachiyomi.data.library.anime.AnimeLibraryUpdateNotifier
import eu.kanade.tachiyomi.data.library.manga.MangaLibraryUpdateNotifier
import eu.kanade.tachiyomi.data.notification.Notifications
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tachiyomi.domain.entries.anime.repository.AnimeRepository
import tachiyomi.domain.entries.manga.repository.MangaRepository
import tachiyomi.domain.items.chapter.repository.ChapterRepository
import tachiyomi.domain.items.episode.repository.EpisodeRepository
import tachiyomi.presentation.widget.entries.anime.AnimeUpdatesGridCoverScreenGlanceWidget
import tachiyomi.presentation.widget.entries.anime.AnimeUpdatesGridGlanceWidget
import tachiyomi.presentation.widget.entries.manga.MangaUpdatesGridCoverScreenGlanceWidget
import tachiyomi.presentation.widget.entries.manga.MangaUpdatesGridGlanceWidget
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object ReleaseNotifications {
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Leave room for other app notifications under Android's per-app notification limit. */
    fun reserveSlot(context: Context, tag: String, id: Int) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val active = manager.activeNotifications
        if (active.any { it.tag == tag && it.id == id }) return
        val releases = active.filter {
            it.tag == "release-anime" || it.tag == "release-manga" || it.tag?.startsWith("release-reminder-") == true
        }
        val removeCount = maxOf(releases.size - 39, active.size - 44, 0).coerceAtMost(releases.size)
        releases.sortedBy { it.postTime }.take(removeCount).forEach { manager.cancel(it.tag, it.id) }
        // Removing an old tray notification never removes its item from Home or Updates.
    }

    fun afterCommit(context: Context) {
        scope.launch {
            try {
                flush(context.applicationContext)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The durable outbox is retried by the monitor; never undo a successful snapshot.
            }
        }
    }

    fun canPost(context: Context, channel: String = Notifications.CHANNEL_NEW_CHAPTERS_EPISODES): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        val notificationChannel = manager.getNotificationChannel(channel) ?: return false
        return notificationChannel.importance != NotificationManager.IMPORTANCE_NONE
    }

    suspend fun flush(context: Context) = mutex.withLock {
        if (Injekt.get<BasePreferences>().incognitoMode().get() ||
            !ReleasePreferences().enabled.get()
        ) {
            return@withLock
        }
        if (ReleaseRestoreGuard.active) return@withLock
        val store = ReleaseStore()
        var changed = false
        for (medium in ReleaseMedium.entries) {
            val pending = store.pending(medium)
            if (pending.isEmpty()) continue
            changed = true
            val followed = store.monitoredIds(medium).toSet()
            val skipped = ArrayList<Long>()
            val eligible = pending.groupBy { it.entryId }.filter { (id, notices) ->
                val keep = id in followed &&
                    store.alertsAllowed(medium, id, reminder = false) &&
                    ReleasePreferences().availability.get() &&
                    ReleaseEligibility.source(medium, id) != null
                if (!keep) skipped.addAll(notices.map { it.itemId })
                keep
            }
            store.delivered(medium, skipped)
            if (!canPost(context)) continue
            val dismissed = Injekt.get<eu.kanade.domain.ui.UiPreferences>().dismissedLibraryUpdates().get()
            for ((id, notices) in eligible) {
                ReleaseNotificationDelivery.deliver(
                    allowed = {
                        canPost(context) &&
                            ReleasePreferences().enabled.get() &&
                            ReleasePreferences().availability.get() &&
                            store.alertsAllowed(medium, id, reminder = false) &&
                            !Injekt.get<BasePreferences>().incognitoMode().get()
                    },
                    post = {
                        when (medium) {
                            ReleaseMedium.ANIME -> {
                                val anime = Injekt.get<AnimeRepository>().getAnimeById(id)
                                val items = notices.mapNotNull {
                                    Injekt.get<EpisodeRepository>().getEpisodeById(it.itemId)
                                }
                                    .filterNot {
                                        val key = "${it.dateFetch}|anime|${anime.source}|" +
                                            "${anime.title}|${it.name}"
                                        it.seen || key in dismissed
                                    }
                                if (items.isNotEmpty()) {
                                    AnimeLibraryUpdateNotifier(
                                        context,
                                    ).showUpdateNotifications(
                                        listOf(anime to items.toTypedArray()),
                                        notices.associate { it.itemId to it.sourceAt },
                                    )
                                }
                            }
                            ReleaseMedium.MANGA -> {
                                val manga = Injekt.get<MangaRepository>().getMangaById(id)
                                val items = notices.mapNotNull {
                                    Injekt.get<ChapterRepository>().getChapterById(it.itemId)
                                }
                                    .filterNot {
                                        val key = "${it.dateFetch}|manga|${manga.source}|" +
                                            "${manga.title}|${it.name}"
                                        it.read || key in dismissed
                                    }
                                if (items.isNotEmpty()) {
                                    MangaLibraryUpdateNotifier(
                                        context,
                                    ).showUpdateNotifications(
                                        listOf(manga to items.toTypedArray()),
                                        notices.associate { it.itemId to it.sourceAt },
                                    )
                                }
                            }
                        }
                        // Notification posting and SQLite cannot form a distributed transaction. Stable tags
                        // replace the same Android notification if the process dies before this acknowledgement.
                    },
                    acknowledge = { store.delivered(medium, notices.map { it.itemId }) },
                )
            }
        }
        if (!changed) return@withLock
        try {
            tachiyomi.presentation.widget.ReleaseWidgetUpdater.refresh(context)
            ReleaseAgendaWidget.refresh(context)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Widget failures do not turn a delivered notification into a pending one.
        }
    }
}
