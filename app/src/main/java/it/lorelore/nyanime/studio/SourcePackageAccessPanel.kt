package it.lorelore.nyanime.studio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.rin.RinLoader
import eu.kanade.tachiyomi.data.rin.RinRecord
import eu.kanade.tachiyomi.data.rin.RinRepository
import eu.kanade.tachiyomi.extension.ExtensionPackageConsent
import it.lorelore.nyanime.source.document.SourceDocumentBackedSource
import it.lorelore.nyanime.source.novel.NovelSource
import it.lorelore.nyanime.source.telegram.TelegramBackedSource
import it.lorelore.nyanime.studio.document.StudioDocumentComponents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Optional capabilities of trusted APK sources; no source names, URLs or parsing rules live here. */
@Composable
internal fun SourcePackageAccessPanel(record: RinRecord) {
    val context = LocalContext.current
    val packages = remember { RinRepository.get(context) }
    val coroutineScope = rememberCoroutineScope()
    var sources by remember(record.sha256) { mutableStateOf<List<NovelSource>?>(null) }
    var request by remember(record.sha256) { mutableStateOf<SourceCapabilityRequest?>(null) }
    var cleanup by remember { mutableStateOf<SourceMembershipCleanup?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var revoked by remember { mutableStateOf(false) }
    suspend fun validate() {
        check(
            packages.installed.value.any {
                it.manifest.packageName == record.manifest.packageName &&
                    it.sha256 == record.sha256 &&
                    it.publisherSigners == record.publisherSigners &&
                    it.trusted &&
                    it.enabled == record.enabled &&
                    ExtensionPackageConsent.trusted(it) &&
                    RinRepository.compatible(it.manifest, it.format)
            },
        ) { "Source changed or is disabled" }
    }
    LaunchedEffect(record.sha256, record.enabled, record.trusted) {
        try {
            validate()
            sources = withContext(Dispatchers.IO) {
                RinLoader.instantiate(context, record).filterIsInstance<NovelSource>().also { values ->
                    check(values.size <= 128)
                    values.forEach { source ->
                        check(
                            record.manifest.sources.any {
                                it.id == source.id && it.name == source.name && it.language == source.lang
                            },
                        )
                    }
                }.filter { it is TelegramBackedSource || it is SourceDocumentBackedSource }.also { values ->
                    values.forEach { source ->
                        if (source is TelegramBackedSource) TelegramPublicChannel.names(source.telegramChannels)
                        if (source is SourceDocumentBackedSource) {
                            require(
                                source.documentResolverIds.size in 1..16 &&
                                    source.documentResolverIds.all {
                                        it.matches(Regex("[a-zA-Z][a-zA-Z0-9_.-]{0,159}"))
                                    },
                            )
                        }
                    }
                }
            }
            validate()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            sources = emptyList()
            failed = true
        }
    }
    sources.orEmpty().forEach { source ->
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(source.name, style = MaterialTheme.typography.titleMedium)
                if (source is TelegramBackedSource && !record.enabled) {
                    Text(
                        stringResource(R.string.studio_telegram_activation_required),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    stringResource(R.string.studio_access_scope_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton({
                        val documents = (source as? SourceDocumentBackedSource)?.documentResolverIds.orEmpty().toSet()
                        require(documents.size <= 16)
                        val telegram: (suspend () -> TelegramSourceReview)? = if (source is TelegramBackedSource) {
                            { StudioTelegramAccess.get(context).review(record, source) }
                        } else {
                            null
                        }
                        request = SourceCapabilityRequest(
                            source.name,
                            "${record.sha256}:${source.id}",
                            StudioDocumentComponents.get(context).scope(record, source),
                            documents,
                            telegram,
                            validate = { validate() },
                        )
                        revoked = false
                    }, enabled = record.enabled && !busy) {
                        Text(stringResource(R.string.studio_access_manage))
                    }
                    TextButton({
                        if (!busy) {
                            busy = true
                            coroutineScope.launch {
                                try {
                                    if (source is SourceDocumentBackedSource) {
                                        val registry = StudioDocumentComponents.get(context)
                                        registry.revoke(registry.scope(record, source))
                                    }
                                    if (source is TelegramBackedSource) {
                                        val access = StudioTelegramAccess.get(context)
                                        val owner = access.owner(record, source)
                                        val available = access.policy.revoke(owner)
                                        if (available.isNotEmpty()) cleanup = SourceMembershipCleanup(owner, available)
                                    }
                                    revoked = true
                                    failed = false
                                } catch (cancel: CancellationException) {
                                    throw cancel
                                } catch (_: Exception) {
                                    failed = true
                                } finally {
                                    busy = false
                                }
                            }
                        }
                    }, enabled = !busy) { Text(stringResource(R.string.studio_access_revoke)) }
                }
            }
        }
    }
    if (busy) CircularProgressIndicator()
    if (failed) Text(stringResource(R.string.studio_access_failed), color = MaterialTheme.colorScheme.error)
    if (revoked) Text(stringResource(R.string.studio_access_revoked), style = MaterialTheme.typography.bodyMedium)
    request?.let { snapshot ->
        SourceCapabilityConsent(snapshot, dismiss = { request = null }, approved = { request = null })
    }
    cleanup?.let { pending -> SourceMembershipCleanupDialog(pending) { cleanup = null } }
}
