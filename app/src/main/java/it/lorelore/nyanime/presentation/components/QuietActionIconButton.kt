package it.lorelore.nyanime.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** Neutral action surface; destructive/selected state must not be implied by a solid brand fill. */
@Composable
internal fun QuietActionIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(48.dp),
        shape = RoundedCornerShape(14.dp),
        color = colors.surfaceContainerHigh,
        contentColor = colors.onSurface.copy(alpha = if (enabled) 1f else .38f),
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = if (enabled) .65f else .25f)),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription, Modifier.size(22.dp))
        }
    }
}
