package eu.kanade.tachiyomi.ui.player.controls.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** The caller retains the existing skip/cancel semantics and countdown. */
@Composable
fun PlayerSkipCue(
    label: String,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    onCancel: (() -> Unit)? = null,
    showActions: Boolean = true,
) {
    Surface(
        modifier.widthIn(max = 360.dp),
        shape = RoundedCornerShape(8.dp),
        color = Color(0xED181818),
        contentColor = Color.White,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.24f)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f, fill = false).heightIn(min = 48.dp)
                    .clickable(enabled = showActions, role = Role.Button, onClick = onSkip)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.SkipNext, null, Modifier.size(20.dp))
                Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    detail?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.72f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (showActions && onCancel != null) {
                IconButton(onClick = onCancel) { Icon(Icons.Default.Close, "Annulla il salto", Modifier.size(20.dp)) }
            }
        }
    }
}
