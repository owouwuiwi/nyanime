package eu.kanade.presentation.components.releases

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.motion.ModernMotion
import eu.kanade.presentation.motion.appMotionEnabled
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.releases.ReleaseMedium

@Composable
internal fun ReleaseMediaFilters(medium: ReleaseMedium?, allowAllMedia: Boolean, onMedium: (ReleaseMedium?) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (allowAllMedia) {
            ReleaseMediaFilter(null, medium == null, { onMedium(null) }, Modifier.weight(1f))
        }
        ReleaseMedium.entries.forEach { value ->
            ReleaseMediaFilter(value, medium == value, { onMedium(value) }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ReleaseMediaFilter(medium: ReleaseMedium?, isSelected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val motion = appMotionEnabled()
    val cue = if (medium != null) {
        releaseColor(medium)
    } else {
        if (MaterialTheme.colorScheme.surface.luminance() < .5f) Color(0xFFBCA3F5) else Color(0xFF6940AA)
    }
    val fill = animateFloatAsState(
        targetValue = if (isSelected) .2f else 0f,
        animationSpec = tween(if (motion) ModernMotion.RESIZE_MILLIS else 0),
        label = "release-selection",
    )
    Surface(
        onClick = onClick,
        modifier = modifier.semantics {
            selected = isSelected
            role = Role.Tab
        },
        shape = RoundedCornerShape(10.dp),
        color = Color.Transparent,
    ) {
        Box(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).drawWithCache {
                val strokeWidth = 1.dp.toPx()
                val corner = CornerRadius(10.dp.toPx())
                onDrawBehind {
                    // Selection changes redraw the surface without moving its contents.
                    drawRect(cue.copy(alpha = fill.value))
                    drawRoundRect(
                        cue.copy(alpha = if (isSelected) .9f else .45f),
                        topLeft = Offset(strokeWidth / 2, strokeWidth / 2),
                        size = Size(size.width - strokeWidth, size.height - strokeWidth),
                        cornerRadius = corner,
                        style = Stroke(strokeWidth),
                    )
                }
            }.padding(horizontal = 8.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(
                    when (medium) {
                        null -> R.string.release_all
                        ReleaseMedium.ANIME -> R.string.release_anime
                        ReleaseMedium.MANGA -> R.string.release_manga
                    },
                ),
                style = MaterialTheme.typography.labelLarge,
                color = cue,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
