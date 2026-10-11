package it.lorelore.nyanime.exchange

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import eu.kanade.presentation.browse.store.RinImportScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.extension.ExtensionPackageArchive
import eu.kanade.tachiyomi.extension.ExtensionPackageInspector
import eu.kanade.tachiyomi.util.storage.getUriCompat
import it.lorelore.nyanime.studio.SourceStudioRepository
import it.lorelore.nyanime.studio.StudioDocuments
import it.lorelore.nyanime.studio.StudioEditorScreen
import it.lorelore.nyanime.studio.StudioSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Received bytes stay inert. Reuse native importers after the person chooses to inspect them. */
internal class SourceExchangeImportScreen(private val incoming: ExchangeFile) : Screen() {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        var source by remember { mutableStateOf<StudioSource?>(null) }
        var system by remember { mutableStateOf<ExtensionPackageArchive.Inspected?>(null) }
        var failed by remember { mutableStateOf(false) }
        var loading by remember { mutableStateOf(true) }
        var collision by remember { mutableStateOf(false) }
        LaunchedEffect(incoming) {
            try {
                val file = withContext(Dispatchers.IO) { ExchangeStorage.received(context, incoming) }
                val archive = withContext(Dispatchers.IO) {
                    file.inputStream().use {
                        it.read() == 'P'.code &&
                            it.read() == 'K'.code
                    }
                }
                if (archive) {
                    val apk =
                        withContext(Dispatchers.IO) {
                            if (ExtensionPackageArchive.isApk(
                                    file,
                                )
                            ) {
                                ExtensionPackageArchive.inspect(context, file)
                            } else {
                                null
                            }
                        }
                    if (apk?.requiresAndroid == true) {
                        require(
                            apk.manifest.hostVersions.supports(eu.kanade.tachiyomi.BuildConfig.VERSION_CODE.toLong()),
                        )
                        system = apk
                    } else {
                        navigator.replace(RinImportScreen(file.getUriCompat(context).toString()))
                    }
                } else {
                    source = withContext(Dispatchers.IO) {
                        require(file.length() <= StudioDocuments.MAX_DOCUMENT_BYTES)
                        StudioDocuments.decode(file.readText())
                    }
                    val repository = SourceStudioRepository.get(context)
                    repository.awaitReady()
                    collision = repository.records.value.any { it.draft.uuid == source?.uuid }
                }
            } catch (
                cancel: CancellationException,
            ) {
                throw cancel
            } catch (_: Exception) {
                failed = true
            } finally {
                loading =
                    false
            }
        }
        fun import(copy: Boolean) {
            val definition = source ?: return
            if (loading) return
            loading = true
            scope.launch {
                try {
                    val uuid = SourceStudioRepository.get(
                        context,
                    ).importDocument(StudioDocuments.encode(definition), duplicateExisting = copy)
                    navigator.replace(StudioEditorScreen(uuid))
                } catch (
                    cancel: CancellationException,
                ) {
                    throw cancel
                } catch (_: Exception) {
                    failed = true
                } finally {
                    loading =
                        false
                }
            }
        }
        Scaffold(topBar = {
            TopAppBar(title = {
                Text(stringResource(R.string.exchange_inspect))
            }, navigationIcon = { TextButton({ navigator.pop() }) { Text(stringResource(R.string.action_close)) } })
        }) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                if (loading) CircularProgressIndicator()
                if (failed) Text(stringResource(R.string.exchange_error_file), color = MaterialTheme.colorScheme.error)
                source?.let { definition ->
                    Text(definition.name, style = MaterialTheme.typography.headlineMedium)
                    Text(definition.baseUrl, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(
                            R.string.exchange_definition_summary,
                            definition.flows.size,
                            definition.flows.values.sumOf {
                                it.nodes.size
                            },
                        ),
                    )
                    Text(
                        stringResource(R.string.exchange_draft_hint),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (collision) {
                        Text(stringResource(R.string.studio_import_existing_body))
                        Button({
                            import(true)
                        }, Modifier.fillMaxWidth(), enabled = !loading) {
                            Text(stringResource(R.string.studio_import_copy))
                        }
                        OutlinedButton({
                            import(false)
                        }, Modifier.fillMaxWidth(), enabled = !loading) {
                            Text(stringResource(R.string.studio_import_update))
                        }
                    } else {
                        Button({
                            import(false)
                        }, Modifier.fillMaxWidth(), enabled = !loading) {
                            Text(stringResource(R.string.exchange_import_draft))
                        }
                    }
                }
                system?.let { inspected ->
                    Text(inspected.manifest.name, style = MaterialTheme.typography.headlineMedium)
                    Text(inspected.manifest.versionName)
                    Text(stringResource(R.string.rin_verified_signature))
                    Text(
                        ExtensionPackageInspector.inspect(inspected.info) {
                            0
                        }.signers.joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(stringResource(R.string.exchange_android_hint))
                    Button({
                        runCatching {
                            val file = ExchangeStorage.received(context, incoming)
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                ).setDataAndType(
                                    file.getUriCompat(context),
                                    "application/vnd.android.package-archive",
                                ).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                            )
                        }.onFailure { failed = true }
                    }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.exchange_android_install)) }
                }
            }
        }
    }
}
