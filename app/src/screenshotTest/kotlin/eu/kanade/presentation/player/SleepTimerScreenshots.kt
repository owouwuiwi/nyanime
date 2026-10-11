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
import eu.kanade.tachiyomi.ui.player.controls.TopRightPlayerControls
import eu.kanade.tachiyomi.ui.player.controls.components.sheets.SleepTimerContent
import eu.kanade.tachiyomi.ui.player.controls.components.sheets.SleepTimerEntry

@PreviewTest
@Preview(name = "QuickPortrait", widthDp = 393, heightDp = 720, locale = "it")
@Preview(name = "QuickLandscape", widthDp = 800, heightDp = 360, locale = "it")
@Preview(name = "QuickLargeText", widthDp = 320, heightDp = 640, fontScale = 1.5f, locale = "it")
@Composable
fun SleepTimerQuickScreenshot() = TimerPreview(0)

@PreviewTest
@Preview(name = "ActivePortrait", widthDp = 393, heightDp = 760, locale = "it")
@Preview(name = "ActiveLandscape", widthDp = 800, heightDp = 360, locale = "it")
@Preview(name = "ActiveLargeText", widthDp = 320, heightDp = 640, fontScale = 1.5f, locale = "it")
@Composable
fun SleepTimerActiveScreenshot() = TimerPreview(1742)

@PreviewTest
@Preview(name = "CustomPortrait", widthDp = 393, heightDp = 640, locale = "it")
@Preview(name = "CustomKeyboardHeight", widthDp = 800, heightDp = 250, locale = "it")
@Composable
fun SleepTimerCustomScreenshot() = TimerPreview(0, custom = true)

@PreviewTest
@Preview(name = "Legacy", widthDp = 393, heightDp = 640, locale = "it")
@Composable
fun SleepTimerLegacyScreenshot() = TimerPreview(1742, modern = false)

@Composable
private fun TimerPreview(
    remaining: Int,
    custom: Boolean = false,
    modern: Boolean = true,
    endEpisode: Boolean = false,
    customMinutes: Int = 30,
) {
    TachiyomiPreviewTheme(appTheme = if (modern) AppTheme.NYANIME else AppTheme.DEFAULT, modernUi = modern) {
        Surface {
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                SleepTimerContent(
                    remaining,
                    {},
                    {},
                    {},
                    initiallyCustom = custom,
                    atEpisodeEnd = endEpisode,
                    initialCustomMinutes = customMinutes,
                )
            }
        }
    }
}

@PreviewTest
@Preview(name = "EndPortrait", widthDp = 393, heightDp = 780, locale = "it")
@Preview(name = "EndLandscape", widthDp = 800, heightDp = 360, locale = "it")
@Preview(name = "EndLargeText", widthDp = 320, heightDp = 720, fontScale = 1.5f, locale = "it")
@Composable
fun SleepTimerEndScreenshot() = TimerPreview(0, endEpisode = true)

@PreviewTest
@Preview(name = "RememberedCustom", widthDp = 393, heightDp = 640, locale = "it")
@Composable
fun SleepTimerRememberedScreenshot() = TimerPreview(0, custom = true, customMinutes = 75)

@PreviewTest
@Preview(name = "Entry", widthDp = 393, heightDp = 140, locale = "it")
@Composable
fun SleepTimerEntryScreenshot() {
    TachiyomiPreviewTheme(appTheme = AppTheme.NYANIME) {
        Surface { Box(Modifier.padding(16.dp)) { SleepTimerEntry(0, {}) } }
    }
}

@PreviewTest
@Preview(name = "ActiveControls", widthDp = 600, heightDp = 150, locale = "it")
@Composable
fun SleepTimerControlsScreenshot() {
    TachiyomiPreviewTheme(appTheme = AppTheme.NYANIME) {
        Surface {
            TopRightPlayerControls(
                onCastClick = {}, autoPlayEnabled = true, onToggleAutoPlay = {},
                onSubtitlesClick = {}, onSubtitlesLongClick = {}, onAudioClick = {}, onAudioLongClick = {},
                onQualityClick = {}, isEpisodeOnline = true,
                isAnime4KSmartEnabled = true, anime4KSmartLabel = "SM", onToggleAnime4KSmart = {},
                sleepTimerRemaining = 1742, onSleepTimerClick = {}, onMoreClick = {}, onMoreLongClick = {},
            )
        }
    }
}
