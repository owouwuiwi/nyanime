package it.lorelore.nyanime.studio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import eu.kanade.tachiyomi.data.telegram.TelegramClient
import eu.kanade.tachiyomi.ui.cloud.LoginCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import nyanime.telegram.tdRequest

/** Login is reused; entering this screen never enables Cloud backup or synchronization. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StudioSourceConsent(
    source: StudioSource,
    preview: Boolean = false,
    dismiss: () -> Unit,
    approved: suspend (TelegramSourceActivation?) -> Unit,
) {
    val context = LocalContext.current
    val access = remember { StudioTelegramAccess.get(context) }
    val definitions = remember { SourceStudioRepository.get(context) }
    SourceCapabilityConsent(
        SourceCapabilityRequest(
            source.name,
            StudioDocuments.fingerprint(source) + ":$preview",
            "studio:${source.uuid}:${source.sourceId}",
            source.documentResolvers.toSet(),
            if (source.transport == StudioTransport.TELEGRAM) ({ access.review(source, preview) }) else null,
            validate = {
                definitions.awaitReady()
                check(definitions.records.value.singleOrNull { it.draft.uuid == source.uuid }?.draft == source) {
                    "Source draft changed"
                }
            },
        ),
        preview,
        dismiss,
        approved,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TelegramSourceConsent(
    displayName: String,
    reviewKey: String,
    preview: Boolean = false,
    fetchReview: suspend () -> TelegramSourceReview,
    dismiss: () -> Unit,
    additionalContent: @Composable () -> Unit = {},
    canApprove: Boolean = true,
    beforeApprove: suspend () -> Unit = {},
    approved: suspend (TelegramSourceActivation) -> Unit,
) {
    val context = LocalContext.current
    val client = remember { TelegramClient.get(context) }
    val access = remember { StudioTelegramAccess.get(context) }
    val state by client.state.collectAsState()
    val scope = rememberCoroutineScope()
    var review by
        remember(reviewKey, state.account?.id) { mutableStateOf<TelegramSourceReview?>(null) }
    var mute by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val failure = stringResource(R.string.studio_telegram_access_failed)
    fun perform(operation: suspend () -> Unit) {
        if (busy) return
        busy = true
        failed = false
        scope.launch {
            try {
                operation()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                failed = true
            } finally {
                busy = false
            }
        }
    }
    DisposableEffect(client) {
        val lease = client.retain()
        onDispose { client.release(lease) }
    }
    LaunchedEffect(Unit) {
        try {
            client.connect()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            failed = true
        }
    }
    LaunchedEffect(reviewKey, state.ready, state.account?.id) {
        review = null
        if (state.ready) {
            busy = true
            failed = false
            try {
                review = fetchReview()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                failed = true
            } finally {
                busy = false
            }
        }
    }
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(
            Modifier.fillMaxWidth()
                .heightIn(max = (LocalConfiguration.current.screenHeightDp * .84f).dp)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.studio_telegram_access_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(displayName, style = MaterialTheme.typography.titleMedium)
            additionalContent()
            Text(
                stringResource(R.string.studio_telegram_access_scope),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!state.ready) {
                LoginCard(
                    state,
                    busy,
                    failure.takeIf { failed },
                    onStart = { perform { client.connect() } },
                    onSubmit = { value -> perform { client.submit(value) } },
                    onQr = { perform { client.requestQr() } },
                    onRestart = {
                        perform {
                            if (!client.state.value.ready) {
                                client.logout()
                                client.connect()
                            }
                        }
                    },
                    onResend = {
                        perform { client.request(tdRequest("resendAuthenticationCode")) }
                    },
                )
            } else {
                review?.channels?.forEach { channel ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(channel.title, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "@${channel.name}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                stringResource(
                                    if (channel.member) {
                                        R.string.studio_telegram_already_joined
                                    } else {
                                        R.string.studio_telegram_will_join
                                    },
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                if (review?.channels?.any { !it.member } == true) {
                    Row {
                        Text(
                            stringResource(R.string.studio_telegram_mute_new),
                            Modifier.weight(1f).padding(end = 12.dp),
                        )
                        Switch(mute, { mute = it }, enabled = !busy)
                    }
                }
                if (busy) CircularProgressIndicator()
                if (failed) {
                    Text(failure, color = MaterialTheme.colorScheme.error)
                    TextButton({ perform { review = fetchReview() } }) {
                        Text(stringResource(R.string.studio_telegram_retry))
                    }
                }
                Button(
                    {
                        val snapshot = review ?: return@Button
                        perform {
                            beforeApprove()
                            approved(access.activate(snapshot, mute))
                        }
                    },
                    Modifier.fillMaxWidth(),
                    enabled = review != null && !busy && canApprove,
                ) {
                    Text(
                        stringResource(
                            if (preview) {
                                R.string.studio_telegram_allow_test
                            } else {
                                R.string.studio_telegram_activate_join
                            },
                        ),
                    )
                }
            }
            TextButton({ dismiss() }, Modifier.fillMaxWidth(), enabled = !busy) {
                Text(stringResource(R.string.studio_cancel))
            }
        }
    }
}
