package eu.kanade.tachiyomi.ui.watch

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.google.zxing.common.BitMatrix
import eu.kanade.presentation.discovery.ArtworkPlaceholder
import eu.kanade.presentation.theme.LocalNyanimeStyle
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.watch.WatchQr
import eu.kanade.tachiyomi.data.watch.WatchRoomState
import eu.kanade.tachiyomi.ui.player.controls.components.NextEpisodeCard
import eu.kanade.tachiyomi.ui.player.controls.components.PlayerSkipCue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun WatchQrDialog(link: String, onDismiss: () -> Unit) {
    val text = rememberRoomText()
    val matrix by produceState<BitMatrix?>(null, link) {
        value = withContext(Dispatchers.Default) { runCatching { WatchQr.encode(link) }.getOrNull() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text(R.string.room_qr_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(text(R.string.room_qr_hint))
                Canvas(
                    Modifier.fillMaxWidth().aspectRatio(1f).background(Color.White)
                        .semantics { contentDescription = text(R.string.room_qr_description) },
                ) {
                    matrix?.let { qr ->
                        val cell = size.width / qr.width
                        for (y in 0 until qr.height) {
                            for (x in 0 until qr.width) {
                                if (qr[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell, cell))
                            }
                        }
                    }
                }
                Text(text(R.string.room_qr_fallback), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(text(R.string.room_close_dialog)) } },
    )
}

@Composable
fun WatchRoomCues(
    room: WatchRoomState,
    onSkip: () -> Unit,
    onCancelSkip: () -> Unit,
    onNext: () -> Unit,
    onCancelNext: () -> Unit,
    modifier: Modifier = Modifier,
    reduceMotion: Boolean = false,
    artwork: @Composable () -> Unit = { ArtworkPlaceholder(Modifier.sizeIn(minWidth = 64.dp, minHeight = 88.dp)) },
) {
    val text = rememberRoomText()
    val controls = room.host || room.sharedControls
    Column(modifier.widthIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        room.skip?.let { skip ->
            if (LocalNyanimeStyle.current) {
                PlayerSkipCue(
                    label = if (controls) text(R.string.room_skip_all) else skip.label,
                    detail = room.skipSeconds?.let { text(R.string.room_skip_countdown, skip.label, it) } ?: skip.label,
                    onSkip = onSkip,
                    onCancel = onCancelSkip,
                    showActions = controls,
                )
            } else {
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            room.skipSeconds?.let { text(R.string.room_skip_countdown, skip.label, it) } ?: skip.label,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (controls) {
                            Button(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
                                Text(text(R.string.room_skip_all))
                            }
                            TextButton(onClick = onCancelSkip) { Text(text(R.string.room_cancel_skip)) }
                        }
                    }
                }
            }
        }
        room.next?.let { next ->
            NextEpisodeCard(
                seriesTitle = next.media.title,
                episodeTitle = next.media.episode,
                secondsRemaining = room.nextSeconds ?: 10,
                onPlayNow = onNext,
                onCancel = onCancelNext,
                statusText = if (room.nextSeconds == null) text(R.string.room_prepare_together) else null,
                showActions = controls,
                playEnabled = next.deadline != null,
                reduceMotion = reduceMotion,
                artwork = artwork,
            )
        }
    }
}
