package tachiyomi.presentation.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import tachiyomi.presentation.widget.entries.anime.AnimeUpdatesGridCoverScreenGlanceWidget
import tachiyomi.presentation.widget.entries.anime.AnimeUpdatesGridGlanceWidget
import tachiyomi.presentation.widget.entries.manga.MangaUpdatesGridCoverScreenGlanceWidget
import tachiyomi.presentation.widget.entries.manga.MangaUpdatesGridGlanceWidget

/** Also refresh widgets when a background worker updates the library with no Activity alive. */
object ReleaseWidgetUpdater {
    suspend fun refresh(context: Context) {
        AnimeUpdatesGridGlanceWidget().updateAll(context)
        AnimeUpdatesGridCoverScreenGlanceWidget().updateAll(context)
        MangaUpdatesGridGlanceWidget().updateAll(context)
        MangaUpdatesGridCoverScreenGlanceWidget().updateAll(context)
    }
}
