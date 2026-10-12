package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.UpdateChannelChoice
import eu.kanade.presentation.more.settings.widget.SwitchPreferenceWidget
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.updater.AppUpdateChecker
import eu.kanade.tachiyomi.ui.more.NewUpdateScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import tachiyomi.domain.release.interactor.GetApplicationRelease
import tachiyomi.domain.release.model.UpdateChannel
import tachiyomi.domain.release.service.AppUpdatePreferences
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object SettingsAppUpdatesScreen : Screen() {
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val preferences = remember { AppUpdatePreferences(Injekt.get()) }
        val ui = remember { Injekt.get<UiPreferences>() }
        val selection by preferences.selection.collectAsState()
        val inApp by ui.inAppUpdateInstallation().collectAsState()
        val selected = UpdateChannel.fromKey(selection)
        var checking by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf<Int?>(null) }
        var switchedToRecommended by remember { mutableStateOf(false) }
        Scaffold(topBar = { scrollBehavior ->
            AppBar(
                title = stringResource(R.string.app_update_settings_title),
                navigateUp = navigator::pop,
                scrollBehavior = scrollBehavior,
            )
        }) { padding ->
            ScrollbarLazyColumn(contentPadding = padding) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        Text(
                            stringResource(R.string.app_update_current_version, BuildConfig.VERSION_NAME),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            stringResource(R.string.app_update_settings_description),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        UpdateChannelChoice(selected) {
                            switchedToRecommended = selected == UpdateChannel.INCLUDING_PREVIEWS &&
                                it == UpdateChannel.RECOMMENDED
                            preferences.confirm(it)
                            message = R.string.app_update_saved
                        }
                        if (switchedToRecommended) {
                            Text(
                                stringResource(R.string.app_update_wait_recommended),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                item {
                    SwitchPreferenceWidget(
                        title = stringResource(R.string.update_in_app),
                        subtitle = stringResource(R.string.update_in_app_hint),
                        checked = inApp,
                        onCheckedChanged = ui.inAppUpdateInstallation()::set,
                    )
                }
                item {
                    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            enabled = !checking,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                            shape = RoundedCornerShape(16.dp),
                            onClick = {
                                checking = true
                                message = null
                                val requestedChannel = preferences.channel()
                                scope.launch {
                                    try {
                                        val result = AppUpdateChecker().checkForUpdate(context, forceCheck = true)
                                        if (preferences.channel() != requestedChannel) return@launch
                                        when (result) {
                                            is GetApplicationRelease.Result.NewUpdate -> navigator.push(
                                                NewUpdateScreen(
                                                    result.release.version,
                                                    result.release.info,
                                                    result.release.releaseLink,
                                                    result.release.downloadLink,
                                                ),
                                            )
                                            else -> message = R.string.app_update_no_new
                                        }
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (_: Exception) {
                                        if (preferences.channel() == requestedChannel) {
                                            message = R.string.app_update_check_error
                                        }
                                    } finally {
                                        checking = false
                                    }
                                }
                            },
                        ) {
                            Text(
                                stringResource(
                                    if (checking) R.string.app_update_checking else R.string.app_update_check,
                                ),
                            )
                        }
                        message?.let {
                            Text(
                                stringResource(it),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
