package it.lorelore.nyanime.studio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R
import it.lorelore.nyanime.studio.document.StudioDocumentComponent
import it.lorelore.nyanime.studio.document.StudioDocumentComponents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Immutable, caller-validated identity: viewing this request does not grant any access. */
internal data class SourceCapabilityRequest(
    val name: String,
    val key: String,
    val documentScope: String,
    val documentResolvers: Set<String>,
    val telegramReview: (suspend () -> TelegramSourceReview)?,
    val validate: suspend () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SourceCapabilityConsent(
    request: SourceCapabilityRequest,
    preview: Boolean = false,
    dismiss: () -> Unit,
    approved: suspend (TelegramSourceActivation?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var components by remember(request.key) { mutableStateOf<List<StudioDocumentComponent>?>(null) }
    var missing by remember(request.key) { mutableStateOf<List<String>>(emptyList()) }
    var failed by remember(request.key) { mutableStateOf(false) }
    var loading by remember(request.key) { mutableStateOf(true) }
    var busy by remember(request.key) { mutableStateOf(false) }
    var attempt by remember(request.key) { mutableStateOf(0) }
    LaunchedEffect(request.key, attempt) {
        loading = true
        failed = false
        try {
            request.validate()
            if (request.documentResolvers.isEmpty()) {
                components = emptyList()
            } else {
                val installed = StudioDocumentComponents.get(context).available().groupBy { it.id }
                missing = request.documentResolvers.filter { installed[it]?.size != 1 }
                components = request.documentResolvers.mapNotNull { installed[it]?.singleOrNull() }
            }
            request.validate()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            failed = true
            components = null
        } finally {
            loading = false
        }
    }
    val ready = !loading && !failed && missing.isEmpty() && components != null
    val content: @Composable () -> Unit = {
        if (request.documentResolvers.isNotEmpty()) {
            Text(stringResource(R.string.studio_access_documents), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.studio_access_documents_scope),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            components.orEmpty().forEach { component ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(component.label, style = MaterialTheme.typography.titleSmall)
                        Text(component.record.manifest.name, style = MaterialTheme.typography.bodySmall)
                        Text(component.hosts.sorted().joinToString("\n"), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            missing.forEach { id ->
                Text(
                    stringResource(R.string.studio_access_component_missing, id),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (loading) CircularProgressIndicator()
        if (failed || missing.isNotEmpty()) {
            if (failed) Text(stringResource(R.string.studio_access_failed), color = MaterialTheme.colorScheme.error)
            TextButton({ attempt++ }, enabled = !busy) { Text(stringResource(R.string.studio_telegram_retry)) }
        }
    }
    val grant: suspend () -> Unit = {
        check(ready)
        request.validate()
        val snapshot = requireNotNull(components)
        if (snapshot.isNotEmpty()) {
            val registry = StudioDocumentComponents.get(context)
            snapshot.forEach { registry.grant(request.documentScope, it) }
        }
        request.validate()
    }
    val telegram = request.telegramReview
    if (telegram != null) {
        TelegramSourceConsent(
            request.name, request.key, preview, telegram, dismiss,
            additionalContent = content, canApprove = ready, beforeApprove = grant,
            approved = { approved(it) },
        )
    } else {
        ModalBottomSheet(onDismissRequest = { if (!busy) dismiss() }) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * .84f).dp)
                    .imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(R.string.studio_access_title), style = MaterialTheme.typography.headlineSmall)
                Text(request.name, style = MaterialTheme.typography.titleMedium)
                content()
                Button({
                    if (!busy) {
                        busy = true
                        scope.launch {
                            try {
                                grant()
                                approved(null)
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (_: Exception) {
                                failed = true
                            } finally {
                                busy = false
                            }
                        }
                    }
                }, Modifier.fillMaxWidth(), enabled = ready && !busy) {
                    Text(stringResource(R.string.studio_access_allow))
                }
                if (busy) CircularProgressIndicator()
                TextButton(dismiss, Modifier.fillMaxWidth(), enabled = !busy) {
                    Text(stringResource(R.string.studio_cancel))
                }
            }
        }
    }
}
