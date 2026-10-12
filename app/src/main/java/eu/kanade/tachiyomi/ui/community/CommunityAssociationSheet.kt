package eu.kanade.tachiyomi.ui.community

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.data.community.CommunityManager
import eu.kanade.tachiyomi.data.community.CommunityState
import eu.kanade.tachiyomi.data.community.SyncRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun AssociationSheet(
    pending: SyncRecord,
    state: CommunityState,
    manager: CommunityManager,
    close: () -> Unit,
) {
    var query by remember { mutableStateOf(pending.title) }
    var title by remember { mutableStateOf<SyncRecord?>(null) }
    var target by remember { mutableStateOf<SyncRecord?>(null) }
    var parts by remember { mutableStateOf<List<SyncRecord>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(title) {
        val selected = title ?: return@LaunchedEffect
        if (pending.ref.itemUrl.isEmpty()) {
            target = selected
        } else {
            loading = true
            try {
                parts = withContext(Dispatchers.IO) { manager.mappingCandidates(selected) }
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (_: Exception) {
                error = "Non riesco a leggere gli episodi o i capitoli. Apri prima il titolo dalla libreria."
            } finally {
                loading = false
            }
        }
    }
    SheetFrame("Ritrova la tua ripresa", close) {
        Text(pending.title, style = MaterialTheme.typography.headlineSmall)
        if (pending.item.isNotEmpty()) Text(pending.item, style = MaterialTheme.typography.titleMedium)
        Text(
            "Scegli la stessa edizione nella libreria di questo dispositivo. La corrispondenza viene salvata solo dopo la tua conferma.",
        )
        if (title == null) {
            OutlinedTextField(query, {
                query = it
            }, label = { Text("Cerca nella tua libreria") }, modifier = Modifier.fillMaxWidth())
            val matches = state.library.filter { it.ref.manga == pending.ref.manga && it.title.contains(query, true) }
            if (matches.isEmpty()) {
                Text(
                    "Nessuna corrispondenza. Aggiungi il titolo dalla fonte che vuoi usare, poi torna qui.",
                )
            }
            matches.take(60).forEach { candidate ->
                OutlinedButton(onClick = { title = candidate }, modifier = Modifier.fillMaxWidth()) {
                    Text(candidate.title)
                }
            }
        } else {
            TextButton(onClick = {
                title = null
                target = null
                parts = emptyList()
            }) { Text("Scegli un altro titolo") }
            if (loading) Text("Leggo gli episodi e i capitoli…")
            if (!loading &&
                pending.ref.itemUrl.isNotEmpty() &&
                parts.isEmpty()
            ) {
                Text("Apri il titolo nella libreria per caricarne gli episodi o i capitoli, poi riprova.")
            }
            if (target == null) {
                parts.forEach { part ->
                    OutlinedButton(onClick = { target = part }, modifier = Modifier.fillMaxWidth()) { Text(part.item) }
                }
            }
            target?.let { selected ->
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Ripresa da associare a", style = MaterialTheme.typography.labelLarge)
                    Text(selected.title, style = MaterialTheme.typography.titleLarge)
                    if (selected.item.isNotEmpty()) Text(selected.item)
                    Button(onClick = {
                        manager.associate(pending, selected)
                        close()
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("Conferma corrispondenza")
                    }
                    if (pending.ref.itemUrl.isNotEmpty()) {
                        TextButton(onClick = {
                            target = null
                        }) { Text("Cambia episodio o capitolo") }
                    }
                }
            }
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    }
}
