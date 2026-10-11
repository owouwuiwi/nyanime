package eu.kanade.presentation.more.settings.screen.browse.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import eu.kanade.domain.extension.UnifiedExtensionCatalogue
import eu.kanade.tachiyomi.R
import java.net.URI

@Composable
fun CatalogueLinkHelpDialog(onDismissRequest: () -> Unit, onLink: (String) -> Unit, unified: Boolean = false) {
    var link by rememberSaveable { mutableStateOf("") }
    val normalized = link.trim()
    val valid = normalized.length in 1..2048 &&
        runCatching {
            val uri = URI(normalized)
            uri.scheme == "https" &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null &&
                uri.rawFragment == null &&
                uri.rawQuery == null &&
                (!unified || UnifiedExtensionCatalogue.validUrl(normalized))
        }.getOrDefault(false)
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.catalogue_link_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.catalogue_link_instruction))
                OutlinedTextField(
                    link, { link = it }, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.catalogue_link_address)) },
                    placeholder = { Text("https://…/index.json") },
                    singleLine = true,
                    isError = link.isNotBlank() && !valid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (valid) onLink(normalized) }),
                )
                Text(stringResource(R.string.catalogue_link_confirmation))
            }
        },
        confirmButton = {
            TextButton(onClick = { onLink(normalized) }, enabled = valid) {
                Text(stringResource(R.string.extensions_catalogue_add))
            }
        },
        dismissButton = { TextButton(onClick = onDismissRequest) { Text(stringResource(R.string.action_close)) } },
    )
}
