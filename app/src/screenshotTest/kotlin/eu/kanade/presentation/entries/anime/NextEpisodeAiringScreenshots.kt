package eu.kanade.presentation.entries.anime

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.presentation.entries.anime.components.NextEpisodeAiringCard
import eu.kanade.presentation.theme.TachiyomiPreviewTheme

@PreviewTest
@Preview(name = "AiringPortrait", widthDp = 393, heightDp = 300, locale = "it")
@Preview(name = "AiringNarrowLargeText", widthDp = 320, heightDp = 350, fontScale = 1.5f, locale = "it")
@Composable
fun NextEpisodeAiringScreenshot() = AiringPreview()

@PreviewTest
@Preview(name = "AiringLegacy", widthDp = 393, heightDp = 300, locale = "it")
@Composable
fun NextEpisodeAiringLegacyScreenshot() = AiringPreview(modern = false)

@Composable
private fun AiringPreview(modern: Boolean = true) {
    TachiyomiPreviewTheme(appTheme = if (modern) AppTheme.NYANIME else AppTheme.DEFAULT, modernUi = modern) {
        Surface {
            Column(modifier = Modifier.fillMaxWidth()) {
                NextEpisodeAiringCard(
                    title = "Episodio 15 · La promessa del domani",
                    scheduledDate = "28 settembre 2026, 18:30",
                    countdown = "Tra 1 giorno e 4 ore",
                )
                Text("Episodio 14")
            }
        }
    }
}
