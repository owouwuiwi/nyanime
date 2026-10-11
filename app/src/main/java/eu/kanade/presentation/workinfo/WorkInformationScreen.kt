package eu.kanade.presentation.workinfo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.workinfo.WorkInfoPreferences
import eu.kanade.tachiyomi.data.workinfo.WorkInfoRepository
import eu.kanade.tachiyomi.data.workinfo.WorkInformation
import eu.kanade.tachiyomi.data.workinfo.WorkKind
import eu.kanade.tachiyomi.data.workinfo.WorkProvider
import eu.kanade.tachiyomi.data.workinfo.WorkReference
import eu.kanade.tachiyomi.ui.browse.anime.source.globalsearch.GlobalAnimeSearchScreen
import eu.kanade.tachiyomi.ui.browse.manga.source.globalsearch.GlobalMangaSearchScreen
import kotlinx.coroutines.CancellationException
import tachiyomi.presentation.core.components.material.Scaffold
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

internal class WorkInformationScreen(
    private val provider: String,
    private val id: String,
    private val kind: String,
    private val incognito: Boolean,
) : Screen() {
    @Composable
    override fun Content() {
        val reference = remember { WorkReference(WorkProvider.valueOf(provider), id, WorkKind.valueOf(kind)) }
        val repository = remember { Injekt.get<WorkInfoRepository>() }
        val enabled = workInformationEnabled()
        val policy = rememberWorkInfoPolicy(privateSession = incognito)
        val navigator = LocalNavigator.currentOrThrow
        var information by remember { mutableStateOf<WorkInformation?>(null) }
        var loading by remember { mutableStateOf(true) }
        var failed by remember { mutableStateOf(false) }
        var retry by remember { mutableIntStateOf(0) }
        LaunchedEffect(reference, policy, enabled, retry) {
            if (!enabled) {
                information = null
                loading = false
                return@LaunchedEffect
            }
            loading = true
            failed = false
            try {
                information = repository.cachedInformation(reference, policy.language)
                information = repository.information(reference, policy.language, policy.network, !policy.incognito)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed = true
            } finally {
                loading = false
            }
        }
        Scaffold(topBar = { behavior ->
            AppBar(
                title = stringResource(R.string.work_info_title),
                navigateUp = navigator::pop,
                scrollBehavior = behavior,
            )
        }) { padding ->
            LazyColumn(Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    Column(
                        Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        if (loading && information == null) WorkInfoSkeleton()
                        if (failed) {
                            WorkInfoNotice(
                                stringResource(
                                    if (policy.network) R.string.work_info_error else R.string.work_info_offline,
                                ),
                            ) {
                                retry++
                            }
                        }
                        information?.let { info ->
                            Text(info.work.title, style = MaterialTheme.typography.headlineMedium)
                            FilledTonalButton(
                                onClick = {
                                    navigator.push(
                                        if (reference.kind ==
                                            WorkKind.MANGA
                                        ) {
                                            GlobalMangaSearchScreen(info.work.title)
                                        } else {
                                            GlobalAnimeSearchScreen(info.work.title)
                                        },
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = policy.network,
                            ) { Text(stringResource(R.string.work_info_find_sources)) }
                            WorkInformationBody(info, policy.incognito, showSynopsis = true, language = policy.language)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun workInformationEnabled(): Boolean {
    val preference = remember { Injekt.get<WorkInfoPreferences>().consent() }
    val consent by preference.changes().collectAsState(preference.get())
    return consent == WorkInfoPreferences.ENABLED
}
