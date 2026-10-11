package eu.kanade.presentation.workinfo

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import eu.kanade.tachiyomi.data.workinfo.WorkCredit
import eu.kanade.tachiyomi.data.workinfo.WorkInfoRepository
import eu.kanade.tachiyomi.data.workinfo.WorkKind
import eu.kanade.tachiyomi.data.workinfo.WorkProvider
import eu.kanade.tachiyomi.data.workinfo.WorkReference
import kotlinx.coroutines.CancellationException
import tachiyomi.presentation.core.components.material.Scaffold
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

internal class WorkCreditsScreen(
    private val provider: String,
    private val id: String,
    private val kind: String,
    private val cast: Boolean,
    private val incognito: Boolean,
) : Screen() {
    @Composable
    override fun Content() {
        val reference = remember { WorkReference(WorkProvider.valueOf(provider), id, WorkKind.valueOf(kind)) }
        val repository = remember { Injekt.get<WorkInfoRepository>() }
        val enabled = workInformationEnabled()
        val policy = rememberWorkInfoPolicy(privateSession = incognito)
        val navigator = LocalNavigator.currentOrThrow
        var credits by remember(policy.language) { mutableStateOf<List<WorkCredit>>(emptyList()) }
        var loadedPage by remember(policy.language) { mutableIntStateOf(0) }
        var page by remember(policy.language) { mutableIntStateOf(1) }
        var more by remember(policy.language) { mutableStateOf(false) }
        var loading by remember { mutableStateOf(true) }
        var failed by remember { mutableStateOf(false) }
        var retry by remember { mutableIntStateOf(0) }
        LaunchedEffect(reference, policy, enabled, page, retry) {
            if (!enabled) {
                credits = emptyList()
                loading = false
                return@LaunchedEffect
            }
            loading = true
            failed = false
            try {
                val result = repository.credits(
                    reference,
                    cast,
                    page,
                    policy.language,
                    policy.network,
                    !policy.incognito,
                )
                if (page ==
                    1
                ) {
                    credits = result.credits
                } else if (page >
                    loadedPage
                ) {
                    credits = (credits + result.credits).distinct()
                }
                loadedPage = page
                more = result.hasNext
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
                title = stringResource(if (cast) R.string.work_info_cast else R.string.work_info_creators),
                navigateUp = navigator::pop,
                scrollBehavior = behavior,
            )
        }) { padding ->
            LazyColumn(Modifier.padding(padding).padding(horizontal = 22.dp)) {
                itemsIndexed(credits, key = { index, _ ->
                    index
                }) { _, credit -> WorkCreditRow(credit, policy.incognito) }
                if (loading) item { WorkInfoSkeleton() }
                if (!loading && !failed && credits.isEmpty()) {
                    item { Text(stringResource(R.string.work_info_no_credits), Modifier.padding(vertical = 16.dp)) }
                }
                if (failed) {
                    item {
                        WorkInfoNotice(
                            stringResource(
                                if (policy.network) R.string.work_info_error else R.string.work_info_offline,
                            ),
                        ) {
                            retry++
                        }
                    }
                }
                if (more &&
                    !loading &&
                    !failed
                ) {
                    item {
                        TextButton(onClick = {
                            page = loadedPage + 1
                        }) { Text(stringResource(R.string.work_info_more)) }
                    }
                }
            }
        }
    }
}
