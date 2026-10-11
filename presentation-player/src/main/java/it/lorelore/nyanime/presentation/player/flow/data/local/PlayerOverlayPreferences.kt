// SPDX-License-Identifier: GPL-3.0-only
// Derived from FLOW 14afdf9e9426fb8b67e5fcbc76bf4e81af492d16.
// Modified for Nyanime: UI-only, host-owned playback, no service/runtime access.
// Original source identities and changes: presentation-player/third-party/flow-ui/PROVENANCE.json.

package it.lorelore.nyanime.presentation.player.flow.data.local
data class PlayerOverlayPreferences(
    val castEnabled: Boolean = true,
    val captionsEnabled: Boolean = false,
    val pipEnabled: Boolean = false,
    val autoplayEnabled: Boolean = false,
    val sleepTimerEnabled: Boolean = true,
    val speedIndicatorEnabled: Boolean = false,
    val commentsEnabled: Boolean = false,
    val sponsorBlockEnabled: Boolean = false,
    val showControlsWhileLoading: Boolean = false,
    val fullscreenSeekbarHorizontalPaddingDp: Int = 48,
    val portraitSeekbarHorizontalPaddingDp: Int = 0,
    val frameStepButtonsEnabled: Boolean = false,
    val sponsorCategoryColors: Map<String, Int> = emptyMap(),
)
enum class DoubleTapSeekZone(val sideFraction: Float) { NORMAL(1f / 3f), NARROW(1f / 4f) }
enum class GestureOverlayStyle { CIRCULAR, VERTICAL, HORIZONTAL, MINIMAL }
