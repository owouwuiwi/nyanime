package eu.kanade.tachiyomi.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.rin.RinRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Installed TV bundles stay manageable even when disabled or their factory fails. */
class TvExtensionScreen(private val packageName: String) : Screen() {
    @Composable override fun Content() {
        val context = LocalContext.current
        val repository = remember { RinRepository.get(context) }
        val installed by repository.installed.collectAsState()
        val record = installed.firstOrNull { it.manifest.packageName == packageName && it.manifest.medium == "tv" }
        val navigator = LocalNavigator.current
        val scope = rememberCoroutineScope()
        var changing by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf(false) }
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                IconButton(onClick = {
                    navigator?.pop()
                }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.tv_done)) }
                Icon(Icons.Outlined.LiveTv, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                Text(
                    record?.manifest?.name ?: stringResource(R.string.tv_title),
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    stringResource(R.string.tv_source_description),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                record?.let {
                    Text(it.manifest.versionName, style = MaterialTheme.typography.labelLarge)
                    eu.kanade.tachiyomi.ui.addon.AddonCompatibilityNotice(it.manifest.hostVersions)
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(if (it.enabled) R.string.store_active else R.string.store_inactive),
                                Modifier.weight(1f),
                            )
                            Switch(it.enabled, { enabled ->
                                changing = true
                                scope.launch {
                                    try {
                                        repository.setEnabled(packageName, enabled)
                                        error = false
                                    } catch (
                                        cancelled: CancellationException,
                                    ) {
                                        throw cancelled
                                    } catch (
                                        _: Exception,
                                    ) {
                                        error = true
                                    } finally {
                                        changing = false
                                    }
                                }
                            }, enabled = !changing && (it.enabled || RinRepository.compatible(it.manifest)))
                        }
                    }
                }
                if (record == null) Text(stringResource(R.string.tv_missing_source))
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
