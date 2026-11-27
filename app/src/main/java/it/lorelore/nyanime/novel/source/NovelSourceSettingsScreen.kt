package it.lorelore.nyanime.novel.source

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.rin.RinRepository
import it.lorelore.nyanime.novel.ui.NovelBrowseSourceScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Reuses the shared signer-bound enablement and update controls, with no provider settings. */
class NovelSourceSettingsScreen(private val packageName: String) : Screen() {
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val repository = remember { RinRepository.get(context) }
        val records by repository.installed.collectAsState()
        val record = records.singleOrNull {
            it.manifest.packageName == packageName && it.manifest.medium == "novel"
        }
        val navigator = LocalNavigator.current
        val scope = rememberCoroutineScope()
        var changing by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf(false) }
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                TextButton(onClick = { navigator?.pop() }) {
                    Text(stringResource(R.string.guide_back))
                }
                Text(
                    record?.manifest?.name ?: stringResource(R.string.novel_sources),
                    style = MaterialTheme.typography.headlineMedium,
                )
                record?.let { current ->
                    Text(
                        current.manifest.versionName,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    current.manifest.sources.forEach { source ->
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Row(
                                Modifier.padding(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        source.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(source.language, style = MaterialTheme.typography.labelMedium)
                                }
                                FilledTonalButton(
                                    onClick = { navigator?.push(NovelBrowseSourceScreen(source.id)) },
                                    enabled = current.enabled &&
                                        !changing &&
                                        RinRepository.compatible(current.manifest, current.format),
                                ) { Text(stringResource(R.string.reading_home_explore)) }
                            }
                        }
                    }
                    Row {
                        Text(stringResource(R.string.store_active), Modifier.weight(1f))
                        Switch(
                            current.enabled,
                            enabled = !changing,
                            onCheckedChange = { enabled ->
                                changing = true
                                scope.launch {
                                    try {
                                        repository.setEnabled(packageName, enabled)
                                        error = false
                                    } catch (cancel: CancellationException) {
                                        throw cancel
                                    } catch (_: Exception) {
                                        error = true
                                    } finally {
                                        changing = false
                                    }
                                }
                            },
                        )
                    }
                    if (!RinRepository.compatible(current.manifest, current.format)) {
                        Text(
                            stringResource(R.string.novel_source_incompatible),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (error) {
                        Text(
                            stringResource(R.string.store_activation_error),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}
