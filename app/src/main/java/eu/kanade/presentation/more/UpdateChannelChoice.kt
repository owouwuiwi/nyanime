package eu.kanade.presentation.more

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.motion.ModernMotion
import eu.kanade.presentation.motion.appMotionEnabled
import eu.kanade.tachiyomi.R
import tachiyomi.domain.release.model.UpdateChannel

/** Shared wording and accessible radio cards for onboarding and settings. */
@Composable
fun UpdateChannelChoice(selected: UpdateChannel, onSelect: (UpdateChannel) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val motion = appMotionEnabled()
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        UpdateChannel.entries.forEach { channel ->
            val checked = channel == selected
            val color by animateColorAsState(
                if (checked) colors.primary.copy(alpha = 0.08f) else colors.surfaceContainer,
                tween(if (motion) ModernMotion.PAGE_MILLIS else 0),
                label = "updateChannelSurface",
            )
            val outline by animateColorAsState(
                if (checked) colors.primary else colors.outlineVariant,
                tween(if (motion) ModernMotion.PAGE_MILLIS else 0),
                label = "updateChannelOutline",
            )
            Surface(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).selectable(
                    selected = checked,
                    role = Role.RadioButton,
                    onClick = { onSelect(channel) },
                ),
                shape = RoundedCornerShape(24.dp),
                color = color,
                border = BorderStroke(1.dp, outline),
            ) {
                Row(
                    Modifier.padding(18.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        if (channel ==
                            UpdateChannel.RECOMMENDED
                        ) {
                            Icons.Outlined.VerifiedUser
                        } else {
                            Icons.Outlined.Science
                        },
                        null,
                        Modifier.padding(top = 6.dp).size(28.dp),
                        tint = if (checked) colors.primary else colors.onSurfaceVariant,
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(channel.titleResource()),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (channel == UpdateChannel.RECOMMENDED) {
                            Surface(shape = RoundedCornerShape(8.dp), color = colors.primary.copy(alpha = 0.1f)) {
                                Text(
                                    stringResource(R.string.app_update_recommended_badge),
                                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    color = colors.primary,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                        Text(
                            stringResource(
                                if (channel == UpdateChannel.RECOMMENDED) {
                                    R.string.app_update_recommended_description
                                } else {
                                    R.string.app_update_preview_description
                                },
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    RadioButton(checked, onClick = null, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
        }
    }
}

fun UpdateChannel.titleResource() = if (this == UpdateChannel.RECOMMENDED) {
    R.string.app_update_recommended
} else {
    R.string.app_update_previews
}
