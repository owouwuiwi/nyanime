package eu.kanade.presentation.reader

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.tachiyomi.data.reading.ReadingBookmark
import eu.kanade.tachiyomi.data.reading.ReadingPeer
import eu.kanade.tachiyomi.data.reading.ReadingPosition
import eu.kanade.tachiyomi.data.reading.ReadingRoomState
import eu.kanade.tachiyomi.data.reading.ReadingTools
import eu.kanade.tachiyomi.ui.reading.ReadingRoomContent

private val chapter =
    ReadingPosition(
        7,
        "/title",
        "/chapter",
        "Le cronache della biblioteca delle stelle",
        "Capitolo 12 · Una lettera arrivata da lontano",
        7,
        32,
    )
private val room = ReadingRoomState(
    active = true,
    host = true,
    localId = "me",
    connected = true,
    relayCount = 2,
    members = linkedMapOf(
        "me" to ReadingPeer("Lorenzo", chapter, true),
        "friend" to ReadingPeer("Alessandro", chapter.copy(page = 15), true),
    ),
)

@PreviewTest
@Preview(name = "ReadingRoom", widthDp = 393, heightDp = 1000, locale = "it", uiMode = 0x20)
@Preview(name = "ReadingRoomNarrow", widthDp = 280, heightDp = 980, fontScale = 1.4f, locale = "it", uiMode = 0x20)
@Preview(name = "ReadingRoomLandscape", widthDp = 760, heightDp = 360, locale = "it", uiMode = 0x20)
@Composable
fun ReadingRoomScreenshot() = ReadingPreview(
    room,
    ReadingTools(returnTo = ReadingBookmark(1, 2, chapter.copy(page = 3))),
)

@PreviewTest
@Preview(name = "ReadingRoomOffline", widthDp = 393, heightDp = 1000, locale = "it", uiMode = 0x20)
@Composable
fun ReadingRoomOfflineScreenshot() = ReadingPreview(
    room.copy(relayCount = 0, connected = false),
    ReadingTools(error = "Questa edizione ha un numero diverso di pagine. Il tuo punto resta invariato."),
)

@PreviewTest
@Preview(name = "ReadingRoomWaiting", widthDp = 393, heightDp = 1000, locale = "it", uiMode = 0x20)
@Composable
fun ReadingRoomWaitingScreenshot() = ReadingPreview(
    room.copy(members = mapOf("me" to ReadingPeer("Lorenzo"))),
    ReadingTools(),
)

@Composable
private fun ReadingPreview(state: ReadingRoomState, tools: ReadingTools) {
    TachiyomiPreviewTheme(appTheme = AppTheme.NYANIME, modernUi = false) {
        Surface {
            ReadingRoomContent(
                state, tools, chapter,
                onJump = {}, onReturn = {}, onChooseManga = {}, onDraw = {},
                onVisible = {}, onShare = {}, onQr = {}, onLeave = {}, onVideo = {},
            )
        }
    }
}
