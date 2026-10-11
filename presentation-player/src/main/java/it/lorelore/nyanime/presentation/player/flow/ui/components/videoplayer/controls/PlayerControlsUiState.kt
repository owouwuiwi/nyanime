// SPDX-License-Identifier: GPL-3.0-only
// Derived from FLOW 14afdf9e9426fb8b67e5fcbc76bf4e81af492d16.
// Modified for Nyanime: UI-only, host-owned playback, no service/runtime access.
// Original source identities and changes: presentation-player/third-party/flow-ui/PROVENANCE.json.

package it.lorelore.nyanime.presentation.player.flow.ui.components.videoplayer.controls

import androidx.compose.runtime.Immutable
import it.lorelore.nyanime.presentation.player.flow.data.model.PlayerChapter

/**
 * Everything the on-video controls render that is not written per frame.
 *
 * The playhead, the buffered fraction and the gesture levels stay out of here and reach the overlay
 * as providers, so a position tick cannot invalidate this value and recompose the whole tree.
 */
@Immutable
internal data class PlayerControlsUiState(
    val isVisible: Boolean = false,
    val isPlaying: Boolean = false,
    val hasEnded: Boolean = false,
    val isBuffering: Boolean = false,
    val duration: Long = 0L,
    val qualityLabel: String? = null,
    val videoTitle: String? = null,
    val channelName: String? = null,
    val playbackSpeed: Float = 1.0f,
    val resizeMode: Int = 0,
    val isFullscreen: Boolean = false,
    val isPortraitFullscreen: Boolean = false,
    val isPipSupported: Boolean = false,
    val chapters: List<PlayerChapter> = emptyList(),
    val isSubtitlesEnabled: Boolean = false,
    val autoplayEnabled: Boolean = true,
    val isLooping: Boolean = false,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
    val hasQueue: Boolean = false,
    val sbSubmitEnabled: Boolean = false,
    /** SponsorBlock is on and this video has segments to act on. */
    val isSponsorBlockAvailable: Boolean = false,
    val isSponsorBlockOffForVideo: Boolean = false,
    val isCasting: Boolean = false,
    val isLive: Boolean = false,
    val isLiveChatAvailable: Boolean = false,
    val isCommentsAvailable: Boolean = false,
    val isCommentsPanelOpen: Boolean = false,
    val isSleepTimerActive: Boolean = false,
    val showRemainingTime: Boolean = false,
    val isTouchLocked: Boolean = false,
    val lockModeEnabled: Boolean = false,
    val lockOverlayRevealSignal: Int = 0,
    /** A gesture read-out — the speed badge, the zoom scale — currently owns the picture. */
    val isGestureReadoutActive: Boolean = false,
)
