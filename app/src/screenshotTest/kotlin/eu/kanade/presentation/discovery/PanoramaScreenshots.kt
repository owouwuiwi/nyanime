package eu.kanade.presentation.discovery

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.presentation.theme.TachiyomiPreviewTheme

@PreviewTest
@Preview(name = "PanoramaNarrow", widthDp = 280, heightDp = 900, locale = "it")
@Preview(name = "PanoramaLargeText", widthDp = 320, heightDp = 1100, fontScale = 1.5f, locale = "en")
@Preview(name = "PanoramaDark", widthDp = 390, heightDp = 1100, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "PanoramaWide", widthDp = 840, heightDp = 1100)
@Composable
fun PanoramaLoadedScreenshot() {
    TachiyomiPreviewTheme(appTheme = AppTheme.NYANIME) {
        Surface {
            Column {
                PanoramaCarousel(
                    items = listOf(
                        "Un viaggio attraverso i ricordi di una città lontana",
                        "Nuovi orizzonti",
                        "Il giardino",
                    ),
                    itemKey = { it },
                    title = { it },
                    metadata = { "Avventura · Capitoli e storie" },
                    artworkData = { it },
                    onOpen = {},
                ) { item, modifier, _ ->
                    Box(
                        modifier.background(Brush.verticalGradient(listOf(Color(0xFF316E7C), Color(0xFF153641)))),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(item, Modifier.padding(24.dp), color = Color.White, textAlign = TextAlign.Center)
                    }
                }
                SectionHeader("Continua a leggere")
                PanoramaResumeCard(
                    title = "Un titolo lungo con il suo capitolo corrente",
                    subtitle = "Capitolo 12",
                    manga = true,
                    onResume = {},
                    modifier = Modifier.padding(horizontal = 20.dp),
                ) { modifier -> Box(modifier.background(MaterialTheme.colorScheme.secondaryContainer)) }
            }
        }
    }
}

@PreviewTest
@Preview(name = "PanoramaPlaceholder", widthDp = 390, heightDp = 900)
@Preview(name = "PanoramaPlaceholderLarge", widthDp = 320, heightDp = 1000, fontScale = 1.5f)
@Composable
fun PanoramaPlaceholderScreenshot() {
    TachiyomiPreviewTheme(appTheme = AppTheme.NYANIME) {
        Surface(Modifier.fillMaxSize()) {
            Column {
                PanoramaHeroSkeleton()
                PanoramaResumeSkeleton()
            }
        }
    }
}

@PreviewTest
@Preview(name = "WatchResumeNarrow", widthDp = 280, heightDp = 260, locale = "it")
@Preview(name = "WatchResumeLargeText", widthDp = 320, heightDp = 330, fontScale = 1.5f, locale = "en")
@Preview(name = "WatchResumeDark", widthDp = 390, heightDp = 260, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "WatchResumeWide", widthDp = 840, heightDp = 260)
@Composable
fun PanoramaWatchResumeScreenshot() {
    TachiyomiPreviewTheme(appTheme = AppTheme.NYANIME) {
        Surface(Modifier.fillMaxSize()) {
            Column {
                SectionHeader("Continua a guardare")
                BoxWithConstraints {
                    val width = PanoramaResumeLayout.width(maxWidth, 3)
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(3) { index ->
                            PanoramaWatchResumeCard(
                                title = if (index ==
                                    0
                                ) {
                                    "Un viaggio attraverso i ricordi di una città lontana"
                                } else {
                                    "Nuovi orizzonti"
                                },
                                episode = "Episodio 12 · Una promessa sotto il cielo d'estate",
                                timing = "12:08 / 24:10",
                                progress = .5f,
                                onResume = {},
                                modifier = Modifier.width(width),
                                menu = {},
                            ) { modifier ->
                                Box(
                                    modifier.background(
                                        Brush.linearGradient(listOf(Color(0xFFBFA477), Color(0xFF315362))),
                                    ),
                                    contentAlignment = Alignment.Center,
                                ) { Text("12", style = MaterialTheme.typography.displayLarge, color = Color.White) }
                            }
                        }
                    }
                }
            }
        }
    }
}
