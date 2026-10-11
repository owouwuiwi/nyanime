package eu.kanade.presentation.discovery

import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.ColorImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.asImage
import coil3.test.FakeImageLoaderEngine
import com.android.tools.screenshot.PreviewTest
import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.presentation.entries.anime.components.AnimeInfoBox
import eu.kanade.presentation.entries.anime.components.AnimeWatchButton
import eu.kanade.presentation.library.components.EntryComfortableGridItem
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.tachiyomi.data.cast.CastDevice
import eu.kanade.tachiyomi.data.cast.CastMedia
import eu.kanade.tachiyomi.data.cast.CastPlayback
import eu.kanade.tachiyomi.data.cast.CastProtocol
import eu.kanade.tachiyomi.data.cast.CastState
import eu.kanade.tachiyomi.data.discovery.LocalHomeItem
import eu.kanade.tachiyomi.ui.cast.CastRemoteActions
import eu.kanade.tachiyomi.ui.cast.CastRemoteContent
import tachiyomi.domain.discovery.SectionState
import tachiyomi.domain.discovery.SourceHomeGroup
import tachiyomi.domain.discovery.SourceHomePage
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.entries.anime.model.AnimeCover
import tachiyomi.domain.entries.anime.model.asAnimeCover
import tachiyomi.domain.items.episode.model.Episode
import tachiyomi.presentation.core.components.material.PullRefresh

@PreviewTest
@Preview(name = "FourTabs", widthDp = 393, heightDp = 150, locale = "it")
@Preview(name = "FourTabsWide", widthDp = 800, heightDp = 150, locale = "it")
@Preview(name = "FourTabsLargeText", widthDp = 320, heightDp = 230, fontScale = 1.4f, locale = "it")
@Composable
fun NyanimeCenteredTabsScreenshot() {
    TachiyomiPreviewTheme(appTheme = AppTheme.NYANIME) {
        DiscoveryHomeHeader(
            null,
            {},
            null,
            {},
            {},
            listOf(
                SourceHomeGroup("cartoons", "Cartoni", emptyList()),
                SourceHomeGroup("films", "Film", emptyList()),
                SourceHomeGroup("series", "Serie TV", emptyList()),
            ),
        )
    }
}

@PreviewTest
@Preview(name = "AutomaticRefresh", widthDp = 393, heightDp = 240, locale = "it")
@Composable
fun NyanimeAutomaticRefreshScreenshot() {
    TachiyomiPreviewTheme(appTheme = AppTheme.NYANIME) {
        Surface {
            Column {
                DiscoveryHomeHeader(null, {}, null, {}, {}, emptyList())
                PullRefresh(refreshing = true, enabled = true, onRefresh = {}, indicatorOnGestureOnly = true) {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(3) {
                            Text("Contenuto della Home", Modifier.padding(16.dp))
                        }
                    }
                }
            }
        }
    }
}

private val titles = listOf("Frieren · Oltre la fine del viaggio", "Cowboy Bebop", "Dungeon Meshi")
private val previewAnime = titles.mapIndexed { index, title ->
    Anime.create().copy(
        id = index + 1L,
        url = "/series/preview-$index",
        title = title,
        source = 1,
        thumbnailUrl = "preview://$index",
        backgroundUrl = "preview://background",
        author = "Kanehito Yamada",
        description = "Dopo la fine di un lungo viaggio, una nuova avventura comincia.",
        initialized = true,
    )
}

/** Optional local artwork is never downloaded by previews or packaged in the application. */
@Composable
@OptIn(DelicateCoilApi::class)
internal fun PreviewImages() {
    val context = LocalContext.current
    remember(context) {
        val images = (0..2).map { index ->
            Anime::class.java.classLoader?.getResourceAsStream("nyanime-preview/poster-$index.jpg")
                ?.use { BitmapFactory.decodeStream(it)?.asImage() }
                ?: ColorImage(listOf(0xFF355B70, 0xFF79533A, 0xFF517550)[index].toInt(), 400, 600)
        }
        val engine = FakeImageLoaderEngine.Builder()
            .apply {
                images.forEachIndexed { index, image ->
                    intercept({
                        when (it) {
                            is Anime -> it.id == index + 1L
                            is AnimeCover -> it.animeId == index + 1L
                            is tachiyomi.domain.entries.manga.model.Manga -> it.id == index + 1L
                            is tachiyomi.domain.entries.manga.model.MangaCover -> it.mangaId == index + 1L
                            else -> it == "preview://$index" || it == "https://preview.example/poster-$index.jpg"
                        }
                    }, image)
                }
            }
            .default(images[2])
            .build()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).components { add(engine) }.build())
    }
}

@PreviewTest
@Preview(name = "Home", widthDp = 393, heightDp = 1100, locale = "it")
@Preview(name = "HomeLargeText", widthDp = 320, heightDp = 1100, fontScale = 1.4f, locale = "it")
@Composable
fun NyanimeHomeScreenshot() = HomePreview(modern = true)

@PreviewTest
@Preview(name = "LegacyHome", widthDp = 393, heightDp = 1100, locale = "it")
@Composable
fun NyanimeLegacyHomeScreenshot() = HomePreview(modern = false)

