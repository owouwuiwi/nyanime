package eu.kanade.tachiyomi.ui.addon

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.LifecycleResumeEffect
import eu.kanade.tachiyomi.data.addon.AddonRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Declarative addon actions; labels and icons belong to the installed package. */
@Composable
fun AddonMoreActions() {
    val context = LocalContext.current
    val repository = remember(context) { AddonRepository.get(context) }
    val installed by repository.registry.state.collectAsState()
    val rin = remember(context) { eu.kanade.tachiyomi.data.rin.RinRepository.get(context) }
    val bundles by rin.installed.collectAsState()
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val scope = rememberCoroutineScope()
    LifecycleResumeEffect(repository) {
        repository.refreshInstalled()
        onPauseOrDispose { }
    }
    Column {
        bundles.filter {
            it.enabled &&
                it.manifest.medium == "addon" &&
                eu.kanade.tachiyomi.data.rin.RinRepository.compatible(it.manifest)
        }.forEach { record ->
            key(record.sha256) {
                val declaration = requireNotNull(record.manifest.addon)
                val icon by produceState<ImageBitmap?>(null, record.sha256) {
                    value =
                        withContext(Dispatchers.IO) {
                            eu.kanade.tachiyomi.data.rin.RinLoader.icon(
                                context,
                                record,
                            )?.toBitmap(72, 72)?.asImageBitmap()
                        }
                }
                Row(
                    Modifier.fillMaxWidth().clickable {
                        eu.kanade.tachiyomi.data.addon.RinAddonRuntime.open(context, record.manifest.packageName)
                    }
                        .heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Box(Modifier.size(24.dp)) { icon?.let { Image(it, null, Modifier.fillMaxSize()) } }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            declaration.text(declaration.actionLabel, locale),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            declaration.text(declaration.actionSummary, locale),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        installed.filter { it.usable && it.activation?.isMoreAction == true }.forEach { addon ->
            key(addon.packageName, addon.versionCode) {
                val icon by produceState<ImageBitmap?>(null, addon.packageName, addon.versionCode) {
                    value = withContext(Dispatchers.IO) {
                        runCatching {
                            context.packageManager.getResourcesForApplication(addon.packageName)
                                .getDrawable(addon.logo, null).toBitmap(72, 72).asImageBitmap()
                        }.getOrNull()
                    }
                }
                Row(
                    Modifier.fillMaxWidth().clickable { scope.launch { repository.open(addon.packageName, context) } }
                        .heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Box(Modifier.size(24.dp)) { icon?.let { Image(it, null, Modifier.fillMaxSize()) } }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(requireNotNull(addon.actionLabel), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            requireNotNull(addon.actionSummary),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
