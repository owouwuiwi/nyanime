// SPDX-License-Identifier: GPL-3.0-only
// Derived from FLOW 14afdf9e9426fb8b67e5fcbc76bf4e81af492d16.
// Modified for Nyanime: UI-only, host-owned playback, no service/runtime access.
// Original source identities and changes: presentation-player/third-party/flow-ui/PROVENANCE.json.

package it.lorelore.nyanime.presentation.player.flow.ui.theme
import androidx.compose.ui.graphics.Color
val ArtworkScrim = Color.Black
val PlayerScrim = Color.Black
val PlayerScrimAffordance = PlayerScrim.copy(alpha = 0.4f)
val PlayerScrimContent = Color.White
val PlayerScrimContentSecondary = PlayerScrimContent.copy(alpha = 0.7f)
val PlayerScrimContentDisabled = PlayerScrimContent.copy(alpha = 0.3f)
val PlayerScrimPanel = PlayerScrim.copy(alpha = 0.56f)
val PlayerScrimEdgeGradient = PlayerScrim.copy(alpha = 0.72f)
val PlayerScrimGestureHud = PlayerScrim.copy(alpha = 0.54f)
val PlayerLiveIndicator = Color.Red
const val TIMELINE_SEGMENT_ALPHA = 0.7f
fun defaultTimelineColor(category: String): Color = Color(0xFF81C784)
