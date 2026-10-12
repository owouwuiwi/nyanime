package eu.kanade.presentation.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.tachiyomi.ui.player.controls.components.NextEpisodeCard

@PreviewTest
@Preview(name = "NextPortrait", widthDp = 393, heightDp = 780, locale = "it")
@Preview(name = "NextLandscape", widthDp = 800, heightDp = 360, locale = "it")
@Preview(name = "NextLargeText", widthDp = 320, heightDp = 640, fontScale = 1.5f, locale = "it")
@Composable
fun NextEpisodeScreenshot() = NextPreview()

@PreviewTest
@Preview(name = "NextLegacy", widthDp = 393, heightDp = 780, locale = "it")
@Composable
fun NextEpisodeLegacyScreenshot() = NextPreview(modern = false)

@Composable
private fun NextPreview(modern: Boolean = true) {
    TachiyomiPreviewTheme(appTheme = if (modern) AppTheme.NYANIME else AppTheme.DEFAULT, modernUi = modern) {
        Surface {
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.BottomEnd) {
                NextEpisodeCard(
                    seriesTitle = "Le cronache della luna",
                    episodeTitle = "Episodio 12 · Il viaggio continua",
                    secondsRemaining = 7,
                    onPlayNow = {},
                    onCancel = {},
                )
            }
        }
    }
}
