package eu.kanade.presentation.more.settings.screen.browse.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R

@Composable
fun CatalogueResetNotice(onAcknowledge: () -> Unit) {
    AlertDialog(
        onDismissRequest = onAcknowledge,
        title = { Text(stringResource(R.string.catalogue_reset_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.catalogue_reset_description))
                Text(stringResource(R.string.catalogue_reset_preserved))
                Text(stringResource(R.string.catalogue_link_instruction))
            }
        },
        confirmButton = {
            TextButton(onClick = onAcknowledge) { Text(stringResource(R.string.catalogue_understood)) }
        },
    )
}
