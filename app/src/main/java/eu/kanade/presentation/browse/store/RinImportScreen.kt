package eu.kanade.presentation.browse.store

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.extensionstore.ExtensionStoreRepository
import eu.kanade.tachiyomi.data.rin.RinImportException
import eu.kanade.tachiyomi.data.rin.RinImportFailure
import eu.kanade.tachiyomi.data.rin.RinLocalImport
import eu.kanade.tachiyomi.data.rin.RinSessions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import tachiyomi.presentation.core.components.material.Scaffold

private class RinImportModel(private val repository: ExtensionStoreRepository, private val uri: String) :
    StateScreenModel<RinImportModel.State>(State.Loading) {
    sealed interface State {
        data object Loading : State
        data class Preview(val candidate: RinLocalImport, val installing: Boolean = false) : State
        data class Failed(val reason: RinImportFailure) : State
        data class Done(val name: String) : State
    }

    init {
        screenModelScope.launch {
            try {
                val candidate = repository.rin.prepareLocal(Uri.parse(uri))
                mutableState.value = if (candidate.previous?.sha256 == candidate.record.sha256) {
                    State.Done(candidate.record.manifest.name)
                } else {
                    State.Preview(candidate)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: RinImportException) {
                mutableState.value = State.Failed(error.reason)
            } catch (_: Exception) {
                mutableState.value = State.Failed(RinImportFailure.INVALID)
            }
        }
    }

    fun install() {
        val preview = state.value as? State.Preview ?: return
        if (preview.installing) return
        if (RinSessions.busy.value || eu.kanade.tachiyomi.data.addon.RinAddonRuntime.busy.value) {
            mutableState.value = State.Failed(RinImportFailure.BUSY)
            return
        }
        mutableState.value = preview.copy(installing = true)
        val job = repository.installLocal(preview.candidate)
        screenModelScope.launch {
            job.join()
            val record = preview.candidate.record
            mutableState.value =
                if (repository.rin.storage.record(record.manifest.packageName)?.sha256 == record.sha256) {
                    State.Done(record.manifest.name)
                } else {
                    State.Failed(RinImportFailure.CHANGED)
                }
        }
    }
}

class RinImportScreen(private val uri: String) : Screen() {
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val model = rememberScreenModel { RinImportModel(ExtensionStoreRepository.get(context), uri) }
        val state by model.state.collectAsState()
        Scaffold(topBar = { behavior ->
            AppBar(title = stringResource(R.string.rin_load), navigateUp = navigator::pop, scrollBehavior = behavior)
        }) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
            ) {
                Icon(
                    if (state is RinImportModel.State.Done) Icons.Outlined.CheckCircle else Icons.Outlined.Extension,
                    null,
                    Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                when (val current = state) {
                    RinImportModel.State.Loading -> {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.rin_verifying))
                    }
                    is RinImportModel.State.Preview -> {
                        val record = current.candidate.record
                        Text(record.manifest.name, style = MaterialTheme.typography.headlineSmall)
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shape = MaterialTheme.shapes.large,
                        ) {
                            Column(
                                Modifier.fillMaxWidth().padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(stringResource(R.string.rin_version, record.manifest.versionName))
                                current.candidate.previous?.let {
                                    Text(stringResource(R.string.rin_previous_version, it.manifest.versionName))
                                }
                                Text(
                                    stringResource(R.string.rin_verified_signature),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                                Text(
                                    record.signer.chunked(8).joinToString(" "),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        Text(stringResource(R.string.rin_import_hint), style = MaterialTheme.typography.bodyMedium)
                        Button(
                            onClick = model::install,
                            enabled = !current.installing,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (current.installing) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Text(
                                    stringResource(
                                        if (current.candidate.previous ==
                                            null
                                        ) {
                                            R.string.rin_install
                                        } else {
                                            R.string.rin_update
                                        },
                                    ),
                                )
                            }
                        }
                    }
                    is RinImportModel.State.Failed -> {
                        val message = when (current.reason) {
                            RinImportFailure.INCOMPATIBLE -> R.string.rin_incompatible
                            RinImportFailure.LEGACY -> R.string.rin_legacy
                            RinImportFailure.CHANGED -> R.string.rin_changed
                            RinImportFailure.BUSY -> R.string.rin_busy
                            RinImportFailure.INVALID -> R.string.rin_invalid
                        }
                        Text(stringResource(message))
                        Button(onClick = { navigator.pop() }) { Text(stringResource(R.string.action_close)) }
                    }
                    is RinImportModel.State.Done -> {
                        Text(current.name, style = MaterialTheme.typography.headlineSmall)
                        Text(stringResource(R.string.rin_import_done))
                        Button(onClick = { navigator.pop() }) { Text(stringResource(R.string.catalogue_open_store)) }
                    }
                }
            }
        }
    }
}
