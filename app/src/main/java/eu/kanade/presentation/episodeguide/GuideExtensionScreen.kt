package eu.kanade.presentation.episodeguide

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import eu.kanade.presentation.browse.store.ExtensionStoreScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.extensionstore.StoreMedium
import eu.kanade.tachiyomi.data.rin.RinRepository
import kotlinx.coroutines.launch

internal class GuideStoreScreen : Screen() {
    @Composable
    override fun Content() {
        var medium by remember { mutableStateOf(StoreMedium.GUIDE) }
        val navigator = LocalNavigator.current
        ExtensionStoreScreen(medium, onMedium = { medium = it }, onBack = { navigator?.pop() })
    }
}

class GuideExtensionScreen(private val packageName: String) : Screen() {
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val repository = remember { RinRepository.get(context) }
        val installed by repository.installed.collectAsState()
        val record = installed.firstOrNull { it.manifest.packageName == packageName }
        val navigator = LocalNavigator.current
        val scope = rememberCoroutineScope()
        var error by remember { mutableStateOf(false) }
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.safeDrawingPadding().verticalScroll(
                    androidx.compose.foundation.rememberScrollState(),
                ).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                TextButton(onClick = { navigator?.pop() }) { Text(stringResource(R.string.guide_back)) }
                Text(record?.manifest?.name.orEmpty(), style = MaterialTheme.typography.headlineMedium)
                record?.let {
                    val descriptions = it.manifest.guide?.descriptions.orEmpty()
                    val locale = context.resources.configuration.locales[0]
                    Text(
                        descriptions[locale.toLanguageTag()] ?: descriptions[locale.language]
                            ?: descriptions["en"].orEmpty(),
                    )
                    if ("episode-guides.v1" in
                        it.manifest.guide?.capabilities.orEmpty()
                    ) {
                        EpisodeGuidePlayerPreferences(null)
                    }
                    Row {
                        Text(stringResource(R.string.store_active), Modifier.weight(1f))
                        Switch(it.enabled, { enabled ->
                            scope.launch {
                                try {
                                    repository.setEnabled(packageName, enabled)
                                    error = false
                                } catch (
                                    cancel: kotlinx.coroutines.CancellationException,
                                ) {
                                    throw cancel
                                } catch (_: Exception) {
                                    error = true
                                }
                            }
                        })
                    }
                    if (error) Text(stringResource(R.string.guide_unavailable), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
