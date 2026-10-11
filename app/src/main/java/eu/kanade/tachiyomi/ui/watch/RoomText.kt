package eu.kanade.tachiyomi.ui.watch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import eu.kanade.tachiyomi.data.watch.RoomText

@Composable
fun rememberRoomText(): RoomText {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(context, configuration) { RoomText.from(context) }
}
