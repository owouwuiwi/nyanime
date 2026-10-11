/*
 * Copyright 2024 Abdallah Mehiz
 * https://github.com/abdallahmehiz/mpvKt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.kanade.tachiyomi.ui.player.controls

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.player.controls.components.AutoPlaySwitch
import eu.kanade.tachiyomi.ui.player.controls.components.ControlsButton
import eu.kanade.tachiyomi.ui.player.controls.components.sheets.formatSleepTimerRemaining
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.theme.active

@Composable
fun TopRightPlayerControls(
    onCastClick: () -> Unit,
    // auto-play
    autoPlayEnabled: Boolean,
    onToggleAutoPlay: (Boolean) -> Unit,

    // subtitles
    onSubtitlesClick: () -> Unit,
    onSubtitlesLongClick: () -> Unit,

    // audio
    onAudioClick: () -> Unit,
    onAudioLongClick: () -> Unit,

    // video
    onQualityClick: () -> Unit,
    isEpisodeOnline: Boolean?,

    // Anime4K quick presets
    isAnime4KSmartEnabled: Boolean,
    anime4KSmartLabel: String,
    onToggleAnime4KSmart: () -> Unit,

    sleepTimerRemaining: Int,
    onSleepTimerClick: () -> Unit,

    // more
    onMoreClick: () -> Unit,
    onMoreLongClick: () -> Unit,

    modifier: Modifier = Modifier,
    showAudioShortcut: Boolean = false,
    showQualityShortcut: Boolean = false,
    sleepTimerAtEpisodeEnd: Boolean = false,
    isUltraVideo: Boolean = false,
    watchRoom: eu.kanade.tachiyomi.data.watch.WatchRoomState = eu.kanade.tachiyomi.data.watch.WatchRoomState(),
    onWatchTogetherClick: () -> Unit = {},
) {
    Column(modifier, horizontalAlignment = Alignment.End) {
        eu.kanade.tachiyomi.ui.watch.WatchRoomChip(watchRoom, onWatchTogetherClick)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Absolute.SpaceEvenly,
        ) {
            AutoPlaySwitch(
                isChecked = autoPlayEnabled,
                onToggleAutoPlay = onToggleAutoPlay,
                modifier = Modifier
                    .padding(vertical = MaterialTheme.padding.medium, horizontal = MaterialTheme.padding.mediumSmall)
                    .size(width = 48.dp, height = 24.dp),
            )
            ControlsButton(
                icon = Icons.Default.Cast,
                title = "Trasmetti alla TV",
                onClick = onCastClick,
                horizontalSpacing = MaterialTheme.padding.mediumSmall,
            )
            ControlsButton(
                icon = Icons.Default.Subtitles,
                onClick = onSubtitlesClick,
                onLongClick = onSubtitlesLongClick,
                horizontalSpacing = MaterialTheme.padding.mediumSmall,
            )
            if (showAudioShortcut) {
                ControlsButton(
                    icon = Icons.Default.Audiotrack,
                    title = "Tracce audio",
                    onClick = onAudioClick,
                    onLongClick = onAudioLongClick,
                    horizontalSpacing = MaterialTheme.padding.mediumSmall,
                )
            }
            if (showQualityShortcut && isEpisodeOnline == true) {
                ControlsButton(
                    icon = Icons.Default.HighQuality,
                    title = "Qualità video",
                    onClick = onQualityClick,
                    onLongClick = onQualityClick,
                    horizontalSpacing = MaterialTheme.padding.mediumSmall,
                )
            }
            if (isUltraVideo) {
                Text(
                    "ULTRA",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = MaterialTheme.padding.mediumSmall),
                )
            } else if (!watchRoom.active) {
                ControlsButton(
                    text = anime4KSmartLabel,
                    onClick = onToggleAnime4KSmart,
                    color = if (isAnime4KSmartEnabled) MaterialTheme.colorScheme.active else Color.White,
                    horizontalSpacing = MaterialTheme.padding.mediumSmall,
                )
            }
            ControlsButton(
                icon = Icons.Default.MoreVert,
                title = "Altro",
                onClick = onMoreClick,
                onLongClick = onMoreLongClick,
                horizontalSpacing = MaterialTheme.padding.mediumSmall,
            )
        }

        if (sleepTimerRemaining > 0 || sleepTimerAtEpisodeEnd) {
            AssistChip(
                onClick = onSleepTimerClick,
                colors = AssistChipDefaults.assistChipColors(containerColor = Color.Black.copy(alpha = 0.65f)),
                label = {
                    Text(
                        if (sleepTimerAtEpisodeEnd) {
                            stringResource(AYMR.strings.timer_end_episode_short)
                        } else {
                            stringResource(AYMR.strings.timer_remaining, formatSleepTimerRemaining(sleepTimerRemaining))
                        },
                        color = Color.White,
                    )
                },
                leadingIcon = { Icon(Icons.Outlined.Timer, null, tint = Color.White) },
                modifier = Modifier.padding(end = 8.dp),
            )
        }
    }
}
