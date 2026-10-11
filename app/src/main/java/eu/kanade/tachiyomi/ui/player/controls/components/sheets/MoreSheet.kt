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

package eu.kanade.tachiyomi.ui.player.controls.components.sheets

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.player.components.PlayerSheet
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.player.Anime4K
import eu.kanade.tachiyomi.ui.player.Anime4KContentState
import eu.kanade.tachiyomi.ui.player.Anime4KMode
import eu.kanade.tachiyomi.ui.player.Anime4KProfile
import eu.kanade.tachiyomi.ui.player.Decoder
import eu.kanade.tachiyomi.ui.player.Sheets
import eu.kanade.tachiyomi.ui.player.applyAudioChannels
import eu.kanade.tachiyomi.ui.player.execute
import eu.kanade.tachiyomi.ui.player.executeLongPress
import eu.kanade.tachiyomi.ui.player.session.LocalPlayerSession
import eu.kanade.tachiyomi.ui.player.settings.AdvancedPlayerPreferences
import eu.kanade.tachiyomi.ui.player.settings.AudioChannels
import eu.kanade.tachiyomi.ui.player.settings.AudioPreferences
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import `is`.xyz.mpv.MPVLib
import it.lorelore.nyanime.presentation.player.VideoSettingsAction
import it.lorelore.nyanime.presentation.player.VideoSettingsGroup
import kotlinx.collections.immutable.ImmutableList
import tachiyomi.domain.custombuttons.model.CustomButton
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import androidx.compose.ui.res.stringResource as androidStringResource
import tachiyomi.presentation.core.util.collectAsState as collectPreferenceAsState