@Composable
private fun HomePreview(modern: Boolean) {
    PreviewImages()
    TachiyomiPreviewTheme(appTheme = if (modern) AppTheme.NYANIME else AppTheme.DEFAULT, modernUi = modern) {
        Surface {
            Column(Modifier.fillMaxSize()) {
                DiscoveryHomeHeader(null, {}, null, {}, {}, listOf(SourceHomeGroup("cartoons", "Cartoni", emptyList())))
                LazyColumn {
                    if (modern) {
                        item {
                            SourceFeaturedSection(
                                SectionState(SourceHomePage(previewAnime, false), loading = false),
                                title = "In evidenza",
                                onRetry = {},
                                onOpen = {},
                            )
                        }
                    }
                    item { SectionHeader("Continua a guardare") {} }
                    item {
                        LocalAnimeRow(
                            SectionState(
                                data = previewAnime.map {
                                    LocalHomeItem(
                                        it,
                                        Episode.create().copy(
                                            id = it.id,
                                            name = "Episodio 4 · Il viaggio continua",
                                            lastSecondSeen = 540_000,
                                            totalSeconds = 1_440_000,
                                        ),
                                    )
                                },
                                loading = false,
                            ),
                            onOpen = {},
                            onHide = {},
                            resume = true,
                            onPlay = {},
                        )
                    }
                    if (!modern) {
                        item {
                            SectionHeader("In evidenza") {}
                            SourceFeaturedCarousel(previewAnime) {}
                        }
                    }
                    item { SectionHeader("Novità da scoprire") {} }
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(previewAnime) { SourceHomePosterCard(it, "Italiano · 1080p", {}) }
                        }
                    }
                }
            }
        }
    }
}

@PreviewTest
@Preview(name = "AnimeDetails", widthDp = 393, heightDp = 852, locale = "it")
@Composable
fun NyanimeDetailsScreenshot() {
    PreviewImages()
    TachiyomiPreviewTheme {
        Surface {
            Column {
                AnimeInfoBox(
                    isTabletUi = false,
                    appBarPadding = 56.dp,
                    anime = previewAnime.first(),
                    sourceName = "Fonte installata",
                    nextAiringAtMillis = null,
                    isStubSource = false,
                    onCoverClick = {},
                    doSearch = { _, _ -> },
                )
                AnimeWatchButton(true, {})
            }
        }
    }
}

@PreviewTest
@Preview(name = "Remote", widthDp = 393, heightDp = 852, locale = "it")
@Preview(name = "RemoteLandscape", widthDp = 852, heightDp = 393, locale = "it")
@Composable
fun NyanimeRemoteScreenshot() {
    PreviewImages()
    TachiyomiPreviewTheme {
        CastRemoteContent(
            CastState(
                device = CastDevice("preview", "TV del salotto", CastProtocol.DLNA),
                media = CastMedia(
                    1, 2, 3, titles.first(), "Episodio 4 · Il viaggio continua", "", "video/mp4", 0, 1440000,
                    cover = previewAnime.first().asAnimeCover(),
                    quality = "1080p",
                ),
                playback = CastPlayback(
                    487000,
                    1440000,
                    paused = false,
                    canSetVolume = true,
                    volume = 0.4f,
                    canSetBrightness = true,
                ),
                canNext = true,
                canPrevious = true,
            ),
            CastRemoteActions(),
        )
    }
}

@PreviewTest
@Preview(name = "LegacyManga", widthDp = 393, heightDp = 600, locale = "it")
@Composable
fun LegacyMangaScreenshot() {
    PreviewImages()
    TachiyomiPreviewTheme(appTheme = AppTheme.DEFAULT) {
        Surface {
            LazyRow(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(previewAnime) {
                    Column(Modifier.width(128.dp)) {
                        EntryComfortableGridItem(
                            title = it.title,
                            coverData = it.asAnimeCover(),
                            onClick = {},
                            onLongClick = {},
                        )
                    }
                }
            }
        }
    }
}

@PreviewTest
@Preview(name = "NyanimeIcon", widthDp = 393, heightDp = 320, locale = "it")
@Composable
fun NyanimeIconScreenshot() {
    TachiyomiPreviewTheme {
        androidx.compose.foundation.layout.Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        ) {
            eu.kanade.presentation.theme.NyanimeWordmark()
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                listOf(108, 72, 48).forEach { size ->
                    Surface(
                        shape = androidx.compose.foundation.shape.RoundedCornerShape((size * 0.24f).dp),
                        color = androidx.compose.ui.graphics.Color(0xFF101010),
                    ) {
                        androidx.compose.foundation.Image(
                            androidx.compose.ui.res.painterResource(
                                eu.kanade.tachiyomi.R.drawable.ic_launcher_foreground,
                            ),
                            "Nyanime",
                            Modifier.width(size.dp),
                        )
                    }
                }
            }
            Surface(
                shape = androidx.compose.foundation.shape.CircleShape,
                color = androidx.compose.ui.graphics.Color(0xFFE6DFFF),
            ) {
                androidx.compose.material3.Icon(
                    androidx.compose.ui.res.painterResource(eu.kanade.tachiyomi.R.drawable.ic_ani_monochrome_launcher),
                    "Icona a tema",
                    Modifier.width(72.dp),
                    tint = androidx.compose.ui.graphics.Color(0xFF312C42),
                )
            }
        }
    }
}
