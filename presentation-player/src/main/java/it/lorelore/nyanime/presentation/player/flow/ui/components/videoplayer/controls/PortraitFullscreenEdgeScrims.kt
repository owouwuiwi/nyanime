// SPDX-License-Identifier: GPL-3.0-only
// Derived from FLOW 14afdf9e9426fb8b67e5fcbc76bf4e81af492d16.
// Modified for Nyanime: UI-only, host-owned playback, no service/runtime access.
// Original source identities and changes: presentation-player/third-party/flow-ui/PROVENANCE.json.

package it.lorelore.nyanime.presentation.player.flow.ui.components.videoplayer.controls

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import it.lorelore.nyanime.presentation.player.flow.ui.theme.PlayerScrimEdgeGradient

@Composable
internal fun PortraitFullscreenEdgeScrims(modifier: Modifier = Modifier) {
    Box(modifier = modifier) {
        Box(
            modifier =
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.24f)
                .playerEdgeScrim(ScrimEdge.Top, PlayerScrimEdgeGradient),
        )
        Box(
            modifier =
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.24f)
                .playerEdgeScrim(ScrimEdge.Bottom, PlayerScrimEdgeGradient),
        )
    }
}
