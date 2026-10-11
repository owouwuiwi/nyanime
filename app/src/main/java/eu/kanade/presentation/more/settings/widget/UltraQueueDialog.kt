package eu.kanade.presentation.more.settings.widget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.entries.anime.components.UltraDeleteDialog
import eu.kanade.presentation.entries.anime.components.UltraStatusRow
import eu.kanade.presentation.entries.anime.components.UltraTaskSheet
import eu.kanade.tachiyomi.data.download.anime.ultra.UltraDownloads
import eu.kanade.tachiyomi.data.download.anime.ultra.UltraPhase
import eu.kanade.tachiyomi.data.download.anime.ultra.UltraTask
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun UltraQueueDialog(
    animeId: Long? = null,
    loadDownloads: (suspend () -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        UltraQueueContent(animeId = animeId, modifier = Modifier.heightIn(max = 640.dp), loadDownloads = loadDownloads)
    }
}

@Composable
internal fun UltraQueueContent(
    animeId: Long? = null,
    modifier: Modifier = Modifier,
    loadDownloads: (suspend () -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cancelling by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableStateOf(0) }
    var selecting by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf(emptySet<String>()) }
    var deleting by remember { mutableStateOf<List<UltraTask>?>(null) }
    val result by produceState<Result<List<UltraTask>>?>(null, retry, animeId) {
        value = null
        try {
            loadDownloads?.invoke()
            UltraDownloads.observe(context).collect { value = Result.success(it) }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            value = Result.failure(error)
        }
    }
    val jobs = result?.getOrNull().orEmpty().filter {
        (animeId == null || it.animeId == animeId) &&
            (animeId != null || it.phase != UltraPhase.AVAILABLE)
    }
        .sortedBy { if (it.active || it.phase == UltraPhase.PAUSED) 0 else 1 }
    var selected by remember { mutableStateOf<UltraTask?>(null) }
    Column(modifier.fillMaxWidth()) {
        if (jobs.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = {
                    selecting = !selecting
                    selection = emptySet()
                }) {
                    Text(if (selecting) "Fine" else "Seleziona")
                }
                if (selecting) {
                    TextButton(onClick = {
                        selection = if (jobs.all { it.key in selection }) {
                            emptySet()
                        } else {
                            jobs.mapTo(hashSetOf()) { it.key }
                        }
                    }) { Text(if (jobs.all { it.key in selection }) "Deseleziona tutti" else "Seleziona tutti") }
                }
            }
        }
        val selectedJobs = jobs.filter { it.key in selection }
        if (selecting && selectedJobs.isNotEmpty()) {
            Button(
                onClick = { deleting = selectedJobs },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            ) { Text("Elimina selezionati (${selectedJobs.size})…") }
        }
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (animeId == null) "Download Ultra" else "Sul tuo dispositivo",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        "Riproduci, esporta o libera spazio: gestisci tutto da qui.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Il cestino permette di eliminare solo Ultra oppure l'intero download.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (jobs.any { it.active || it.phase == UltraPhase.PAUSED }) {
                        TextButton(
                            enabled = !cancelling,
                            onClick = {
                                scope.launch {
                                    cancelling = true
                                    actionError = null
                                    try {
                                        jobs.filter { it.active || it.phase == UltraPhase.PAUSED }.forEach {
                                            UltraDownloads.cancel(context, it)
                                        }
                                    } catch (error: Exception) {
                                        if (error is CancellationException) throw error
                                        actionError = "Non è stato possibile annullare tutta la coda. Riprova."
                                    } finally {
                                        cancelling = false
                                    }
                                }
                            },
                        ) { Text(if (cancelling) "Annullamento…" else "Annulla tutte le elaborazioni") }
                    }
                    actionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
            if (result == null || result?.isFailure == true) {
                item {
                    Text(
                        if (result == null) {
                            "Recupero dei download…"
                        } else {
                            "Non riesco a leggere lo stato Ultra. I video originali restano disponibili."
                        },
                    )
                    if (result?.isFailure == true) TextButton(onClick = { retry++ }) { Text("Riprova") }
                }
            } else if (jobs.isEmpty()) {
                item {
                    Text(
                        if (animeId == null) {
                            "Nessuna elaborazione Ultra. Puoi preparare una copia dalla scheda di un episodio scaricato."
                        } else {
                            "Nessun download da gestire. Gli episodi restano disponibili dalla scheda del titolo."
                        },
                        Modifier.padding(vertical = 24.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            items(jobs, key = { it.key }) { task ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(task.title, style = MaterialTheme.typography.titleSmall)
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        if (selecting) {
                            Checkbox(checked = task.key in selection, onCheckedChange = {
                                selection = if (it) selection + task.key else selection - task.key
                            })
                        }
                        UltraStatusRow(
                            task,
                            onClick = {
                                if (selecting) {
                                    selection = if (task.key in selection) {
                                        selection - task.key
                                    } else {
                                        selection + task.key
                                    }
                                } else {
                                    selected = task
                                }
                            },
                            modifier = Modifier.weight(1f),
                            onDelete = if (selecting) {
                                null
                            } else {
                                { deleting = listOf(task) }
                            },
                        )
                    }
                }
            }
        }
    }
    deleting?.let { tasks ->
        UltraDeleteDialog(tasks, onDismiss = { deleting = null }, onDeleted = {
            deleting = null
            selection = emptySet()
            selecting = false
        })
    }
    selected?.let { task ->
        UltraTaskSheet(
            task,
            onDismiss = { selected = null },
            onOpenTitle = if (task.animeId > 0) {
                {
                    NotificationReceiver.openAnimeEntryPendingActivity(context, task.animeId).send()
                    selected = null
                }
            } else {
                null
            },
        )
    }
}
