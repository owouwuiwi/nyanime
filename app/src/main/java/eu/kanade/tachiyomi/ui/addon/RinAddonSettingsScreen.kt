package eu.kanade.tachiyomi.ui.addon

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.addon.RinAddonRuntime
import eu.kanade.tachiyomi.data.rin.RinRepository
import kotlinx.coroutines.launch
import tachiyomi.presentation.core.components.material.Scaffold

class RinAddonSettingsScreen(private val packageName: String) : Screen() {
    @Composable override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val repository = remember(context) { RinRepository.get(context) }
        val records by repository.installed.collectAsState()
        val record = records.singleOrNull { it.manifest.packageName == packageName }
        val declaration = record?.manifest?.addon
        val locale = LocalConfiguration.current.locales[0]
        val scope = rememberCoroutineScope()
        var changing by remember { mutableStateOf(false) }
        var failed by remember { mutableStateOf(false) }
        Scaffold(topBar = {
            AppBar(title = record?.manifest?.name ?: stringResource(R.string.store_addon), navigateUp = navigator::pop)
        }) { padding ->
            Column(
                Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text(stringResource(R.string.addon_optional_action), style = MaterialTheme.typography.headlineSmall)
                declaration?.let { Text(it.text(it.descriptions, locale), style = MaterialTheme.typography.bodyLarge) }
                record?.let { AddonCompatibilityNotice(it.manifest.hostVersions) }
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(20.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.addon_enabled), style = MaterialTheme.typography.titleMedium)
                            declaration?.let {
                                Text(stringResource(R.string.addon_more_hint, it.text(it.actionLabel, locale)))
                            }
                        }
                        Switch(
                            record?.enabled == true,
                            enabled =
                            record != null && !changing && RinRepository.compatible(record.manifest),
                            onCheckedChange = { enabled ->
                                changing = true
                                failed = false
                                scope.launch {
                                    try {
                                        repository.setEnabled(packageName, enabled)
                                    } catch (
                                        cancel: kotlinx.coroutines.CancellationException,
                                    ) {
                                        throw cancel
                                    } catch (_: Exception) {
                                        failed =
                                            true
                                    } finally {
                                        changing = false
                                    }
                                }
                            },
                        )
                    }
                }
                if (failed) {
                    Text(
                        stringResource(R.string.store_activation_error),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Button(
                    onClick = { RinAddonRuntime.open(context, packageName) },
                    enabled =
                    record?.enabled == true && RinRepository.compatible(record.manifest),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.addon_open))
                }
                declaration?.sourceUrl?.let { url ->
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)),
                            )
                        }
                    }) {
                        Text(stringResource(R.string.addon_sources))
                    }
                }
            }
        }
    }
}