@Composable
fun MoreSheet(
    anime: Anime?,
    onOpenAudio: () -> Unit,
    onOpenQuality: () -> Unit,
    onOpenScreenshot: () -> Unit,
    qualityAvailable: Boolean,
    selectedDecoder: Decoder,
    onSelectDecoder: (Decoder) -> Unit,
    remainingTime: Int,
    timerAtEpisodeEnd: Boolean,
    onOpenSleepTimer: () -> Unit,
    onOpenWatchTogether: () -> Unit,
    onShareContent: (() -> Unit)?,
    onDismissRequest: () -> Unit,
    onEnterFiltersPanel: () -> Unit,
    customButtons: ImmutableList<CustomButton>,
    onSelectAnime4KCustom: (Anime4KMode) -> Unit,
    modifier: Modifier = Modifier,
    anime4kAvailable: Boolean = true,
) {
    val session = LocalPlayerSession.current
    val advancedPreferences = remember { Injekt.get<AdvancedPlayerPreferences>() }
    val audioPreferences = remember { Injekt.get<AudioPreferences>() }
    val playerPreferences = remember { Injekt.get<PlayerPreferences>() }
    val showAudioShortcut by playerPreferences.showAudioShortcut().collectPreferenceAsState()
    val showQualityShortcut by playerPreferences.showQualityShortcut().collectPreferenceAsState()
    val globalAutoplay by playerPreferences.autoplayEnabled().collectPreferenceAsState()
    val globalAutoSkip by playerPreferences.autoSkipIntro().collectPreferenceAsState()
    val autoSkipOverrides by playerPreferences.autoSkipIntroOverrides().collectPreferenceAsState()
    val animeAutoSkip = playerPreferences.autoSkipIntroOverride(anime, autoSkipOverrides)
    var customizeControls by rememberSaveable { mutableStateOf(false) }
    val statisticsPage by advancedPreferences.playerStatisticsPage().collectPreferenceAsState()
    val anime4kSelection by advancedPreferences.anime4kActiveSelection().collectAsState()
    val anime4kDiagnosticsEnabled by advancedPreferences.anime4kDiagnosticsEnabled().collectPreferenceAsState()
    val anime4kDiagnostics by advancedPreferences.anime4kDiagnostics().collectAsState()
    val imageAnalysisEnabled by advancedPreferences.anime4kContentAnalysisEnabled().collectPreferenceAsState()

    PlayerSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        title = androidStringResource(R.string.playback_settings),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MaterialTheme.padding.medium)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        ) {
            VideoSettingsGroup(
                androidStringResource(R.string.playback_video),
                buildList {
                    if (qualityAvailable) {
                        add(
                            VideoSettingsAction(
                                androidStringResource(R.string.player_video_quality),
                                Icons.Default.HighQuality,
                                onClick = onOpenQuality,
                            ),
                        )
                    }
                    add(
                        VideoSettingsAction(
                            androidStringResource(R.string.playback_speed),
                            Icons.Default.Speed,
                            value = "${session.viewModel.playbackSpeed.value}×",
                            onClick = { session.viewModel.showSheet(Sheets.PlaybackSpeed) },
                        ),
                    )
                    add(
                        VideoSettingsAction(
                            androidStringResource(R.string.playback_audio),
                            Icons.Default.Audiotrack,
                            onClick = onOpenAudio,
                        ),
                    )
                    add(
                        VideoSettingsAction(
                            androidStringResource(R.string.playback_subtitles),
                            Icons.Default.Subtitles,
                            onClick = { session.viewModel.showSheet(Sheets.SubtitleTracks) },
                        ),
                    )
                },
            )
            VideoSettingsGroup(
                androidStringResource(R.string.playback_advanced),
                buildList {
                    add(
                        VideoSettingsAction(
                            androidStringResource(R.string.playback_episodes),
                            Icons.Default.PlaylistPlay,
                            onClick = {
                                session.viewModel.showSheet(Sheets.None)
                                session.viewModel.showEpisodeListDialog()
                            },
                        ),
                    )
                    add(
                        VideoSettingsAction(
                            androidStringResource(R.string.playback_rotation),
                            Icons.Default.ScreenRotation,
                            onClick = {
                                session.viewModel.cycleScreenRotations()
                                onDismissRequest()
                            },
                        ),
                    )
                    add(
                        VideoSettingsAction(
                            androidStringResource(R.string.playback_lock),
                            Icons.Default.Lock,
                            onClick = {
                                onDismissRequest()
                                session.viewModel.lockControls()
                            },
                        ),
                    )
                    if (session.isPipSupportedAndEnabled) {
                        add(
                            VideoSettingsAction(
                                androidStringResource(R.string.playback_pip),
                                Icons.Default.PictureInPicture,
                                onClick = {
                                    onDismissRequest()
                                    session.enterPictureInPictureMode(session.createPipParams())
                                },
                            ),
                        )
                    }
                    add(
                        VideoSettingsAction(
                            androidStringResource(R.string.player_frame),
                            Icons.Default.PhotoCamera,
                            onClick = onOpenScreenshot,
                        ),
                    )
                    add(
                        VideoSettingsAction(
                            stringResource(AYMR.strings.player_sheets_filters_title),
                            Icons.Default.Tune,
                            onClick = onEnterFiltersPanel,
                        ),
                    )
                    onShareContent?.let { share ->
                        add(
                            VideoSettingsAction(
                                androidStringResource(R.string.playback_share),
                                Icons.Outlined.Share,
                                onClick = share,
                            ),
                        )
                    }
                    add(
                        VideoSettingsAction(
                            androidStringResource(R.string.playback_close),
                            Icons.Default.Close,
                            onClick = {
                                onDismissRequest()
                                session.requestClose()
                            },
                        ),
                    )
                },
            )
            ShortcutPreference(
                androidStringResource(R.string.playback_autoplay),
                globalAutoplay,
                session.viewModel::setAutoPlay,
            )
            eu.kanade.presentation.episodeguide.EpisodeGuidePlayerPreferences(anime)
            it.lorelore.nyanime.attention.AttentionPlayerEntry()
            SleepTimerEntry(remainingTime, onOpenSleepTimer, timerAtEpisodeEnd)
            eu.kanade.presentation.privacy.PlayerPrivacyEntry()
            Text(androidStringResource(R.string.player_intro_skip), style = MaterialTheme.typography.titleMedium)
            ShortcutPreference(
                androidStringResource(R.string.player_auto_skip_default),
                globalAutoSkip,
                playerPreferences.autoSkipIntro()::set,
            )
            if (anime != null) {
                Text(androidStringResource(R.string.player_for_this_anime), style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = animeAutoSkip == null,
                        onClick = { playerPreferences.setAutoSkipIntroOverride(anime, null) },
                        label = { Text(androidStringResource(R.string.player_use_global)) },
                    )
                    FilterChip(
                        selected = animeAutoSkip == true,
                        onClick = { playerPreferences.setAutoSkipIntroOverride(anime, true) },
                        label = { Text(androidStringResource(R.string.player_skip)) },
                    )
                    FilterChip(
                        selected = animeAutoSkip == false,
                        onClick = { playerPreferences.setAutoSkipIntroOverride(anime, false) },
                        label = { Text(androidStringResource(R.string.player_do_not_skip)) },
                    )
                }
            }
            TextButton(onClick = { customizeControls = !customizeControls }) {
                Text(androidStringResource(R.string.player_customize_controls))
                Icon(if (customizeControls) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            }
            if (customizeControls) {
                ShortcutPreference(
                    androidStringResource(R.string.player_show_audio),
                    showAudioShortcut,
                    playerPreferences.showAudioShortcut()::set,
                )
                ShortcutPreference(
                    androidStringResource(R.string.player_show_quality),
                    showQualityShortcut,
                    playerPreferences.showQualityShortcut()::set,
                )
            }
            TextButton(onClick = onOpenWatchTogether, modifier = Modifier.fillMaxWidth()) {
                Text(
                    androidStringResource(R.string.player_watch_together),
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            Text(stringResource(AYMR.strings.player_hwdec_mode))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            ) {
                items(Decoder.entries.minus(Decoder.Auto)) { decoder ->
                    FilterChip(
                        selected = decoder == selectedDecoder,
                        onClick = { onSelectDecoder(decoder) },
                        label = { Text(text = decoder.title) },
                    )
                }
            }

            Text(stringResource(AYMR.strings.player_sheets_stats_page_title))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            ) {
                items(6) { page ->
                    FilterChip(
                        label = {
                            Text(
                                stringResource(
                                    if (page ==
                                        0
                                    ) {
                                        AYMR.strings.player_sheets_tracks_off
                                    } else {
                                        AYMR.strings.player_sheets_stats_page_chip
                                    },
                                    page,
                                ),
                            )
                        },
                        onClick = {
                            if ((page == 0) xor (statisticsPage == 0)) {
                                MPVLib.command(arrayOf("script-binding", "stats/display-stats-toggle"))
                            }
                            if (page != 0) {
                                MPVLib.command(arrayOf("script-binding", "stats/display-page-$page"))
                            }
                            advancedPreferences.playerStatisticsPage().set(page)
                        },
                        selected = statisticsPage == page,
                    )
                }
            }

            if (anime4kAvailable) {
                Text(stringResource(AYMR.strings.pref_anime4k))
                if (anime4kSelection.profile == Anime4KProfile.Smart) {
                    val status = when {
                        anime4kDiagnostics.contentState == Anime4KContentState.Sampling ->
                            androidStringResource(R.string.smart_status_analysing)
                        anime4kDiagnostics.probing -> androidStringResource(R.string.smart_status_recovering)
                        anime4kDiagnostics.suspended -> androidStringResource(R.string.smart_status_original)
                        anime4kSelection.mode == Anime4KMode.Off -> androidStringResource(
                            R.string.smart_status_preparing,
                        )
                        else -> androidStringResource(
                            R.string.smart_status_active,
                            Anime4K.smartButtonLabel(anime4kSelection.mode),
                        )
                    }
                    Text(
                        status,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        minLines = 2,
                    )
                    if (anime4kDiagnostics.contentState != Anime4KContentState.Disabled &&
                        anime4kDiagnostics.contentState != Anime4KContentState.Sampling
                    ) {
                        Text(
                            androidStringResource(
                                if (anime4kDiagnostics.contentState == Anime4KContentState.Applied) {
                                    R.string.smart_analysis_applied
                                } else {
                                    R.string.smart_analysis_fallback
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                ShortcutPreference(androidStringResource(R.string.smart_analysis_toggle), imageAnalysisEnabled) {
                    advancedPreferences.anime4kContentAnalysisEnabled().set(it)
                }
                Text(
                    androidStringResource(R.string.smart_analysis_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                ) {
                    Anime4KMode.entries.forEach { mode ->
                        FilterChip(
                            selected = if (mode == Anime4KMode.Off) {
                                anime4kSelection.profile == Anime4KProfile.Off
                            } else {
                                anime4kSelection.profile == Anime4KProfile.Custom && anime4kSelection.mode == mode
                            },
                            onClick = { onSelectAnime4KCustom(mode) },
                            label = { Text(text = stringResource(mode.titleRes)) },
                        )
                    }
                    FilterChip(
                        selected = anime4kDiagnosticsEnabled,
                        onClick = {
                            advancedPreferences.anime4kDiagnosticsEnabled().set(!anime4kDiagnosticsEnabled)
                        },
                        label = { Text(text = stringResource(AYMR.strings.pref_anime4k_debug_overlay)) },
                    )
                }
            }

            if (customButtons.isNotEmpty()) {
                Text(text = stringResource(AYMR.strings.player_sheets_custom_buttons_title))
                FlowRow(
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.mediumSmall),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                    maxItemsInEachRow = Int.MAX_VALUE,
                ) {
                    customButtons.forEach { button ->

                        val inputChipInteractionSource = remember { MutableInteractionSource() }

                        Box {
                            FilterChip(
                                onClick = {},
                                label = { Text(text = button.name) },
                                selected = false,
                                interactionSource = inputChipInteractionSource,
                            )
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .combinedClickable(
                                        onClick = { button.execute() },
                                        onLongClick = { button.executeLongPress() },
                                        interactionSource = inputChipInteractionSource,
                                        indication = null,
                                    ),
                            )
                        }
                    }
                }
            }
            Text(text = stringResource(AYMR.strings.pref_audio_channels))
            val audioChannels by audioPreferences.audioChannels().collectPreferenceAsState()
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            ) {
                items(AudioChannels.entries) {
                    FilterChip(
                        selected = audioChannels == it,
                        onClick = {
                            applyAudioChannels(it, previous = audioChannels)
                            audioPreferences.audioChannels().set(it)
                        },
                        label = { Text(text = stringResource(it.titleRes)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ShortcutPreference(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .toggleable(checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onCheckedChange = null)
    }
}
