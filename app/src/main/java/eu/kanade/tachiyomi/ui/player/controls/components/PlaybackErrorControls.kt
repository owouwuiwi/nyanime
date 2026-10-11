package eu.kanade.tachiyomi.ui.player.controls.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.player.PlaybackFailure
import tachiyomi.i18n.MR
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun PlaybackErrorControls(failure: PlaybackFailure, retry: () -> Unit, openSource: (() -> Unit)?) {
    Column(
        Modifier.widthIn(
            max = 420.dp,
        ).background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(16.dp)).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(
                when (failure) {
                    PlaybackFailure.Format -> AYMR.strings.player_error_format
                    PlaybackFailure.Network -> AYMR.strings.player_error_network
                    PlaybackFailure.SourcePage -> AYMR.strings.player_error_source_page
                    PlaybackFailure.Timeout -> AYMR.strings.player_error_timeout
                    PlaybackFailure.Unknown -> AYMR.strings.player_error_unknown
                },
            ),
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        Row {
            TextButton(onClick = retry) { Text(stringResource(MR.strings.action_retry)) }
            if (openSource != null) {
                TextButton(onClick = openSource) { Text(stringResource(AYMR.strings.player_open_source)) }
            }
        }
    }
}
