package eu.kanade.tachiyomi.data.releases

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tachiyomi.domain.entries.anime.repository.AnimeRepository
import tachiyomi.domain.items.episode.repository.EpisodeRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** One alarm for the nearest reminder, not an alarm per title or a network polling timer. */
object ReleaseReminders {
    const val CHANNEL = "release-reminders"
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Saving a choice must finish rescheduling even when the settings screen is closed. */
    fun reschedule(context: Context) {
        val application = context.applicationContext
        scope.launch {
            try {
                schedule(application)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The durable preference is picked up by the existing foreground/periodic pass.
            }
        }
    }

    fun exactAllowed(context: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun cancel(context: Context) = context.getSystemService(AlarmManager::class.java).cancel(intent(context))

    suspend fun schedule(context: Context) {
        mutex.withLock { scheduleLocked(context) }
    }

    private suspend fun scheduleLocked(context: Context) {
        if (ReleaseRestoreGuard.active) return
        cancel(context)
        val preferences = ReleasePreferences()
        if (!preferences.enabled.get() ||
            !preferences.reminders.get() ||
            Injekt.get<BasePreferences>().incognitoMode().get()
        ) {
            return
        }
        createChannel(context)
        val events = eligibleEvents()
        val now = System.currentTimeMillis()
        val receipts = ReleaseReminderReceipts()
        val reminders = ReleaseReminderPlan.pending(events, receipts.delivered(), preferences.selectedReminders())
        for (reminder in reminders) {
            if (reminder.at <= now) deliverDue(context, reminder, receipts, now)
        }
        val next = reminders.firstOrNull { it.at > now } ?: return
        scheduleAlarm(context, next.at)
    }

    internal fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                context.getString(R.string.release_reminder),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    private suspend fun eligibleEvents(): List<AiringEvent> {
        val store = ReleaseStore()
        val followed = store.monitoredIds(ReleaseMedium.ANIME).toSet()
        val result = mutableListOf<AiringEvent>()
        val eligible = mutableMapOf<Long, Boolean>()
        for (event in AiringRepository().effectiveEvents().first()) {
            if (event.entryId !in followed) continue
            val allowed = eligible[event.entryId] ?: (
                store.subscription(ReleaseMedium.ANIME, event.entryId).reminder &&
                    ReleaseEligibility.source(ReleaseMedium.ANIME, event.entryId) != null
                ).also { eligible[event.entryId] = it }
            if (!allowed) continue
            result += event
        }
        return result
    }

    private suspend fun deliverDue(
        context: Context,
        reminder: ReleaseReminder,
        receipts: ReleaseReminderReceipts,
        now: Long,
    ) {
        suspend fun acknowledge() {
            receipts.record(reminder, now)
            if (reminder.kind == ReleaseReminderKind.AIRING) {
                reminder.events.forEach { AiringRepository().reminded(it) }
            }
        }
        // A missed reminder from an old backup/offline period must not produce a flood.
        // Do not record it as delivered: a subsequently postponed date can still deserve an alert.
        if (reminder.expired(now)) return
        if (!ReleaseNotifications.canPost(context, CHANNEL)) return
        val event = reminder.event
        val anime = Injekt.get<AnimeRepository>().getAnimeById(event.entryId)
        val seen = reminder.events.any { edition ->
            Injekt.get<EpisodeRepository>().getEpisodeByAnimeId(edition.entryId)
                .any { it.seen && it.episodeNumber == event.episode.toDouble() }
        }
        if (!seen) {
            val hidden = Injekt.get<SecurityPreferences>().hideNotificationContent().get()
            val text = ReleaseNotificationCopy.from(context).reminder(
                anime.title,
                event,
                now,
                if (event.variants.isEmpty()) ScheduleAirType.RAW else AnimeSchedulePreferences().type,
                hidden,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_ani)
                .setContentTitle(text.title)
                .setContentText(text.summary)
                .setStyle(NotificationCompat.BigTextStyle().setBigContentTitle(text.headline).bigText(text.details))
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setContentIntent(
                    NotificationReceiver.openEpisodePendingActivity(context, anime, Notifications.ID_NEW_EPISODES),
                )
                .setAutoCancel(true).setOnlyAlertOnce(true).build()
            // Stable identity also makes replay after a process death replace the existing tray item.
            val tag = "release-reminder-${reminder.keys.sorted().first()}-${reminder.kind.name}"
            ReleaseNotifications.reserveSlot(context, tag, event.episode)
            try {
                NotificationManagerCompat.from(context).notify(tag, event.episode, notification)
            } catch (_: SecurityException) {
                // Permission can be revoked between the check and notify; keep the receipt pending.
                return
            }
        }
        acknowledge()
    }

    private fun scheduleAlarm(context: Context, at: Long) {
        val alarm = context.getSystemService(AlarmManager::class.java)
        try {
            if (exactAllowed(context)) {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(context))
            } else {
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(context))
            }
        } catch (_: SecurityException) {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(context))
        }
    }

    private fun intent(context: Context) = PendingIntent.getBroadcast(
        context,
        23061,
        Intent(context, ReleaseReminderReceiver::class.java).setAction("nyanime.release.REMIND"),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

class ReleaseReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                ReleaseStore().alignSchedule(AiringRepository().effectiveEvents().first())
                ReleaseReminders.schedule(context.applicationContext)
                if (intent.action != "nyanime.release.REMIND") ReleaseMonitor.setup(context.applicationContext)
                ReleaseMonitor.enqueue(context.applicationContext)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Recover from durable cache on the next foreground/periodic pass.
            } finally {
                pending.finish()
            }
        }
    }
}
