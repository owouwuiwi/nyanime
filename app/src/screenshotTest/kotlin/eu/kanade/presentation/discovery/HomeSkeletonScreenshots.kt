package eu.kanade.presentation.discovery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import tachiyomi.domain.discovery.SourceHomeGroup

@PreviewTest
@Preview(name = "NarrowSkeleton", widthDp = 280, heightDp = 900, locale = "it")
@Preview(name = "LargeTextSkeleton", widthDp = 320, heightDp = 1000, fontScale = 1.5f, locale = "it")
@Preview(name = "WideSkeleton", widthDp = 840, heightDp = 900, locale = "it")
@Composable
fun HomeLoadingSkeletonScreenshot() {
    TachiyomiPreviewTheme(appTheme = AppTheme.NYANIME) {
        Surface(Modifier.fillMaxSize()) { HomeLoadingSkeleton() }
    }
}

@PreviewTest
@Preview(name = "NarrowMangaSkeleton", widthDp = 280, heightDp = 900, fontScale = 1.5f, locale = "it")
@Composable
fun MangaLoadingSkeletonScreenshot() {
    TachiyomiPreviewTheme(appTheme = AppTheme.NYANIME) {
        Surface(Modifier.fillMaxSize()) { HomeMangaLoadingSkeleton() }
    }
}

@PreviewTest
@Preview(name = "NarrowLoadedHero", widthDp = 280, heightDp = 900, fontScale = 1.5f, locale = "it")
@Composable
fun HomeLoadedHeroScreenshot() {
    TachiyomiPreviewTheme(appTheme = AppTheme.NYANIME) {
        Surface(Modifier.fillMaxSize()) {
            CinematicHero(
                title = "Un viaggio attraverso i ricordi di una città lontana",
                eyebrow = "In evidenza · 1 di 5",
                metadata = "Nuovi episodi · Avventura",
                description = null,
                actionLabel = "Apri episodi",
                onOpen = {},
                onSources = {},
                artwork = {
                    Box(
                        Modifier.matchParentSize().background(
                            MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                    )
                },
            )
        }
    }
}

@PreviewTest
@Preview(name = "NarrowHeader", widthDp = 280, heightDp = 280, locale = "it")
@Preview(name = "LargeTextHeader", widthDp = 280, heightDp = 320, fontScale = 1.5f, locale = "it")
@Composable
fun HomeNarrowHeaderScreenshot() {
    TachiyomiPreviewTheme(appTheme = AppTheme.NYANIME) {
        DiscoveryHomeHeader(
            selectedHome = null,
            onSelect = {},
            onBack = null,
            onSearch = {},
            onRefresh = {},
            onUpdates = {},
            hasUpdates = true,
            homes = listOf(
                SourceHomeGroup("manga", "Manga", emptyList()),
                SourceHomeGroup("cartoons", "Cartoni", emptyList()),
                SourceHomeGroup("films", "Film", emptyList()),
                SourceHomeGroup("series", "Serie TV", emptyList()),
            ),
        )
    }
}
