package eu.kanade.tachiyomi.data.news

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.news.NewsReaderActivity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class NewsNotifications(private val context: Context, private val repository: NewsRepository) {
    suspend fun deliver() = deliveryLock.withLock { deliverLocked() }

    private suspend fun deliverLocked() {
        val manager = NotificationManagerCompat.from(context)
        val channel = NotificationChannel(
            CHANNEL,
            context.getString(R.string.news_title),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        manager.createNotificationChannel(channel)
        val snapshot = repository.store.state.value
        val all = snapshot.pending.mapNotNull { snapshot.articles[it] }
        val index = if (all.any { snapshot.sources[it.source]?.alerts == NewsAlerts.PERSONAL }) {
            NewsInterestIndex(snapshot, NewsPersonalTitles().load())
        } else {
            null
        }
        val enabled = all.filter {
            snapshot.sources[it.source]?.let { config ->
                config.enabled &&
                    !it.read &&
                    (
                        config.alerts == NewsAlerts.ALL ||
                            config.alerts == NewsAlerts.PERSONAL &&
                            index?.match(it)?.reliable == true
                        )
            } ==
                true
        }
        val channelEnabled = manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
        if (!manager.areNotificationsEnabled() || !channelEnabled) {
            // Enabling permission later must not release an old notification backlog.
            acknowledge(snapshot.pending)
            return
        }
        enabled.groupBy { it.source }.forEach { (source, items) ->
            val newest = items.maxBy { it.article.publishedAt ?: 0 }
            val intent = Intent(
                context,
                NewsReaderActivity::class.java,
            ).putExtra(NewsReaderActivity.ARTICLE, newest.key)
            val pending = PendingIntent.getActivity(
                context,
                source.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notice = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_ani)
                .setContentTitle(
                    if (items.size ==
                        1
                    ) {
                        newest.article.title
                    } else {
                        context.getString(R.string.news_notification_count, items.size, newest.article.publisher)
                    },
                )
                .setContentText(if (items.size == 1) newest.article.publisher else newest.article.title)
                .setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(true)
                .setGroup(CHANNEL).setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            if (items.size ==
                1
            ) {
                notice.setStyle(
                    NotificationCompat.BigTextStyle().bigText(newest.article.excerpt ?: newest.article.title),
                )
            } else {
                notice.setStyle(
                    NotificationCompat.InboxStyle().also { style ->
                        items.take(5).forEach { style.addLine(it.article.title) }
                    },
                )
            }
            manager.notify("news:$source", source.hashCode(), notice.build())
        }
        acknowledge(snapshot.pending)
    }

    private suspend fun acknowledge(keys: Set<String>) = repository.store.update {
        it.copy(receipts = it.receipts + keys, pending = it.pending - keys)
    }

    companion object {
        private val deliveryLock = Mutex()
        private const val CHANNEL = "news_articles"
    }
}
