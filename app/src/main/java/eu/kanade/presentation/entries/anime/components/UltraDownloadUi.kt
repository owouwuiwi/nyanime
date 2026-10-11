package eu.kanade.presentation.entries.anime.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.hippo.unifile.UniFile
import eu.kanade.presentation.more.settings.widget.UltraQueueDialog
import eu.kanade.tachiyomi.data.download.anime.AnimeDownloadManager
import eu.kanade.tachiyomi.data.download.anime.ultra.UltraDownloads
import eu.kanade.tachiyomi.data.download.anime.ultra.UltraFiles
import eu.kanade.tachiyomi.data.download.anime.ultra.UltraPhase
import eu.kanade.tachiyomi.data.download.anime.ultra.UltraTask
import eu.kanade.tachiyomi.data.download.anime.ultra.UltraTaskStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.items.episode.model.Episode
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

internal val UltraAccent: Color
    @Composable get() = MaterialTheme.colorScheme.primary

/** This row is composed only for visible downloaded episodes; filesystem discovery never runs on main. */
@Composable
internal fun UltraEpisodeDownload(anime: Anime, episode: Episode, onPlay: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var retry by remember(episode.id) { mutableStateOf(0) }
    var unavailable by remember(episode.id) { mutableStateOf(false) }
    val task by produceState<UltraTask?>(null, anime.id, episode.id, retry) {
        try {
            unavailable = false
            val found = Injekt.get<AnimeDownloadManager>().describeUltra(anime, episode)
            if (found == null) {
                unavailable = true
                return@produceState
            }
            UltraTaskStore.get(context).tasks.map { it[found.key] }.distinctUntilChanged().collect { value = it }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            unavailable = true
        }
    }
    var show by remember(episode.id) { mutableStateOf(false) }
    var delete by remember(episode.id) { mutableStateOf(false) }
    if (task == null) {
        TextButton(
            onClick = { retry++ },
            enabled = unavailable,
            modifier = modifier.padding(horizontal = 16.dp).heightIn(min = 48.dp),
        ) {
            Text(if (unavailable) "Stato del download non disponibile · riprova" else "Lettura del download…")
        }
    }
    task?.let {
        UltraStatusRow(
            it,
            { show = true },
            modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
            onDelete = { delete = true },
        )
        if (delete) UltraDeleteDialog(listOf(it), onDismiss = { delete = false })
        if (show) {
            UltraTaskSheet(it, onDismiss = { show = false }, onPlay = {
                show = false
                onPlay()
            })
        }
    }
}

@Composable
internal fun UltraTitleSummary(anime: Anime, episodes: List<Episode>) {
    val animeId = anime.id
    val downloaded = episodes.size
    if (downloaded == 0) return
    val context = LocalContext.current
    val flow =
        remember(animeId) {
            UltraDownloads.observe(context).map { tasks -> tasks.filter { it.animeId == animeId } }
                .catch { emit(emptyList()) }
        }
    val tasks by flow.collectAsState(emptyList())
    var show by remember { mutableStateOf(false) }
    if (downloaded == 0 && tasks.none { it.active || it.phase == UltraPhase.PAUSED }) return
    val pending = tasks.count { it.active || it.phase == UltraPhase.PAUSED }
    Surface(
        onClick = { show = true },
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Filled.Download, null, tint = UltraAccent)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Sul tuo dispositivo", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (pending >
                        0
                    ) {
                        "$downloaded scaricati · $pending elaborazioni Ultra"
                    } else {
                        "$downloaded scaricati · pronti da guardare"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, "Gestisci i download")
        }
    }
    if (show) {
        UltraQueueDialog(animeId = animeId, onDismiss = { show = false }, loadDownloads = {
            episodes.forEach { Injekt.get<AnimeDownloadManager>().describeUltra(anime, it) }
        })
    }
}

