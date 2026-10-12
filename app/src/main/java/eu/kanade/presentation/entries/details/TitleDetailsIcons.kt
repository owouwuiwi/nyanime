package eu.kanade.presentation.entries.details

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Consistent 24-unit outline geometry, matching the approved detail-screen mockup. */
internal object TitleDetailsIcons {
    private fun icon(name: String, vararg paths: String): ImageVector = ImageVector.Builder(
        name,
        24.dp,
        24.dp,
        24f,
        24f,
    ).apply {
        paths.forEach {
            addPath(
                pathData = PathParser().parsePathString(it).toNodes(),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()

    val Back = icon("Back", "M19 12H5M12 19l-7-7 7-7")
    val Close = icon("Close", "M6 6l12 12M6 18 18 6")
    val Play = icon("Play", "M8 5l11 7-11 7Z")
    val Book = icon("Book", "M12 5v16M12 5C8 2 4 3 2 4v15c4-2 7-1 10 2 3-3 6-4 10-2V4c-2-1-6-2-10 1Z")
    val Bookmark = icon("Bookmark", "M6 21V4a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v17l-6-4Z")
    val BookmarkCheck = icon("BookmarkCheck", "M6 21V4a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v17l-6-4Z", "m9 9 2 2 4-4")
    val Bell = icon("Bell", "M6 8a6 6 0 0 1 12 0c0 7 3 7 3 9H3c0-2 3-2 3-9M10 21h4")
    val Users =
        icon(
            "Users",
            "M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2M22 21v-2a4 4 0 0 0-3-3.9",
            "M13 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0ZM16 3a4 4 0 0 1 0 8",
        )
    val Share =
        icon(
            "Share",
            "M21 5a3 3 0 1 1-6 0a3 3 0 0 1 6 0ZM9 12a3 3 0 1 1-6 0a3 3 0 0 1 6 0Z",
            "M21 19a3 3 0 1 1-6 0a3 3 0 0 1 6 0ZM8.6 10.5l6.8-4M8.6 13.5l6.8 4",
        )
    val More = icon("More", "M5 12h.01M12 12h.01M19 12h.01")
    val Search = icon("Search", "M17 10a7 7 0 1 1-14 0 7 7 0 0 1 14 0ZM15 15l6 6")
    val Index = icon("Index", "M9 5h12M9 12h12M9 19h12M3 5h1M3 12h1M3 19h1")
    val Resume = icon("Resume", "M12 2v4M12 18v4M2 12h4M18 12h4M19 12a7 7 0 1 1-14 0a7 7 0 0 1 14 0Z")
    val Filter = icon("Filter", "M4 6h6M14 6h6M4 12h2M10 12h10M4 18h10M18 18h2M10 3v6M6 9v6M14 15v6")
    val Download = icon("Download", "M12 3v12M7 10l5 5 5-5M5 16v4a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-4")
    val Check = icon("Check", "m5 12 4 4L19 6")
    val Checks = icon("Checks", "m2 12 4 4L16 6m-4 9 1 1L23 6")
    val Square = icon("Square", "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2Z")
    val SquareCheck =
        icon("SquareCheck", "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2Z", "m7 12 3 3 7-7")
    val Next = icon("Next", "m9 6 6 6-6 6")
    val Clock = icon("Clock", "M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0ZM12 6v6l4 2")
    val Sparkles = icon("Sparkles", "m12 3 2.7 6.3L21 12l-6.3 2.7L12 21l-2.7-6.3L3 12l6.3-2.7ZM20 2v4M18 4h4")
    val Refresh = icon("Refresh", "M20 7a9 9 0 1 0 1 9M20 3v5h-5")
    val Globe = icon("Globe", "M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0ZM2 12h20M12 2c-5 5-5 15 0 20 5-5 5-15 0-20Z")
    val Info = icon("Info", "M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0ZM12 11v6M12 7h.01")
    val Trash = icon("Trash", "M3 6h18M9 6V3h6v3M5 6l1 15h12l1-15M10 10v7M14 10v7")
}
