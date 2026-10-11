package eu.kanade.presentation.workinfo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.workinfo.WorkInfoRepository
import eu.kanade.tachiyomi.data.workinfo.WorkResolution
import kotlinx.coroutines.CancellationException
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkAssociationSheet(state: WorkInfoState) {
    val repository = remember { Injekt.get<WorkInfoRepository>() }
    val keyboard = LocalSoftwareKeyboardController.current
    var query by remember { mutableStateOf(state.identity.title) }
    var submitted by remember { mutableStateOf(query) }
    var revision by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf(WorkResolution()) }
    LaunchedEffect(submitted, revision, state.network, state.language, state.incognito) {
        if (!state.network || submitted.isBlank()) return@LaunchedEffect
        loading = true
        try {
            result = repository.resolve(
                state.identity.copy(title = submitted, aliases = emptyList(), references = emptyList()),
                binding = null,
                language = state.language,
                network = true,
                persist = !state.incognito,
                forceSearch = true,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            result = WorkResolution(partialFailure = true)
        } finally {
            loading = false
        }
    }
    fun search() {
        if (query.isBlank()) return
        submitted = query.trim()
        revision++
        keyboard?.hide()
    }
    ModalBottomSheet(onDismissRequest = {
        state.choosing = false
    }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().imePadding().padding(horizontal = 22.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.work_info_choose), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.work_info_choose_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.incognito) {
                Text(
                    stringResource(R.string.work_info_incognito),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it.take(256) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.work_info_query)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search() }),
            )
            TextButton(onClick = ::search, enabled = state.network && !loading && query.isNotBlank()) {
                Text(stringResource(R.string.work_info_search))
            }
            LazyColumn(Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.46f).dp)) {
                if (loading) item { WorkInfoSkeleton() }
                if (!state.network) item { WorkInfoNotice(stringResource(R.string.work_info_offline)) }
                if (result.partialFailure) item { WorkInfoNotice(stringResource(R.string.work_info_partial)) }
                if (!loading && result.candidates.isEmpty() && state.network) {
                    item {
                        Text(stringResource(R.string.work_info_empty), Modifier.padding(vertical = 16.dp))
                    }
                }
                items(result.candidates, key = { it.reference.toString() }) { candidate ->
                    WorkSummaryRow(
                        candidate,
                        stringResource(R.string.work_info_current).takeIf {
                            candidate.reference == state.resolution.reference
                        },
                    ) { state.select(candidate.reference) }
                }
            }
            if (state.resolution.reference != null || state.sessionBinding?.reference != null) {
                TextButton(onClick = { state.select(null) }) { Text(stringResource(R.string.work_info_remove)) }
            }
        }
    }
}
