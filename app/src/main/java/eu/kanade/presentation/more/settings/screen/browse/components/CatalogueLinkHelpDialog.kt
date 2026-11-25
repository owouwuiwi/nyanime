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
import eu.kanade.domain.extension.ExtensionCatalogueLink
import eu.kanade.tachiyomi.R

@Composable
fun CatalogueLinkHelpDialog(
    onDismissRequest: () -> Unit,
    onLink: (String) -> Unit,
    unified: Boolean = false,
    onDriveLink: (String) -> Unit = onLink,
) {
    var link by rememberSaveable { mutableStateOf("") }
    val normalized = link.trim()
    val catalogueLink = ExtensionCatalogueLink.parse(normalized)
    val isDriveLink = catalogueLink is ExtensionCatalogueLink.DriveFolder ||
        catalogueLink is ExtensionCatalogueLink.Index && catalogueLink.isDrive
    val valid = catalogueLink != null || (!unified && ExtensionCatalogueLink.isLegacyRepositoryUrl(normalized))
    val invalid = link.isNotBlank() && !valid
    val submitLink = {
        if (isDriveLink) onDriveLink(normalized) else onLink(normalized)
    }
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
                    isError = invalid,
                    supportingText = {
                        if (invalid) Text(stringResource(R.string.catalogue_link_invalid))
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (valid) submitLink() }),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = submitLink, enabled = valid) {
                Text(stringResource(R.string.extensions_catalogue_add))
            }
        },
        dismissButton = { TextButton(onClick = onDismissRequest) { Text(stringResource(R.string.action_close)) } },
    )
}
