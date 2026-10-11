package eu.kanade.presentation.motion

import android.graphics.BitmapFactory
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.discovery.CatalogDetailsContent
import eu.kanade.presentation.discovery.CinematicHero
import eu.kanade.presentation.discovery.PreviewImages
import eu.kanade.presentation.entries.anime.components.AnimeInfoBox
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import tachiyomi.domain.discovery.CatalogAnime
import tachiyomi.domain.discovery.CatalogId
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.presentation.core.components.TwoPanelBox
import tachiyomi.presentation.core.components.material.Scaffold

@PreviewTest
@Preview(name = "PosterOrigin", widthDp = 393, heightDp = 760, locale = "it")
@Preview(name = "PosterOriginLargeText", widthDp = 320, heightDp = 760, fontScale = 1.4f, locale = "it")
@Composable
fun PosterOriginScreenshot() = PosterMotionPreview("home")

@PreviewTest
@Preview(name = "WarmDetail", widthDp = 393, heightDp = 760, locale = "it")
@Preview(name = "WarmDetailLargeText", widthDp = 320, heightDp = 760, fontScale = 1.4f, locale = "it")
@Preview(name = "WarmDetailTablet", widthDp = 1000, heightDp = 700, locale = "it")
@Composable
fun PosterDetailScreenshot() = PosterMotionPreview("detail")

@PreviewTest
@Preview(name = "ReadyDetail", widthDp = 393, heightDp = 760, locale = "it")
@Preview(name = "ReadyDetailLargeText", widthDp = 320, heightDp = 760, fontScale = 1.4f, locale = "it")
@Preview(name = "ReadyDetailTablet", widthDp = 1000, heightDp = 700, locale = "it")
@Composable
fun PosterReadyDetailScreenshot() = PosterMotionPreview("detail", ready = true)

@PreviewTest
@Preview(name = "WarmCatalogDetail", widthDp = 393, heightDp = 760, locale = "it")
@Composable
fun PosterCatalogScreenshot() = PosterMotionPreview("detail", catalog = true)

@PreviewTest
@Preview(name = "ReadyCatalogDetail", widthDp = 393, heightDp = 760, locale = "it")
@Composable
fun PosterReadyCatalogScreenshot() = PosterMotionPreview("detail", catalog = true, ready = true)

@PreviewTest
@Preview(name = "HeroShortTitle", widthDp = 393, heightDp = 760, locale = "it")
@Preview(name = "HeroShortTitleLargeText", widthDp = 320, heightDp = 760, fontScale = 1.4f, locale = "it")
@Composable
fun PosterHeroShortTitleScreenshot() = PosterMotionPreview("home", hero = true, shortTitle = true)

@PreviewTest
@Preview(name = "HeroLongTitle", widthDp = 393, heightDp = 760, locale = "it")
@Preview(name = "HeroLongTitleLargeText", widthDp = 320, heightDp = 760, fontScale = 1.4f, locale = "it")
@Composable
fun PosterHeroLongTitleScreenshot() = PosterMotionPreview("home", hero = true)

/** Static transition endpoints; the host renderer does not advance the animation clock. */
@Composable
private fun PosterMotionPreview(
    initial: String,
    catalog: Boolean = false,
    ready: Boolean = false,
    hero: Boolean = false,
    shortTitle: Boolean = false,
) {
    PreviewImages()
    val title = if (shortTitle) {
        "Oltre il viaggio"
    } else {
        "Oltre la fine del viaggio · Il ritorno nella città delle stelle"
    }
    val artwork = remember {
        PosterSource::class.java.classLoader?.getResourceAsStream("nyanime-preview/poster-0.jpg")
            ?.use { BitmapFactory.decodeStream(it) }
            ?.let { BitmapPainter(it.asImageBitmap()) }
            ?: ColorPainter(Color(0xFF355B70))
    }
    val state = remember {
        PosterNavigationState<Painter>().apply {
            connect("home", "detail", "selected-poster", title, artwork)
        }
    }
    val transition = updateTransition(initial, label = "poster_preview")
    TachiyomiPreviewTheme(appTheme = AppTheme.NYANIME) {
        Surface {
            PosterNavigationTransition(
                navigation = transition,
                routeKey = { it },
                retainedRoutes = setOf("home", "detail"),
                enabled = true,
                transitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
                state = state,
                modifier = Modifier.fillMaxSize(),
            ) { screen ->
                if (screen == "home" && hero) {
                    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        val source = remember { PosterSource("selected-poster") }
                        CinematicHero(title, "In evidenza", "Serie · 24 episodi", null, "Apri episodi", {
                        }, poster = source) {
                            Image(
                                artwork,
                                null,
                                Modifier.matchParentSize().posterSource(source),
                                contentScale = ContentScale.Crop,
                            )
                        }
                    }
                } else if (screen == "home") {
                    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp)) {
                        Text("NYANIME", color = Color(0xFFE50914), style = MaterialTheme.typography.headlineLarge)
                        Spacer(Modifier.height(40.dp))
                        Text("La tua lista", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(20.dp))
                        val source = remember { PosterSource("selected-poster") }
                        Image(
                            artwork,
                            null,
                            Modifier.size(152.dp, 228.dp).posterSource(source),
                            contentScale = ContentScale.Crop,
                        )
                    }
                } else if (catalog) {
                    if (ready) {
                        Column {
                            CatalogDetailsContent(CatalogAnime(CatalogId(value = 1), title, cover = "preview://0")) {}
                        }
                    } else {
                        PosterLoadingBody(catalog = true)
                    }
                } else if (ready) {
                    ReadyAnimeHeader(title)
                } else {
                    PosterAnimeLoadingScreen(
                        tablet =
                        androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 800,
                    ) {}
                }
            }
        }
    }
}

/** The production header inside the same scaffold and panels as the actual details screen. */
@Composable
private fun ReadyAnimeHeader(title: String) {
    val tablet = LocalConfiguration.current.screenWidthDp >= 800
    val anime = remember(title) {
        Anime.create().copy(
            id = 1,
            source = 1,
            url = "/series/preview-0",
            title = title,
            thumbnailUrl = "preview://0",
            initialized = true,
        )
    }
    Scaffold(
        topBar = { AppBar(titleContent = {}, navigateUp = {}, backgroundColor = Color.Transparent) },
    ) { padding ->
        val direction = LocalLayoutDirection.current
        Box(
            Modifier.padding(
                start = padding.calculateStartPadding(direction),
                end = padding.calculateEndPadding(direction),
            ),
        ) {
            val header: @Composable () -> Unit = {
                AnimeInfoBox(
                    isTabletUi = tablet,
                    appBarPadding = padding.calculateTopPadding(),
                    anime = anime,
                    sourceName = "Fonte installata",
                    nextAiringAtMillis = null,
                    isStubSource = false,
                    onCoverClick = {},
                    doSearch = { _, _ -> },
                )
            }
            if (tablet) TwoPanelBox(startContent = { header() }, endContent = {}) else header()
        }
    }
}
