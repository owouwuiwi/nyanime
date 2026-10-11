package eu.kanade.presentation.download

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.presentation.entries.anime.components.UltraDeleteConfirmation
import eu.kanade.presentation.entries.anime.components.UltraStatusRow
import eu.kanade.presentation.entries.anime.components.UltraTaskContent
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.tachiyomi.data.download.anime.ultra.UltraPhase
import eu.kanade.tachiyomi.data.download.anime.ultra.UltraTask

@PreviewTest
@Preview(name = "Modern", widthDp = 393, heightDp = 680, locale = "it")
@Preview(name = "NarrowLargeText", widthDp = 320, heightDp = 820, fontScale = 1.4f, locale = "it")
@Composable
fun UltraDownloadsScreenshot() = UltraPreview(modern = true)

@PreviewTest
@Preview(name = "Legacy", widthDp = 393, heightDp = 680, locale = "it")
@Composable
fun UltraDownloadsLegacyScreenshot() = UltraPreview(modern = false)

@PreviewTest
@Preview(name = "DownloadDetails", widthDp = 393, heightDp = 820, locale = "it")
@Preview(name = "DownloadDetailsNarrow", widthDp = 320, heightDp = 820, fontScale = 1.4f, locale = "it")
@Composable
fun UltraDownloadsDetailsScreenshot() {
    TachiyomiPreviewTheme(appTheme = AppTheme.NYANIME, modernUi = true) {
        Surface {
            UltraTaskContent(
                current = UltraTask(
                    "content://example/episode",
                    "Una lunga avventura · Episodio 12",
                    phase = UltraPhase.WAITING,
                    progress = 48,
                    message = "In pausa per raffreddamento",
                ),
                details = "Originale · 380 MB\nCopia Ultra · 0 B\nTemporanei · 760 MB\nEpisodio 12.mp4",
                busy = false, feedback = null, primaryLabel = "Guarda l'originale",
                onPrimary = {}, onToggle = {}, onExport = {}, onCancel = {}, onDelete = {},
            )
        }
    }
}

@Composable
private fun UltraPreview(modern: Boolean) {
    TachiyomiPreviewTheme(appTheme = if (modern) AppTheme.NYANIME else AppTheme.DEFAULT, modernUi = modern) {
        Surface {
            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text("Sul tuo dispositivo", style = MaterialTheme.typography.headlineSmall)
                Text("I tuoi episodi, sempre qui", style = MaterialTheme.typography.bodyMedium)
                val task = UltraTask("content://example/episode", "Una lunga avventura · Episodio 12")
                Text(task.title, style = MaterialTheme.typography.titleMedium)
                UltraStatusRow(task.copy(phase = UltraPhase.READY, progress = 100), {}, onDelete = {})
                UltraStatusRow(
                    task.copy(
                        phase = UltraPhase.RUNNING,
                        progress = 48,
                        message = "A+ HQ · 12:05 di 24:10",
                    ),
                    {
                    },
                )
                UltraStatusRow(
                    task.copy(
                        phase = UltraPhase.WAITING,
                        progress = 48,
                        message = "In pausa per raffreddamento",
                    ),
                    {
                    },
                )
                UltraStatusRow(task.copy(phase = UltraPhase.WAITING, message = "In attesa dello schermo spento"), {})
                UltraStatusRow(
                    task.copy(
                        phase = UltraPhase.PAUSED,
                        progress = 71,
                        message = "In pausa · progresso conservato",
                    ),
                    {
                    },
                )
                UltraStatusRow(task, {})
            }
        }
    }
}

@PreviewTest
@Preview(name = "DeleteDownloads", widthDp = 393, heightDp = 820, locale = "it")
@Preview(name = "DeleteDownloadsNarrow", widthDp = 320, heightDp = 820, fontScale = 1.4f, locale = "it")
@Composable
fun UltraDownloadsDeleteScreenshot() {
    TachiyomiPreviewTheme(appTheme = AppTheme.NYANIME, modernUi = true) {
        UltraDeleteConfirmation(
            title = "3 episodi selezionati", onlyUltra = false, onChoose = {}, size = "2,4 GB",
            busy = false, progress = null, error = null, onDismiss = {}, onConfirm = {},
        )
    }
}
