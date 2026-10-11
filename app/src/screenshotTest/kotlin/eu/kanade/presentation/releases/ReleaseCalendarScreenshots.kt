package eu.kanade.presentation.releases

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import cafe.adriel.voyager.navigator.Navigator
import com.android.tools.screenshot.PreviewTest
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.presentation.components.releases.ReleaseAgendaItem
import eu.kanade.presentation.components.releases.ReleaseCalendarContent
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.releases.ReleaseMedium
import kotlinx.collections.immutable.persistentMapOf
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

@PreviewTest
@Preview(name = "ReleaseAgenda", widthDp = 393, heightDp = 850, locale = "it")
@Preview(name = "ReleaseAgendaNarrow", widthDp = 320, heightDp = 850, fontScale = 1.5f, locale = "it")
@Composable
fun ReleaseAgendaScreenshot() = AgendaPreview()

@PreviewTest
@Preview(name = "ReleaseAgendaLegacy", widthDp = 393, heightDp = 850, locale = "it")
@Composable
fun ReleaseAgendaLegacyScreenshot() = AgendaPreview(modern = false)

@PreviewTest
@Preview(name = "ReleaseAgendaEmpty", widthDp = 320, heightDp = 850, locale = "it")
@Composable
fun ReleaseAgendaEmptyScreenshot() = AgendaPreview(empty = true)

@PreviewTest
@Preview(name = "ReleaseCalendar", widthDp = 393, heightDp = 850, locale = "it")
@Preview(name = "ReleaseCalendarNarrow", widthDp = 320, heightDp = 850, fontScale = 1.5f, locale = "it")
@Composable
fun ReleaseCalendarScreenshot() = AgendaPreview(calendar = true)

@Composable
private fun AgendaPreview(modern: Boolean = true, empty: Boolean = false, calendar: Boolean = false) {
    Injekt.addSingleton(UiPreferences(InMemoryPreferenceStore()))
    TachiyomiPreviewTheme(
        appTheme = if (modern) AppTheme.NYANIME else AppTheme.DEFAULT,
        modernUi = modern,
        darkTheme = modern,
    ) {
        Navigator(AgendaScreen(empty, calendar))
    }
}

private class AgendaScreen(val empty: Boolean, val calendar: Boolean) : Screen() {
    @Composable
    override fun Content() {
        val date = LocalDate.of(2026, 9, 28)
        val event = ReleaseAgendaItem(
            "scheduled",
            1,
            "Il ritorno nella citta delle stelle: una promessa per domani",
            null,
            date.atTime(18, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            "Trasmissione originale · Episodio 15",
        )
        ReleaseCalendarContent(
            YearMonth.from(date), if (calendar || empty) date else null, persistentMapOf(date to 2),
            if (empty) {
                emptyList()
            } else {
                listOf(
                    event.copy(key = "past", at = event.at - 10 * 86_400_000L),
                    event,
                    event.copy(
                        key = "manga-available",
                        title = "Le avventure oltre l’orizzonte",
                        label = "Disponibile · Capitolo 12",
                        itemId = 1,
                        medium = ReleaseMedium.MANGA,
                    ),
                    event.copy(key = "future", at = event.at + 12 * 86_400_000L),
                )
            },
            false, null, {}, {}, {}, {},
            initialCalendar = calendar, showBack = false, showMediaFilter = true, today = date,
        )
    }
}
