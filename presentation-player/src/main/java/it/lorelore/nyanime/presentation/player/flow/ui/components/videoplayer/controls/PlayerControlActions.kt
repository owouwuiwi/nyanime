// SPDX-License-Identifier: GPL-3.0-only
// Derived from FLOW 14afdf9e9426fb8b67e5fcbc76bf4e81af492d16.
// Modified for Nyanime: UI-only, host-owned playback, no service/runtime access.
// Original source identities and changes: presentation-player/third-party/flow-ui/PROVENANCE.json.

package it.lorelore.nyanime.presentation.player.flow.ui.components.videoplayer.controls

import androidx.compose.runtime.Immutable

@Immutable
internal data class PlayerControlActions(
    val onPlayPause: () -> Unit = {},
    val onPrevious: () -> Unit = {},
    val onNext: () -> Unit = {},
    val onStepFrame: (forward: Boolean) -> Unit = {},
    val onBack: () -> Unit = {},
    val onSettingsClick: () -> Unit = {},
    val onQualityClick: () -> Unit = {},
    val onSpeedClick: () -> Unit = {},
    val onFullscreenClick: () -> Unit = {},
    val onResizeClick: () -> Unit = {},
    val onPipClick: () -> Unit = {},
    val onChapterClick: () -> Unit = {},
    val onQueueClick: () -> Unit = {},
    val onDescriptionClick: () -> Unit = {},
    val onSubtitleClick: () -> Unit = {},
    val onSubtitleLongClick: () -> Unit = {},
    val onAutoplayToggle: (Boolean) -> Unit = {},
    val onSbSubmitClick: () -> Unit = {},
    val onSponsorBlockToggle: (off: Boolean) -> Unit = {},
    val onCastClick: () -> Unit = {},
    val onLiveClick: () -> Unit = {},
    val onLiveChatClick: () -> Unit = {},
    val onCommentsClick: () -> Unit = {},
    val onSleepTimerClick: () -> Unit = {},
    val onToggleRemainingTime: () -> Unit = {},
    val onTouchLockToggle: () -> Unit = {},
    val onSeek: (Long) -> Unit = {},
    val onScrubbingChange: (Boolean) -> Unit = {},
)
