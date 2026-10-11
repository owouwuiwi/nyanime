package eu.kanade.tachiyomi.ui.addon

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.LifecycleResumeEffect
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.addon.AddonRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.presentation.core.components.material.Scaffold

class AddonSettingsScreen(private val packageName: String) : Screen() {
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val repository = remember(context) { AddonRepository.get(context) }
        val installed by repository.registry.state.collectAsState()
        val addon = installed.singleOrNull { it.packageName == packageName }
        val scope = rememberCoroutineScope()
        LifecycleResumeEffect(repository) {
            repository.refreshInstalled()
            onPauseOrDispose { }
        }
        val logo by produceState<ImageBitmap?>(null, addon?.version, addon?.logo) {
            value = withContext(Dispatchers.IO) {
                addon?.let { item ->
                    runCatching {
                        val image = context.packageManager.getResourcesForApplication(
                            item.packageName,
                        ).getDrawable(item.logo, null)
                        val height = (
                            600f *
                                image.intrinsicHeight /
                                image.intrinsicWidth.coerceAtLeast(
                                    1,
                                )
                            ).toInt().coerceIn(12, 600)
                        image.toBitmap(600, height).asImageBitmap()
                    }.getOrNull()
                }
            }
        }
        Scaffold(topBar = {
            AppBar(title = addon?.label ?: stringResource(R.string.store_addon), navigateUp = navigator::pop)
        }) { padding ->
            Column(
                Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                logo?.let {
                    Image(it, addon?.label, Modifier.fillMaxWidth().height(76.dp), contentScale = ContentScale.Fit)
                }
                Text(
                    stringResource(
                        if (addon?.activation?.isMoreAction ==
                            true
                        ) {
                            R.string.addon_optional_action
                        } else {
                            R.string.addon_optional_mode
                        },
                    ),
                    style = MaterialTheme.typography.titleLarge,
                )
                addon?.description?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                addon?.let { AddonCompatibilityNotice(it.hostVersions) }
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(R.string.addon_enabled), style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (addon?.compatible == true && addon.activation?.isMoreAction == true) {
                                    stringResource(R.string.addon_more_hint, requireNotNull(addon.actionLabel))
                                } else {
                                    stringResource(
                                        if (addon?.compatible ==
                                            true
                                        ) {
                                            R.string.addon_hold_hint
                                        } else {
                                            R.string.addon_incompatible
                                        },
                                    )
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Switch(
                            checked = addon?.usable == true,
                            enabled = addon?.compatible == true && addon.trusted,
                            onCheckedChange = { selected ->
                                scope.launch { repository.registry.enable(packageName, selected) }
                            },
                        )
                    }
                }
                if (addon?.sharesAppearance == true) {
                    Text(
                        stringResource(R.string.addon_appearance_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(
                    onClick = { scope.launch { repository.open(packageName, context) } },
                    enabled = addon?.usable == true,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.addon_open)) }
                addon?.sourceUrl?.let { url ->
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)),
                            )
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.addon_sources)) }
                }
            }
        }
    }
}
