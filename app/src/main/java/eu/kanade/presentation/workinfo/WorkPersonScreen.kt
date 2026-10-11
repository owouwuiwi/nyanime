package eu.kanade.presentation.workinfo

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.MaterialTheme
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
import eu.kanade.tachiyomi.data.workinfo.WorkInfoRepository
import eu.kanade.tachiyomi.data.workinfo.WorkPerson
import eu.kanade.tachiyomi.data.workinfo.WorkPersonReference
import eu.kanade.tachiyomi.data.workinfo.WorkProvider
import eu.kanade.tachiyomi.data.workinfo.WorkRelation
import kotlinx.coroutines.CancellationException
import tachiyomi.presentation.core.components.material.Scaffold
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Locale

internal class WorkPersonScreen(
    private val provider: String,
    private val id: String,
    private val incognito: Boolean,
) : Screen() {
    @Composable
    override fun Content() {
        val reference = remember { WorkPersonReference(WorkProvider.valueOf(provider), id) }
        val repository = remember { Injekt.get<WorkInfoRepository>() }
        val enabled = workInformationEnabled()
        val policy = rememberWorkInfoPolicy(privateSession = incognito)
        val navigator = LocalNavigator.currentOrThrow
        var person by remember(policy.language) { mutableStateOf<WorkPerson?>(null) }
        var works by remember(policy.language) { mutableStateOf<List<WorkRelation>>(emptyList()) }
        var loadedPage by remember(policy.language) { mutableIntStateOf(0) }
        var requestedPage by remember(policy.language) { mutableIntStateOf(1) }
        var more by remember(policy.language) { mutableStateOf(false) }
        var loading by remember { mutableStateOf(true) }
        var failed by remember { mutableStateOf(false) }
        var retry by remember { mutableIntStateOf(0) }
        LaunchedEffect(reference, policy, enabled, requestedPage, retry) {
            if (!enabled) {
                person = null
                works = emptyList()
                loading = false
                return@LaunchedEffect
            }
            loading = true
            failed = false
            try {
                repository.cachedPerson(reference, policy.language)?.let { person = it }
                person = repository.person(reference, policy.language, policy.network, !policy.incognito)
                val result = repository.works(
                    reference,
                    requestedPage,
                    policy.language,
                    policy.network,
                    !policy.incognito,
                )
                if (requestedPage ==
                    1
                ) {
                    works = result.works
                } else if (requestedPage >
                    loadedPage
                ) {
                    works = (works + result.works).distinctBy { it.work.reference to it.relation }
                }
                loadedPage = requestedPage
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
            AppBar(title = person?.name.orEmpty(), navigateUp = navigator::pop, scrollBehavior = behavior)
        }) { padding ->
            LazyColumn(Modifier.padding(padding).padding(horizontal = 22.dp)) {
                person?.let { value ->
                    item {
                        Column(Modifier.padding(vertical = 16.dp)) {
                            WorkPanel(Icons.Outlined.Person, stringResource(R.string.work_info_biography)) {
                                if (value.biography.isNullOrBlank()) {
                                    Text(
                                        stringResource(R.string.work_info_no_biography),
                                    )
                                } else {
                                    ExpandableWorkText(value.biography)
                                    Text(
                                        stringResource(
                                            R.string.work_info_language_note,
                                            value.reference.provider.label,
                                            Locale(value.language).displayLanguage,
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    item {
                        Text(
                            stringResource(R.string.work_info_filmography),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                }
                itemsIndexed(works, key = { index, item ->
                    "$index:${item.work.reference}:${item.relation}"
                }) { _, relation ->
                    WorkSummaryRow(relation.work, workLabel(relation.relation)) {
                        val ref = relation.work.reference
                        navigator.push(
                            WorkInformationScreen(ref.provider.name, ref.id, ref.kind.name, policy.incognito),
                        )
                    }
                }
                if (loading) item { WorkInfoSkeleton() }
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
                if (!loading &&
                    !failed &&
                    works.isEmpty()
                ) {
                    item { Text(stringResource(R.string.work_info_no_works), Modifier.padding(vertical = 16.dp)) }
                }
                if (more && !loading && !failed) {
                    item {
                        TextButton(onClick = {
                            requestedPage = loadedPage + 1
                        }) { Text(stringResource(R.string.work_info_more)) }
                    }
                }
            }
        }
    }
}