@Composable
internal fun UltraStatusRow(
    task: UltraTask,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onDelete: (() -> Unit)? = null,
) {
    val ready = task.phase == UltraPhase.READY
    val available = task.phase == UltraPhase.AVAILABLE || task.phase == UltraPhase.CANCELLED
    Surface(
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(
            Modifier.clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(
                    when {
                        ready -> Icons.Filled.CheckCircle
                        task.phase == UltraPhase.PAUSED || task.phase == UltraPhase.WAITING -> Icons.Filled.PauseCircle
                        available -> Icons.Filled.Download
                        else -> Icons.Filled.AutoAwesome
                    },
                    null,
                    modifier = Modifier.size(18.dp),
                    tint = if (available) MaterialTheme.colorScheme.onSurfaceVariant else UltraAccent,
                )
                Text(
                    when {
                        ready -> "ULTRA · pronto"
                        available -> "Scaricato · gestisci"
                        task.phase == UltraPhase.FAILED -> "Ultra da riprendere"
                        else -> task.message
                    },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (task.active || task.phase == UltraPhase.PAUSED) {
                    Text("${task.progress}%", color = UltraAccent, style = MaterialTheme.typography.labelMedium)
                }
                Icon(Icons.AutoMirrored.Filled.ArrowForward, "Opzioni del download", Modifier.size(16.dp))
                if (onDelete != null) {
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Outlined.DeleteOutline,
                            "Elimina download",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (task.active || task.phase == UltraPhase.PAUSED) {
                LinearProgressIndicator(
                    progress = { task.progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                    color = UltraAccent,
                    trackColor = UltraAccent.copy(alpha = 0.12f),
                )
            }
        }
    }
}

@Composable
internal fun UltraTaskSheet(
    task: UltraTask,
    onDismiss: () -> Unit,
    onPlay: (() -> Unit)? = null,
    onOpenTitle: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tasks by UltraTaskStore.get(context).tasks.collectAsState()
    val current = tasks[task.key] ?: task
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var fileName by remember { mutableStateOf<String?>(null) }
    var exportUltra by rememberSaveable { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf("Nella cartella di questo episodio, insieme al download originale.") }
    LaunchedEffect(current.phase) {
        val info = withContext(Dispatchers.IO) {
            runCatching {
                val folder = UniFile.fromUri(context, current.folder.toUri())
                val video = folder?.let { UltraFiles.completed(context, it) ?: UltraFiles.original(it) }
                val name = video?.name
                val originalBytes = folder?.let(UltraFiles::original)?.length() ?: 0L
                val ultraBytes = folder?.findFile(UltraFiles.VIDEO)?.length() ?: 0L
                val temporaryBytes = UltraDownloads.scratch(context, current.key).walkTopDown()
                    .filter { it.isFile }.sumOf { it.length() } +
                    (folder?.findFile(UltraFiles.PART)?.length() ?: 0L)
                fun size(bytes: Long) = android.text.format.Formatter.formatFileSize(context, bytes)
                name to (
                    video?.let {
                        "Originale · ${size(
                            originalBytes,
                        )}\nCopia Ultra · ${size(ultraBytes)}\nTemporanei · ${size(temporaryBytes)}\n${it.name}"
                    } ?: "Il file originale non è più disponibile."
                    )
            }.getOrDefault(null to "Impossibile accedere al file. Controlla la cartella dei download.")
        }
        fileName = info.first
        details = info.second
    }
    fun command(action: suspend () -> Unit) {
        scope.launch {
            busy = true
            feedback = null
            try {
                action()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                feedback = error.message ?: "Operazione non riuscita. Riprova."
            } finally {
                busy = false
            }
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/*")) { uri ->
        if (uri != null) {
            command {
                UltraDownloads.export(context, current, uri, exportUltra) { feedback = "Esportazione · $it%" }
                feedback = "Copia salvata nella posizione che hai scelto."
            }
        }
    }
    ModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        UltraTaskContent(
            current, details, busy, feedback,
            primaryLabel = if (onPlay ==
                null
            ) {
                "Apri il titolo"
            } else if (current.phase ==
                UltraPhase.READY
            ) {
                "Guarda in Ultra"
            } else {
                "Guarda l'originale"
            },
            onPrimary = if (fileName != null) onPlay ?: onOpenTitle else onOpenTitle,
            onToggle = {
                command {
                    if (current.active) {
                        UltraDownloads.pause(context, current)
                    } else {
                        UltraDownloads.enqueue(context, current, replace = true)
                    }
                }
            },
            onExport = {
                exportUltra = current.phase == UltraPhase.READY
                export.launch(
                    if (current.phase ==
                        UltraPhase.READY
                    ) {
                        "${current.title.take(80).replace(Regex("[^\\p{L}\\p{N} ._-]"), "_")}-Ultra.mkv"
                    } else {
                        requireNotNull(fileName)
                    },
                )
            },
            onCancel = { command { UltraDownloads.cancel(context, current) } },
            canExport = fileName != null,
            canPrepare = fileName != null,
            onDelete = { delete = true },
        )
    }
    if (delete) {
        UltraDeleteDialog(listOf(current), onDismiss = { delete = false }, onDeleted = { onlyUltra ->
            delete = false
            if (!onlyUltra) onDismiss()
        })
    }
}

@Composable
internal fun UltraTaskContent(
    current: UltraTask,
    details: String,
    busy: Boolean,
    feedback: String?,
    primaryLabel: String,
    onPrimary: (() -> Unit)?,
    onToggle: () -> Unit,
    onExport: () -> Unit,
    onCancel: () -> Unit,
    canExport: Boolean = true,
    canPrepare: Boolean = true,
    onDelete: (() -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxWidth().widthIn(max = 600.dp).verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.Download, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "IL TUO DOWNLOAD",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(current.title, style = MaterialTheme.typography.titleLarge)
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (current.phase ==
                        UltraPhase.READY
                    ) {
                        "Ultra è pronto"
                    } else {
                        current.message
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                if (current.active || current.phase == UltraPhase.PAUSED) {
                    LinearProgressIndicator(
                        progress = { current.progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = UltraAccent,
                        trackColor = UltraAccent.copy(alpha = 0.12f),
                    )
                    Text(
                        "${current.progress}% · il punto di ripresa resta sul dispositivo",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    if (current.phase ==
                        UltraPhase.READY
                    ) {
                        "Premi Play su questo episodio: Nyanime usa automaticamente la copia Ultra."
                    } else {
                        "Puoi già guardare l'originale. Ultra prepara una seconda copia, con calma, e si ferma quando usi il player o il telefono è caldo."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (onPrimary != null) {
            Button(
                onClick = onPrimary,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.onSurface,
                    contentColor = MaterialTheme.colorScheme.surface,
                ),
                contentPadding = PaddingValues(16.dp),
            ) {
                Icon(Icons.Filled.PlayArrow, null, Modifier.padding(end = 8.dp))
                Text(primaryLabel)
            }
        }
        if (onDelete != null) {
            OutlinedButton(onClick = onDelete, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.DeleteOutline, null, Modifier.padding(end = 8.dp))
                Text("Elimina download…")
            }
        }
        when {
            current.active -> OutlinedButton(
                onClick = onToggle,
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            ) {
                Text(
                    if (current.phase ==
                        UltraPhase.RUNNING
                    ) {
                        "Metti Ultra in pausa"
                    } else {
                        "Sospendi la ripresa automatica"
                    },
                )
            }
            current.phase != UltraPhase.READY -> OutlinedButton(
                onClick = onToggle,
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy && canPrepare,
            ) {
                Text(
                    if (current.phase == UltraPhase.AVAILABLE ||
                        current.phase == UltraPhase.CANCELLED
                    ) {
                        "Prepara una copia Ultra"
                    } else {
                        "Riprendi Ultra"
                    },
                )
            }
        }
        Text("File e spazio occupato", style = MaterialTheme.typography.titleSmall)
        Text(
            details,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = onExport,
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy && canExport,
        ) { Text(if (busy) "Operazione in corso…" else "Esporta una copia…") }
        Text(
            "Scegli Download o un'altra cartella per ritrovarlo anche fuori dall'app.",
            style = MaterialTheme.typography.bodySmall,
        )
        feedback?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        if (current.phase != UltraPhase.AVAILABLE &&
            current.phase != UltraPhase.READY &&
            current.phase != UltraPhase.CANCELLED
        ) {
            TextButton(
                onClick = onCancel,
                enabled = !busy,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) { Text("Annulla Ultra e libera i file temporanei") }
        }
    }
}
