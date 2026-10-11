package eu.kanade.presentation.updates

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun UpdatesInboxFilter(
    pending: Boolean,
    pendingCount: Int,
    allCount: Int,
    isAnime: Boolean,
    onPendingChange: (Boolean) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text("Le tue novità", style = MaterialTheme.typography.titleLarge)
        Text(
            if (isAnime) {
                "Nuovi episodi dei titoli che segui o hai guardato"
            } else {
                "Nuovi capitoli dei titoli che segui o hai letto"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            UpdateFilterCard(
                label = if (isAnime) "Da vedere" else "Da leggere",
                count = pendingCount,
                detail = "titoli",
                selected = pending,
                onClick = { onPendingChange(true) },
            )
            UpdateFilterCard(
                label = "Tutti",
                count = allCount,
                detail = if (isAnime) "episodi" else "capitoli",
                selected = !pending,
                onClick = { onPendingChange(false) },
            )
        }
    }
}

@Composable
private fun RowScope.UpdateFilterCard(
    label: String,
    count: Int,
    detail: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = Modifier.weight(1f),
        shape = MaterialTheme.shapes.large,
        color = if (selected) colors.primaryContainer else colors.surfaceContainerLow,
        border = BorderStroke(1.dp, if (selected) colors.primary else colors.outlineVariant),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(count.toString(), style = MaterialTheme.typography.headlineSmall)
                Text(detail, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(bottom = 4.dp))
            }
        }
    }
}

@Composable
fun UpdatesInboxEmpty(isAnime: Boolean, modifier: Modifier = Modifier, onRefresh: () -> Unit) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.extraLarge) {
            Icon(
                Icons.Outlined.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.padding(18.dp).size(32.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Text("Sei al passo con tutto", style = MaterialTheme.typography.titleLarge)
        Text(
            if (isAnime) {
                "Quando usciranno nuovi episodi dei titoli che segui o hai guardato, li troverai qui."
            } else {
                "Quando usciranno nuovi capitoli dei titoli che segui o hai letto, li troverai qui."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onRefresh) { Text("Controlla novità") }
    }
}
