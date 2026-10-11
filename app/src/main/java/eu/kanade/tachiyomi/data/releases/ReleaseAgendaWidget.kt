package eu.kanade.tachiyomi.data.releases

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import eu.kanade.domain.base.BasePreferences
import eu.kanade.presentation.components.releases.ReleaseAgendaRepository
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.ui.main.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Reuses the local agenda projection. Updating a widget never starts catalog or source requests. */
class ReleaseAgendaWidget : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == AppWidgetManager.ACTION_APPWIDGET_UPDATE ||
            intent.action == AppWidgetManager.ACTION_APPWIDGET_OPTIONS_CHANGED
        ) {
            val pending = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    refresh(context)
                } finally {
                    pending.finish()
                }
            }
        } else {
            super.onReceive(context, intent)
        }
    }
    companion object {
        const val OPEN = "nyanime.OPEN_RELEASES"
        fun pin(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                manager.isRequestPinAppWidgetSupported
            ) {
                manager.requestPinAppWidget(ComponentName(context, ReleaseAgendaWidget::class.java), null, null)
            } else {
                android.widget.Toast.makeText(
                    context,
                    R.string.schedule_widget_manual,
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
        suspend fun refresh(context: Context) {
            try {
                refreshSnapshot(context)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Retain the last usable view and retry on the next update; widget errors cannot crash the app.
            }
        }
        private suspend fun refreshSnapshot(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ReleaseAgendaWidget::class.java))
            if (ids.isEmpty()) return
            val hidden =
                Injekt.get<BasePreferences>().incognitoMode().get() ||
                    Injekt.get<SecurityPreferences>().hideNotificationContent().get()
            val items = if (hidden) {
                emptyList()
            } else {
                withTimeoutOrNull(8_000) {
                    ReleaseAgendaRepository().snapshots().first().items.filter {
                        it.at >= LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    }.sortedBy { it.at }.take(3)
                } ?: return
            }
            val formatter = DateTimeFormatter.ofPattern("EEE d MMM · HH:mm", context.resources.configuration.locales[0])
            for (id in ids) {
                val height = manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 250)
                val count = ((height - 56) / (92 * context.resources.configuration.fontScale)).toInt().coerceIn(1, 3)
                val views = RemoteViews(context.packageName, R.layout.widget_release_agenda)
                views.setTextViewText(R.id.release_widget_heading, context.getString(R.string.release_title))
                views.removeAllViews(R.id.release_widget_rows)
                if (items.isEmpty()) {
                    val row = RemoteViews(context.packageName, R.layout.widget_release_row)
                    row.setTextViewText(
                        R.id.release_widget_title,
                        context.getString(
                            if (hidden) R.string.schedule_widget_private else R.string.schedule_widget_empty,
                        ),
                    )
                    row.setTextViewText(R.id.release_widget_time, "")
                    views.addView(R.id.release_widget_rows, row)
                }
                for (item in items.take(count)) {
                    val row = RemoteViews(context.packageName, R.layout.widget_release_row)
                    row.setTextViewText(R.id.release_widget_title, item.title)
                    row.setTextViewText(
                        R.id.release_widget_time,
                        "${Instant.ofEpochMilli(
                            item.at,
                        ).atZone(ZoneId.systemDefault()).format(formatter)}\n${item.label}",
                    )
                    row.setTextColor(
                        R.id.release_widget_time,
                        if (item.medium ==
                            ReleaseMedium.MANGA
                        ) {
                            0xFF80D8FF.toInt()
                        } else {
                            0xFFFFAB62.toInt()
                        },
                    )
                    views.addView(R.id.release_widget_rows, row)
                }
                val open = PendingIntent.getActivity(
                    context,
                    23161,
                    Intent(
                        context,
                        MainActivity::class.java,
                    ).setAction(OPEN).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                views.setOnClickPendingIntent(R.id.release_widget_root, open)
                manager.updateAppWidget(id, views)
            }
        }
    }
}
