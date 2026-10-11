package it.lorelore.nyanime.studio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.rin.RinRepository
import it.lorelore.nyanime.studio.document.StudioDocumentComponent
import it.lorelore.nyanime.studio.document.StudioDocumentComponents
import kotlinx.coroutines.CancellationException

private class DocumentChoices(
    val values: List<StudioDocumentComponent>?,
    val failed: Boolean,
    val retry: () -> Unit,
)

@Composable
private fun rememberDocumentChoices(): DocumentChoices {
    val context = LocalContext.current
    val packages = remember { RinRepository.get(context) }
    val records by packages.installed.collectAsState()
    var values by remember { mutableStateOf<List<StudioDocumentComponent>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(records, attempt) {
        failed = false
        try {
            values = StudioDocumentComponents.get(context).available()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            values = emptyList()
            failed = true
        }
    }
    return DocumentChoices(values, failed) { attempt++ }
}

/** Preserve imported requirements; only explicitly selected identifiers change. */
@Composable
internal fun StudioDocumentResolverSelection(selected: Set<String>, change: (Set<String>) -> Unit) {
    val choices = rememberDocumentChoices()
    val unique = choices.values.orEmpty().groupBy { it.id }.mapNotNull { (_, values) -> values.singleOrNull() }
        .sortedBy { it.label.lowercase() }
    var manual by remember { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.studio_document_components), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.studio_document_choose_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (choices.values == null) CircularProgressIndicator()
            if (choices.failed) {
                Text(stringResource(R.string.studio_access_failed), color = MaterialTheme.colorScheme.error)
                TextButton(choices.retry) { Text(stringResource(R.string.studio_telegram_retry)) }
            } else if (choices.values?.isEmpty() == true) {
                Text(stringResource(R.string.studio_document_none), style = MaterialTheme.typography.bodyMedium)
            }
            unique.forEach { component ->
                val checked = component.id in selected
                Row(
                    Modifier.fillMaxWidth().toggleable(
                        value = checked,
                        role = Role.Checkbox,
                        enabled = checked || selected.size < 16,
                        onValueChange = { change(if (it) selected + component.id else selected - component.id) },
                    ).padding(vertical = 4.dp),
                ) {
                    Checkbox(checked, onCheckedChange = null)
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(component.label, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            component.record.manifest.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            selected.filter { id -> unique.none { it.id == id } }.forEach { missing ->
                Text(
                    stringResource(R.string.studio_access_component_missing, missing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton({ change(selected - missing) }) { Text(stringResource(R.string.studio_document_remove)) }
            }
            TextButton({ manual = !manual }) { Text(stringResource(R.string.studio_document_manual)) }
            if (manual) {
                StudioField(stringResource(R.string.studio_document_identifiers), selected.joinToString("\n"), {
                    val next = it.lines().map(String::trim).filter(String::isNotBlank).toSet()
                    if (next.size <= 16) change(next)
                }, multiline = true)
            }
        }
    }
}

@Composable
internal fun StudioDocumentResolverChoice(allowed: Set<String>, selected: String, choose: (String) -> Unit) {
    val choices = rememberDocumentChoices()
    val labels = choices.values.orEmpty().groupBy { it.id }.mapNotNull { (id, values) ->
        values.singleOrNull()?.let { id to it.label }
    }.toMap()
    var expanded by remember { mutableStateOf(false) }
    Column {
        OutlinedButton({ expanded = true }, Modifier.fillMaxWidth(), enabled = allowed.isNotEmpty()) {
            Text(
                labels[selected] ?: selected.takeIf(String::isNotBlank)
                    ?: stringResource(R.string.studio_document_component),
            )
        }
        DropdownMenu(expanded, { expanded = false }) {
            allowed.forEach { id ->
                DropdownMenuItem(text = { Text(labels[id] ?: id) }, onClick = {
                    choose(id)
                    expanded = false
                })
            }
        }
        if (allowed.isEmpty()) {
            Text(
                stringResource(R.string.studio_document_choose_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
