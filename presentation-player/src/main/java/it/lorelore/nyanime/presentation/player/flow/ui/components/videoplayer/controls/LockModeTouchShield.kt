// SPDX-License-Identifier: GPL-3.0-only
// Derived from FLOW 14afdf9e9426fb8b67e5fcbc76bf4e81af492d16.
// Modified for Nyanime: UI-only, host-owned playback, no service/runtime access.
// Original source identities and changes: presentation-player/third-party/flow-ui/PROVENANCE.json.

package it.lorelore.nyanime.presentation.player.flow.ui.components.videoplayer.controls

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import it.lorelore.nyanime.presentation.player.R

@Composable
fun LockModeTouchShield(
    onRevealUnlock: () -> Unit,
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val unlockLabel = stringResource(R.string.flow_player_unlock_controls)

    Box(
        modifier =
        modifier
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        onRevealUnlock()
                        event.changes.forEach { it.consume() }
                    }
                }
            }.semantics {
                onClick(label = unlockLabel) {
                    onUnlock()
                    true
                }
            },
    )
}
