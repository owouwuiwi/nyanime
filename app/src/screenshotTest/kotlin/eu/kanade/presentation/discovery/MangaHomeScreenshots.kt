package eu.kanade.presentation.discovery

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import eu.kanade.presentation.discovery.manga.MangaHomeContent
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.tachiyomi.data.discovery.MangaHomeChapter
import eu.kanade.tachiyomi.data.discovery.MangaHomeItem
import eu.kanade.tachiyomi.data.discovery.MangaHomePage
import eu.kanade.tachiyomi.data.discovery.MangaHomePresentation
import eu.kanade.tachiyomi.ui.discovery.manga.MangaHomeRowState
import eu.kanade.tachiyomi.ui.discovery.manga.MangaHomeState
import tachiyomi.domain.discovery.SourceHomeSection
import tachiyomi.domain.discovery.SourceHomeSource
import tachiyomi.domain.entries.manga.model.Manga

@PreviewTest
@Preview(name = "MangaHomePhone", widthDp = 393, heightDp = 1050, locale = "it")
@Preview(name = "MangaHomeNarrow", widthDp = 280, heightDp = 1050, fontScale = 1.5f, locale = "it")
@Preview(name = "MangaHomeLargeText", widthDp = 320, heightDp = 1050, fontScale = 1.5f, locale = "it")
@Preview(name = "MangaHomeTablet", widthDp = 800, heightDp = 1050, locale = "it")
@Composable
fun MangaHomeScreenshot() = MangaPreview()

@PreviewTest
@Preview(name = "MangaChapterUpdates", widthDp = 393, heightDp = 1050, locale = "it")
@Composable
fun MangaChapterUpdatesScreenshot() = MangaPreview(firstItem = 4)

@PreviewTest
@Preview(name = "MangaRanking", widthDp = 393, heightDp = 1000, locale = "it")
@Composable
fun MangaRankingScreenshot() = MangaPreview(firstItem = 9)

@PreviewTest
@Preview(name = "MangaHomeError", widthDp = 393, heightDp = 1000, locale = "it")
@Composable
fun MangaHomeErrorScreenshot() = MangaPreview(error = true)

private val mangaSections = listOf(
    SourceHomeSection("trending", "Capitoli di tendenza", emptyMap(), "chapters"),
    SourceHomeSection("updates", "Ultimi capitoli aggiunti", emptyMap(), "updates"),
    SourceHomeSection("monthly", "Manga del mese", emptyMap(), "ranking", moreSelections = emptyMap()),
    SourceHomeSection("new", "Ultime aggiunte", emptyMap(), "updates", moreSelections = emptyMap()),
)
private val mangaHome = SourceHomeSource(
    1,
    "v1",
    mangaSections,
    listOf(SourceHomeSection("category:adventure", "Avventura", emptyMap())),
    sourceName = "Fonte di esempio",
    title = "Manga",
    search = SourceHomeSection("search", "Archivio", emptyMap()),
)
private val mangaTitles = listOf("Un viaggio oltre le stelle", "La città delle ombre", "Il giardino dei ricordi")
private fun mangaItems(ranked: Boolean = false) = mangaTitles.mapIndexed { index, title ->
    MangaHomeItem(
        Manga.create().copy(
            id = index + 1L,
            source = 1,
            title = title,
            url = "/series/$index",
            thumbnailUrl = "preview://$index",
        ),
        MangaHomePresentation(
            id = "entry-$index",
            badges = listOf("Manga", "In corso"),
            rank = (index + 1).takeIf { ranked },
            details = if (ranked) listOf("Letto: 42.500 volte") else emptyList(),
            chapters = if (ranked) {
                emptyList()
            } else {
                listOf(
                    MangaHomeChapter("/chapter/18", "Capitolo 18", isNew = true),
                    MangaHomeChapter("/chapter/17", "Volume 03 · Capitolo 17", "10 settembre"),
                    MangaHomeChapter("/chapter/16", "Capitolo 16", "7 settembre"),
                )
            },
        ),
    )
}

@Composable
private fun MangaPreview(firstItem: Int = 0, error: Boolean = false) {
    PreviewImages()
    val state = MangaHomeState(
        initializing = false,
        homes = listOf(mangaHome),
        selected = mangaHome,
        rows = mangaSections.associate { section ->
            section.id to if (error) {
                MangaHomeRowState(loading = false, error = "La fonte non ha risposto in tempo. Riprova.")
            } else {
                val items = mangaItems(section.layout == "ranking").map { item ->
                    if (section.id == "trending") {
                        item.copy(presentation = item.presentation?.copy(chapters = item.presentation.chapters.take(1)))
                    } else if (section.id == "new") {
                        item.copy(
                            presentation = item.presentation?.copy(
                                chapters = emptyList(),
                                details = listOf("12 settembre 2026"),
                            ),
                        )
                    } else {
                        item
                    }
                }
                MangaHomeRowState(MangaHomePage(items, section.id == "updates"), 1, false)
            }
        },
    )
    TachiyomiPreviewTheme {
        Surface {
            MangaHomeContent(
                state = state,
                onRefresh = {},
                onSelectHome = {},
                onSelectAll = {},
                onPreferredSource = {},
                onManga = {},
                onChapter = { _, _ -> },
                onResume = {},
                onArchive = {},
                onExplore = {},
                onGenre = {},
                onMore = {},
                onRetry = {},
                onUpdates = {},
                onUpdate = {},
                listState = rememberLazyListState(firstItem),
            )
        }
    }
}
