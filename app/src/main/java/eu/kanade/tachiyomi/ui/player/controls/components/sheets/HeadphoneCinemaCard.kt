package eu.kanade.tachiyomi.ui.player.controls.components.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.player.CinemaStatus
import eu.kanade.tachiyomi.ui.player.HeadphoneCinemaState

@Composable
fun HeadphoneCinemaCard(state: HeadphoneCinemaState, onSelect: (Boolean) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    Icons.Outlined.Headphones,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
                Text(stringResource(R.string.cinema_title), style = MaterialTheme.typography.titleMedium)
            }
            Text(
                stringResource(
                    when (state.status) {
                        CinemaStatus.Original -> R.string.cinema_description
                        CinemaStatus.Preparing -> R.string.cinema_preparing
                        CinemaStatus.Active -> R.string.cinema_active
                        CinemaStatus.ConnectHeadphones -> R.string.cinema_connect
                        CinemaStatus.UnsupportedTrack -> R.string.cinema_unsupported
                        CinemaStatus.ChannelOverride -> R.string.cinema_channels
                        CinemaStatus.CustomFilters -> R.string.cinema_custom_filters
                        CinemaStatus.Unavailable -> R.string.cinema_unavailable
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !state.enabled,
                    onClick = { onSelect(false) },
                    label = { Text(stringResource(R.string.cinema_original)) },
                    modifier = Modifier.weight(1f),
                )
                FilterChip(
                    selected = state.enabled,
                    onClick = { onSelect(true) },
                    label = { Text(stringResource(R.string.cinema_mode)) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
