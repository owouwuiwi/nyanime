package it.lorelore.nyanime.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.preference.PreferenceManager
import eu.kanade.tachiyomi.R
import it.lorelore.nyanime.reader.quality.QualityEngineId
import it.lorelore.nyanime.reader.quality.QualityModels
import it.lorelore.nyanime.reader.quality.QualityReaderState
import it.lorelore.nyanime.reader.quality.QualityTitle
import it.lorelore.nyanime.reader.quality.ReaderQualityPreferences
import tachiyomi.domain.entries.manga.model.Manga

@Composable
internal fun ReaderQualityPanel(manga: Manga?, onDismiss: () -> Unit, onColorSettings: () -> Unit) {
    val mangaId = manga?.id
    val title = remember(manga?.source, manga?.url) {
        manga?.takeIf { it.url.isNotBlank() && it.url.length <= 16384 }?.let { QualityTitle(it.source, it.url) }
    }
    val context = LocalContext.current
    val preferences =
        remember(context) { ReaderQualityPreferences(PreferenceManager.getDefaultSharedPreferences(context)) }
    val models = remember(context) { ReaderQualityModels.get(context) }
    val modelStates by models.states.collectAsState()
    LaunchedEffect(models) { models.refresh() }
    val selected by remember(title) { preferences.observe(title) }.collectAsState(preferences.selected(title))
    val originals by QualityReaderState.original.collectAsState()
    val runtime by QualityReaderState.status.collectAsState()
    var global by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    val overridden = remember(selected, revision, title) { title?.let(preferences::override) }
    val active = if (global) preferences.global() else selected
    val choose: (QualityEngineId) -> Unit = {
        mangaId?.let { id -> QualityReaderState.showOriginal(id, false) }
        if (global || title == null) preferences.setGlobal(it) else preferences.setOverride(title, it)
        if (it == selected && mangaId != null) QualityReaderState.retry(mangaId)
        revision++
    }
    ReaderActionsPanel(stringResource(R.string.reader_quality), onDismiss) {
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (title != null) {
                FilterChip(!global, { global = false }, label = { Text(stringResource(R.string.reader_this_manga)) })
            }
            FilterChip(global || title == null, {
                global = true
            }, label = { Text(stringResource(R.string.reader_all_manga)) })
        }
        Text(
            stringResource(R.string.reader_quality_original_safe),
            Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!global && title != null) {
            ReaderPanelAction(stringResource(R.string.reader_use_global), active = overridden == null) {
                mangaId?.let { QualityReaderState.showOriginal(it, false) }
                preferences.setOverride(title, null)
                revision++
            }
        }
        ReaderPanelAction(stringResource(R.string.reader_quality_off), active = active == QualityEngineId.OFF) {
            choose(QualityEngineId.OFF)
        }
        ReaderPanelAction(
            "Anime4K",
            stringResource(R.string.reader_quality_anime4k),
            active =
            active == QualityEngineId.ANIME4K,
        ) {
            choose(QualityEngineId.ANIME4K)
        }
        for (engine in listOf(QualityEngineId.REAL_CUGAN, QualityEngineId.REAL_ESRGAN)) {
            val model = requireNotNull(QualityModels.get(engine))
            val state = modelStates[engine] ?: ReaderQualityModels.State()
            Column {
                ReaderPanelAction(
                    if (engine == QualityEngineId.REAL_CUGAN) "RealCUGAN" else "Real-ESRGAN",
                    stringResource(
                        if (engine ==
                            QualityEngineId.REAL_CUGAN
                        ) {
                            R.string.reader_quality_cugan
                        } else {
                            R.string.reader_quality_esrgan
                        },
                    ),
                    active = active == engine,
                ) {
                    if (state.ready) choose(engine) else models.download(engine)
                }
                if (state.running) {
                    LinearProgressIndicator(
                        progress = { (state.bytes.toFloat() / model.downloadBytes).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 6.dp).heightIn(min = 40.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (state.running) {
                            stringResource(
                                R.string.reader_model_progress,
                                android.text.format.Formatter.formatShortFileSize(context, state.bytes),
                                android.text.format.Formatter.formatShortFileSize(context, model.downloadBytes),
                            )
                        } else {
                            model.license
                        },
                        Modifier.weight(1f).padding(horizontal = 8.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = {
                        when {
                            state.running -> models.cancel(engine)
                            state.ready -> {
                                if (active == engine) choose(QualityEngineId.OFF)
                                models.remove(engine)
                            }
                            else -> models.download(engine)
                        }
                    }) {
                        Text(
                            when {
                                state.running -> stringResource(R.string.reader_model_cancel)
                                state.ready -> stringResource(R.string.reader_model_remove)
                                else -> stringResource(
                                    R.string.reader_model_download,
                                    android.text.format.Formatter.formatShortFileSize(context, model.downloadBytes),
                                )
                            },
                        )
                    }
                }
                if (state.failed) {
                    Text(
                        stringResource(R.string.reader_model_error),
                        Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        if (selected != QualityEngineId.OFF && mangaId != null) {
            ReaderPanelAction(
                stringResource(
                    if (mangaId in
                        originals
                    ) {
                        R.string.reader_quality_show_enhanced
                    } else {
                        R.string.reader_quality_compare
                    },
                ),
                active = mangaId in originals,
            ) {
                QualityReaderState.showOriginal(mangaId, mangaId !in originals)
                onDismiss()
            }
            val message = when (runtime[mangaId]) {
                QualityReaderState.Status.WORKING -> R.string.reader_quality_working
                QualityReaderState.Status.READY -> R.string.reader_quality_ready
                QualityReaderState.Status.ORIGINAL_FALLBACK -> R.string.reader_quality_unavailable
                QualityReaderState.Status.MEMORY_FALLBACK -> R.string.reader_quality_memory
                else -> null
            }
            message?.let {
                Text(stringResource(it), Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
            }
            if (runtime[mangaId] in listOf(
                    QualityReaderState.Status.ORIGINAL_FALLBACK,
                    QualityReaderState.Status.MEMORY_FALLBACK,
                )
            ) {
                ReaderPanelAction(stringResource(R.string.reader_quality_retry)) { QualityReaderState.retry(mangaId) }
            }
        }
        ReaderPanelAction(stringResource(R.string.reader_color_settings), onClick = onColorSettings)
    }
}
