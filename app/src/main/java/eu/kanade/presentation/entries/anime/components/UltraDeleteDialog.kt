package eu.kanade.presentation.entries.anime.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.anime.AnimeDownloadManager
import eu.kanade.tachiyomi.data.download.anime.ultra.UltraDownloads
import eu.kanade.tachiyomi.data.download.anime.ultra.UltraFiles
import eu.kanade.tachiyomi.data.download.anime.ultra.UltraRemoval
import eu.kanade.tachiyomi.data.download.anime.ultra.UltraTask
import eu.kanade.tachiyomi.util.size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
internal fun UltraDeleteDialog(
    tasks: List<UltraTask>,
    onDismiss: () -> Unit,
    onDeleted: (onlyUltra: Boolean) -> Unit = { onDismiss() },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var onlyUltra by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf(0) }
    var pending by remember { mutableStateOf(tasks.distinctBy { it.key }) }
    val sizes by produceState<Pair<Long, Long>?>(null, pending.map { it.key }) {
        value = null
        value = withContext(Dispatchers.IO) {
            runCatching {
                var all = 0L
                var ultra = 0L
                pending.forEach { task ->
                    val folder = UniFile.fromUri(context, task.folder.toUri())
                    val temporary = UltraDownloads.scratch(context, task.key).walkTopDown()
                        .filter { it.isFile }.sumOf { it.length() }
                    all += (folder?.size() ?: 0L) + temporary
                    ultra += temporary +
                        listOf(UltraFiles.VIDEO, UltraFiles.PART, UltraFiles.MARKER)
                            .sumOf { folder?.findFile(it)?.length() ?: 0L }
                }
                all to ultra
            }.getOrNull()
        }
    }
    UltraDeleteConfirmation(
        title = if (tasks.size == 1) tasks.first().title else "${tasks.size} episodi selezionati",
        onlyUltra = onlyUltra,
        onChoose = { onlyUltra = it },
        size = sizes?.let {
            android.text.format.Formatter.formatFileSize(context, if (onlyUltra) it.second else it.first)
        },
        busy = busy,
        progress = if (busy) "Eliminazione · $progress di ${pending.size}" else null,
        error = error,
        onDismiss = onDismiss,
        onConfirm = {
            scope.launch {
                busy = true
                error = null
                progress = 0
                try {
                    val failed = UltraRemoval.batch(
                        pending,
                        remove = { Injekt.get<AnimeDownloadManager>().deleteStoredDownload(it, onlyUltra) },
                        onProgress = { progress = it },
                    )
                    if (failed.isEmpty()) {
                        onDeleted(onlyUltra)
                    } else {
                        pending = failed.map { it.first }
                        val reason = failed.first().second.message ?: "Impossibile eliminare il download."
                        error = "${failed.size} download da riprovare. $reason"
                    }
                } finally {
                    busy = false
                }
            }
        },
    )
}

@Composable
internal fun UltraDeleteConfirmation(
    title: String,
    onlyUltra: Boolean,
    onChoose: (Boolean) -> Unit,
    size: String?,
    busy: Boolean,
    progress: String?,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = !busy, dismissOnClickOutside = !busy),
        icon = { Icon(Icons.Outlined.DeleteOutline, null) },
        title = { Text("Libera spazio") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                listOf(
                    false to "Elimina il download completo",
                    true to "Elimina solo Ultra",
                ).forEach { (choice, label) ->
                    Row(
                        Modifier.fillMaxWidth().selectable(
                            selected = onlyUltra == choice,
                            enabled = !busy,
                            role = Role.RadioButton,
                            onClick = { onChoose(choice) },
                        ).padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = onlyUltra == choice, onClick = null, enabled = !busy)
                        Text(label, Modifier.padding(start = 12.dp))
                    }
                }
                Text(
                    if (onlyUltra) {
                        "Conserva il video originale. Rimuove la copia Ultra e i file di lavorazione, interrompendo l'elaborazione."
                    } else {
                        "Rimuove originale, copia Ultra e file di lavorazione. Il titolo resta in libreria e il punto di visione è conservato."
                    },
                )
                size?.let { Text("Spazio recuperabile: circa $it", style = MaterialTheme.typography.titleSmall) }
                Text(
                    "Le copie esportate in altre cartelle restano dove le hai salvate.",
                    style = MaterialTheme.typography.bodySmall,
                )
                progress?.let { Text(it) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !busy) {
                Text(
                    if (busy) {
                        "Eliminazione…"
                    } else if (error !=
                        null
                    ) {
                        "Riprova"
                    } else {
                        "Elimina"
                    },
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Annulla") } },
    )
}
