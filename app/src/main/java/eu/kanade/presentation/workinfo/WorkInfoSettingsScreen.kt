package eu.kanade.presentation.workinfo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.settings.widget.SwitchPreferenceWidget
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.workinfo.WorkInfoPreferences
import tachiyomi.presentation.core.components.material.Scaffold
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class WorkInfoSettingsScreen : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val uri = LocalUriHandler.current
        val preference = remember { Injekt.get<WorkInfoPreferences>().consent() }
        val consent by preference.changes().collectAsState(preference.get())
        Scaffold(topBar = { behavior ->
            AppBar(
                title = stringResource(R.string.work_info_title),
                navigateUp = navigator::pop,
                scrollBehavior = behavior,
            )
        }) { padding ->
            LazyColumn(Modifier.padding(padding)) {
                item {
                    SwitchPreferenceWidget(
                        title = stringResource(R.string.work_info_title),
                        subtitle = stringResource(R.string.work_info_setting_hint),
                        checked = consent == WorkInfoPreferences.ENABLED,
                        onCheckedChanged = {
                            preference.set(if (it) WorkInfoPreferences.ENABLED else WorkInfoPreferences.DISABLED)
                        },
                    )
                }
                item {
                    Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Text(stringResource(R.string.work_info_coverage), style = MaterialTheme.typography.bodyLarge)
                        WorkPanel(Icons.Outlined.Info, stringResource(R.string.work_info_attribution)) {
                            TextButton(onClick = {
                                uri.openUri("https://anilist.co")
                            }) { Text(stringResource(R.string.work_info_anilist_credit)) }
                            TextButton(onClick = {
                                uri.openUri("https://www.tvmaze.com/api#licensing")
                            }) { Text(stringResource(R.string.work_info_tvmaze_credit)) }
                            TextButton(onClick = {
                                uri.openUri("https://www.wikidata.org/wiki/Wikidata:Licensing")
                            }) { Text(stringResource(R.string.work_info_wikidata_credit)) }
                        }
                        Text(
                            stringResource(R.string.work_info_privacy),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
