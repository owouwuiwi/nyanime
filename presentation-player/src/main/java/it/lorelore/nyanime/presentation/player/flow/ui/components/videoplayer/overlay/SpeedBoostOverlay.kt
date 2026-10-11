// SPDX-License-Identifier: GPL-3.0-only
// Derived from FLOW 14afdf9e9426fb8b67e5fcbc76bf4e81af492d16.
// Modified for Nyanime: UI-only, host-owned playback, no service/runtime access.
// Original source identities and changes: presentation-player/third-party/flow-ui/PROVENANCE.json.

@file:Suppress("ktlint:standard:no-wildcard-imports")

package it.lorelore.nyanime.presentation.player.flow.ui.components.videoplayer.overlay

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.lorelore.nyanime.presentation.player.flow.ui.theme.PlayerScrim
import it.lorelore.nyanime.presentation.player.flow.ui.theme.PlayerScrimContent
import it.lorelore.nyanime.presentation.player.flow.ui.theme.PlayerScrimPanel
import it.lorelore.nyanime.presentation.player.flow.utils.formatMultiplierLabel

@Composable
internal fun SpeedBoostOverlay(
    isVisible: Boolean,
    speed: Float = 2.0f,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = isVisible,
        enter =
        fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()) +
            expandVertically(MaterialTheme.motionScheme.fastSpatialSpec(), expandFrom = Alignment.Top),
        exit =
        fadeOut(MaterialTheme.motionScheme.defaultEffectsSpec()) +
            shrinkVertically(MaterialTheme.motionScheme.fastSpatialSpec(), shrinkTowards = Alignment.Top),
        modifier = modifier,
    ) {
        Surface(
            color = PlayerScrimPanel,
            shape = CircleShape,
            modifier = Modifier.wrapContentSize(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = formatMultiplierLabel(speed, maxValue = 4.0f),
                    color = PlayerScrimContent,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Rounded.FastForward,
                    contentDescription = null,
                    tint = PlayerScrimContent,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
