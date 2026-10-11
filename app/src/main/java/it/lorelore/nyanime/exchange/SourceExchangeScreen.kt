package it.lorelore.nyanime.exchange

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import eu.kanade.presentation.motion.ModernMotion
import eu.kanade.presentation.motion.appMotionEnabled
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.extensionstore.ExtensionStoreRepository
import eu.kanade.tachiyomi.data.watch.WatchQr
import eu.kanade.tachiyomi.util.storage.getUriCompat
import it.lorelore.nyanime.studio.SourceStudioRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SourceExchangeScreen(
    private val studioUuid: String? = null,
    private val initialTicket: String? = null,
) : Screen() {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val client = remember { SourceExchangeClient.get(context) }
        val snapshot by client.state.collectAsState()
        val scope = rememberCoroutineScope()
        val motion = appMotionEnabled()
        var receiving by rememberSaveable { mutableStateOf(initialTicket != null) }
        var internet by rememberSaveable { mutableStateOf(false) }
        var ticket by rememberSaveable { mutableStateOf(initialTicket.orEmpty()) }
        var outgoingUri by rememberSaveable { mutableStateOf("") }
        var outgoingName by rememberSaveable { mutableStateOf("") }
        var picker by remember { mutableStateOf(false) }
        var preparing by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf("") }
        val busy = snapshot.busy || preparing
        fun select(block: suspend () -> ExchangeOutgoing) {
            if (busy) return
            preparing = true
            error = ""
            scope.launch {
                try {
                    val source = block()
                    outgoingUri = source.uri.toString()
                    outgoingName = source.name
                    receiving = false
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (failure: Exception) {
                    error = if (failure.message == "split") "split" else "file"
                } finally {
                    preparing = false
                }
            }
        }
        DisposableEffect(client) {
            client.observe()
            onDispose { client.leave() }
        }
        LaunchedEffect(studioUuid) {
            if (studioUuid != null &&
                outgoingUri.isBlank() &&
                !snapshot.busy
            ) {
                select { ExchangeOutgoingFiles.studio(context, studioUuid) }
            }
        }
        val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                select {
                    withContext(Dispatchers.IO) {
                        val name = ExchangeProtocol.filename(ExchangeStorage.displayName(context, uri))
                        // Freeze the user's explicitly chosen document before the foreground transfer.
                        val directory = ExchangeStorage.newSession(context)
                        val copy = ExchangeStorage.copy(context, uri, directory, name)
                        ExchangeOutgoing(copy.getUriCompat(context), name)
                    }
                }
            }
        }
        val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
            result.contents?.let { candidate ->
                runCatching { ExchangeProtocol.ticket(candidate) }.onSuccess {
                    ticket = it
                    error = ""
                }.onFailure {
                    error =
                        "ticket"
                }
            }
        }
        val scanPrompt = stringResource(R.string.exchange_scan)
        Scaffold(topBar = {
            TopAppBar(title = { Text(stringResource(R.string.exchange_title)) }, navigationIcon = {
                IconButton({
                    navigator.pop()
                }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_close)) }
            })
        }) { padding ->
            LazyColumn(
                Modifier.fillMaxSize().padding(padding).imePadding(),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                item {
                    Icon(Icons.Outlined.Devices, null, Modifier.size(38.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.exchange_intro), style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.exchange_summary), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item {
                    AnimatedContent(snapshot.phase, transitionSpec = {
                        ModernMotion.transform(motion)
                    }, label = "exchange-phase") { phase ->
                        when {
                            phase == ExchangePhase.REVIEW -> ExchangeReview(snapshot) { file ->
                                navigator.push(SourceExchangeImportScreen(file))
                            }
                            snapshot.busy -> ExchangeActivity(snapshot, client::stop)
                            else -> Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                if (phase == ExchangePhase.FAILED) ExchangeError(snapshot.error)
                                if (phase ==
                                    ExchangePhase.STOPPED
                                ) {
                                    Text(
                                        stringResource(R.string.exchange_stopped),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
                if (!snapshot.busy) {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FilterChip(!receiving, {
                                receiving = false
                            }, {
                                Text(stringResource(R.string.exchange_send))
                            }, enabled = !preparing, modifier = Modifier.weight(1f))
                            FilterChip(receiving, {
                                receiving = true
                            }, {
                                Text(stringResource(R.string.exchange_receive))
                            }, enabled = !preparing, modifier = Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FilterChip(!internet, {
                                internet = false
                            }, { Text(stringResource(R.string.exchange_local)) }, modifier = Modifier.weight(1f))
                            FilterChip(internet, {
                                internet = true
                            }, { Text(stringResource(R.string.exchange_internet)) }, modifier = Modifier.weight(1f))
                        }
                        Text(
                            stringResource(
                                if (internet) R.string.exchange_internet_hint else R.string.exchange_local_hint,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (receiving) {
                        item {
                            OutlinedTextField(ticket, {
                                if (it.length <=
                                    66000
                                ) {
                                    ticket = it
                                    error = ""
                                }
                            }, Modifier.fillMaxWidth(), label = {
                                Text(stringResource(R.string.exchange_ticket))
                            }, maxLines = 4, shape = RoundedCornerShape(20.dp))
                            TextButton({
                                scanner.launch(
                                    ScanOptions().setDesiredBarcodeFormats(
                                        ScanOptions.QR_CODE,
                                    ).setBeepEnabled(false).setOrientationLocked(false).setPrompt(scanPrompt),
                                )
                            }) {
                                Icon(Icons.Outlined.QrCodeScanner, null)
                                Text(stringResource(R.string.exchange_scan), Modifier.padding(start = 10.dp))
                            }
                            Button(
                                {
                                    runCatching { client.receive(ticket, internet) }.onFailure { error = "ticket" }
                                },
                                Modifier.fillMaxWidth(),
                                enabled =
                                ticket.isNotBlank() && !preparing,
                            ) {
                                Text(stringResource(R.string.exchange_receive_file))
                            }
                        }
                    } else {
                        item {
                            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
                                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(
                                        outgoingName.ifBlank {
                                            stringResource(R.string.exchange_choose)
                                        },
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    if (preparing) CircularProgressIndicator(Modifier.size(24.dp))
                                    FilledTonalButton({
                                        picker = true
                                    }, Modifier.fillMaxWidth(), enabled = !preparing) {
                                        Text(stringResource(R.string.exchange_your_sources))
                                    }
                                    OutlinedButton({
                                        filePicker.launch(arrayOf("*/*"))
                                    }, Modifier.fillMaxWidth(), enabled = !preparing) {
                                        Icon(Icons.Outlined.FolderOpen, null)
                                        Text(
                                            stringResource(R.string.exchange_choose_file),
                                            Modifier.padding(start = 10.dp),
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Button({
                                runCatching { client.share(Uri.parse(outgoingUri), outgoingName, internet) }.onFailure {
                                    error =
                                        "file"
                                }
                            }, Modifier.fillMaxWidth(), enabled = outgoingUri.isNotBlank() && !preparing) {
                                Text(stringResource(R.string.exchange_start_share))
                            }
                        }
                    }
                }
                if (error.isNotBlank()) item { ExchangeError(error) }
                item {
                    Text(
                        stringResource(R.string.exchange_safety),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        if (picker) {
            val studio = remember { SourceStudioRepository.get(context) }
            val records by studio.records.collectAsState()
            val store = remember { ExtensionStoreRepository.get(context) }
            val entries by store.entries.collectAsState()
            ModalBottomSheet(onDismissRequest = { picker = false }) {
                LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        Text(
                            stringResource(R.string.exchange_your_sources),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                    }
                    items(records, key = { "studio-${it.draft.uuid}" }) { record ->
                        SourceChoice(
                            record.draft.name,
                            stringResource(
                                if (record.enabled) {
                                    R.string.exchange_active_definition
                                } else {
                                    R.string.exchange_draft_definition
                                },
                            ),
                        ) {
                            picker = false
                            select { ExchangeOutgoingFiles.studio(context, record.draft.uuid) }
                        }
                    }
                    items(
                        entries.filter {
                            it.installed
                        }.distinctBy { it.packageName },
                        key = { "package-${it.packageName}" },
                    ) { entry ->
                        SourceChoice(entry.name, entry.version) {
                            picker = false
                            select { ExchangeOutgoingFiles.installed(context, entry) }
                        }
                    }
                    if (records.isEmpty() &&
                        entries.none { it.installed }
                    ) {
                        item { Text(stringResource(R.string.exchange_no_sources)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceChoice(name: String, detail: String, click: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().clickable(onClick = click),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium)
            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ExchangeActivity(snapshot: ExchangeSnapshot, stop: () -> Unit) {
    val context = LocalContext.current
    var now by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(snapshot.expiresAt) {
        while (true) {
            delay(1000)
            now = SystemClock.elapsedRealtime()
        }
    }
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp)) {
        Column(
            Modifier.padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(
                    when (snapshot.phase) {
                        ExchangePhase.SHARING -> R.string.exchange_ready
                        ExchangePhase.RECEIVING -> R.string.exchange_receiving
                        ExchangePhase.CONNECTING -> R.string.exchange_connecting
                        else -> R.string.exchange_preparing
                    },
                ),
                style = MaterialTheme.typography.titleLarge,
            )
            if (snapshot.name.isNotBlank()) Text(snapshot.name, style = MaterialTheme.typography.bodyMedium)
            if (snapshot.phase == ExchangePhase.SHARING) {
                ExchangeQr(ExchangeProtocol.link(snapshot.ticket))
                Text(
                    stringResource(
                        R.string.exchange_expiry,
                        (
                            ((snapshot.expiresAt - now).coerceAtLeast(0) + 59999) /
                                60000
                            ).toInt(),
                    ),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(stringResource(R.string.exchange_keep_open), style = MaterialTheme.typography.bodySmall)
                FilledTonalButton({
                    context.startActivity(
                        Intent.createChooser(
                            Intent(
                                Intent.ACTION_SEND,
                            ).setType("text/plain").putExtra(Intent.EXTRA_TEXT, ExchangeProtocol.link(snapshot.ticket)),
                            null,
                        ),
                    )
                }, Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Share, null)
                    Text(stringResource(R.string.exchange_share_code), Modifier.padding(start = 10.dp))
                }
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                if (snapshot.bytes >
                    0
                ) {
                    Text(
                        android.text.format.Formatter.formatShortFileSize(context, snapshot.bytes),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            OutlinedButton(stop, Modifier.fillMaxWidth()) { Text(stringResource(R.string.exchange_stop)) }
        }
    }
}

@Composable
private fun ExchangeQr(link: String) {
    val bitmap by produceState<Bitmap?>(null, link) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                val matrix = WatchQr.encode(link)
                Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888).also { bitmap ->
                    val pixels = IntArray(matrix.width * matrix.height) { index ->
                        if (matrix[
                                index % matrix.width, index /
                                    matrix.width,
                            ]
                        ) {
                            android.graphics.Color.BLACK
                        } else {
                            android.graphics.Color.WHITE
                        }
                    }
                    bitmap.setPixels(pixels, 0, matrix.width, 0, 0, matrix.width, matrix.height)
                }
            }.getOrNull()
        }
    }
    Surface(color = Color.White, shape = RoundedCornerShape(18.dp)) {
        bitmap?.let {
            Image(
                it.asImageBitmap(),
                stringResource(R.string.exchange_qr),
                Modifier.size(240.dp).padding(12.dp),
                filterQuality = androidx.compose.ui.graphics.FilterQuality.None,
            )
        }
    }
}

@Composable
private fun ExchangeReview(snapshot: ExchangeSnapshot, open: (ExchangeFile) -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.exchange_received), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.exchange_verified_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
            snapshot.files.forEach { file ->
                SourceChoice(
                    file.name,
                    android.text.format.Formatter.formatShortFileSize(LocalContext.current, file.bytes),
                ) {
                    open(file)
                }
            }
        }
    }
}

@Composable
private fun ExchangeError(reason: String) {
    Text(
        stringResource(
            when (reason) {
                "engine" -> R.string.exchange_error_engine
                "space" -> R.string.exchange_error_space
                "ticket" -> R.string.exchange_error_ticket
                "file" -> R.string.exchange_error_file
                "split" -> R.string.exchange_error_split
                else -> R.string.exchange_error_transfer
            },
        ),
        color = MaterialTheme.colorScheme.error,
    )
}
