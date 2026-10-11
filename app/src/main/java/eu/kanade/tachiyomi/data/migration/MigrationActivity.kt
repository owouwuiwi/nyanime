package eu.kanade.tachiyomi.data.migration

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.lifecycleScope
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.main.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MigrationActivity : ComponentActivity() {
    private var ready by mutableStateOf(false)
    private var available by mutableStateOf(false)
    private var checking by mutableStateOf(true)
    private var folder by mutableStateOf<String?>(null)
    private var needsFolder by mutableStateOf(false)
    private var folderError by mutableStateOf(false)
    private var sourceLocked by mutableStateOf(false)
    private var removableLegacy by mutableStateOf(false)
    private var openingApp by mutableStateOf(false)
    private var recoveryError by mutableStateOf<String?>(null)
    private var manualRetry = false
    private var pendingGrant by mutableStateOf<String?>(null)
    private val selectFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                MigrationSharedStorage.verify(this, checkNotNull(folder), uri)
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                folder = uri.toString()
                needsFolder = false
                folderError = false
                pendingGrant?.let {
                    MigrationTransfer.start(this, it, folder)
                    pendingGrant = null
                }
            }.onFailure { folderError = true }
        }
    }
    private val authorize = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringExtra("grant")?.takeIf { result.resultCode == RESULT_OK }?.let {
            lifecycleScope.launch {
                val accessible = withContext(Dispatchers.IO) {
                    MigrationTransfer.acquireStorage(this@MigrationActivity, it, folder)
                }
                needsFolder = !accessible
                if (accessible) {
                    MigrationTransfer.start(this@MigrationActivity, it, folder)
                } else {
                    pendingGrant = it
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        manualRetry = intent.getBooleanExtra("retry", false) && savedInstanceState?.getBoolean("completed") != true
        // Rotation must not erase the completed state of a manually requested transfer.
        if (savedInstanceState == null && manualRetry && !MigrationTransfer.busy) {
            MigrationTransfer.progress.value = MigrationProgress()
        }
        val source = packageName == BuildConfig.LEGACY_APPLICATION_ID
        if (source) {
            lifecycleScope.launch {
                while (true) {
                    sourceLocked = MigrationState.frozen(this@MigrationActivity)
                    kotlinx.coroutines.delay(1000)
                }
            }
        }
        lifecycleScope.launch {
            val recovered = withContext(Dispatchers.IO) {
                runCatching {
                    if (!MigrationTransfer.busy && MigrationState.state(this@MigrationActivity).has("transaction")) {
                        if (MigrationState.state(this@MigrationActivity).optBoolean("complete")) {
                            runCatching { MigrationTransaction.finish(this@MigrationActivity) }
                        } else {
                            MigrationTransaction.rollback(this@MigrationActivity)
                        }
                    }
                }.onFailure { MigrationFailure.record(this@MigrationActivity, "recovery", it) }
            }
            if (recovered.isFailure) {
                recoveryError = MigrationFailure.code("recovery", checkNotNull(recovered.exceptionOrNull()))
                checking = false
                return@launch
            }
            if (!source) {
                if (!manualRetry &&
                    !MigrationTransfer.busy &&
                    MigrationState.state(this@MigrationActivity).optBoolean("complete")
                ) {
                    ready = true
                    checking = false
                    return@launch
                }
                val description =
                    withContext(Dispatchers.IO) {
                        runCatching { MigrationTransfer.describe(this@MigrationActivity) }.getOrNull()
                    }
                available =
                    description?.getInt("protocol") == 2 &&
                    description.getInt("minimumReceiver") <= BuildConfig.VERSION_CODE &&
                    description.getInt("versionCode") in 219..BuildConfig.VERSION_CODE &&
                    MigrationState.legacyPresent(this@MigrationActivity)
                folder = description?.getString("storage")
                val privateStorage = description?.getString("privateStoragePath")
                if (privateStorage != null) {
                    folder = MigrationStoragePaths.destination(this@MigrationActivity, privateStorage)
                    needsFolder = false
                } else if (folder != null && description?.getBoolean("storageExists") == true) {
                    needsFolder = withContext(Dispatchers.IO) {
                        runCatching {
                            MigrationSharedStorage.inventory(this@MigrationActivity, checkNotNull(folder))
                        }.getOrNull() ==
                            null
                    }
                }
            }
            ready = !manualRetry && MigrationState.state(this@MigrationActivity).optBoolean("complete")
            checking = false
        }
        setContent {
            val progress by MigrationTransfer.progress.collectAsState()
            val busy = progress.working && !progress.failed && !progress.done
            LaunchedEffect(busy) {
                if (busy) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
            val done = ready || progress.done
            LaunchedEffect(done) {
                removableLegacy = withContext(Dispatchers.IO) { MigrationState.mayRemoveLegacy(this@MigrationActivity) }
            }
            val title = when {
                source && !sourceLocked -> R.string.migration_source_ready
                done -> R.string.migration_complete
                source || busy -> R.string.migration_working
                progress.failed || recoveryError != null -> R.string.migration_failed
                else -> R.string.migration_title
            }
            val body = when {
                source && !sourceLocked -> R.string.migration_source_ready_body
                done -> R.string.migration_complete_body
                source -> R.string.migration_source_wait
                busy && progress.step == 0 -> R.string.migration_preparing_body
                busy && progress.step == 1 -> R.string.migration_copying_body
                busy -> R.string.migration_verifying_body
                progress.failed || recoveryError != null -> R.string.migration_failed_body
                !checking && !available -> R.string.migration_update_legacy
                needsFolder && pendingGrant != null -> R.string.migration_folder_body
                else -> R.string.migration_body
            }
            MigrationPage(
                if (done) 2 else progress.step,
                stringResource(title),
                stringResource(body),
                completed = done,
            ) {
                if (recoveryError != null) {
                    Text(stringResource(R.string.migration_error_code, checkNotNull(recoveryError)))
                    Button(onClick = { recreate() }) { Text(stringResource(R.string.migration_retry)) }
                    TextButton(onClick = {
                        packageManager.getLaunchIntentForPackage(
                            BuildConfig.LEGACY_APPLICATION_ID,
                        )?.let(::startActivity)
                    }) {
                        Text(stringResource(R.string.migration_open_legacy))
                    }
                } else if (source && !sourceLocked && !checking) {
                    Button(onClick = ::openApp) { Text(stringResource(R.string.migration_open_legacy)) }
                } else if (done) {
                    Button(enabled = !openingApp, onClick = ::openApp, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(if (openingApp) R.string.migration_opening else R.string.migration_open))
                    }
                    if (openingApp) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (removableLegacy) {
                        Text(stringResource(R.string.migration_remove_later))
                        TextButton(enabled = !openingApp, onClick = {
                            if (MigrationRemovalOffer.request(this@MigrationActivity)) {
                                MigrationRemovalOffer.acknowledge(this@MigrationActivity)
                            }
                        }) { Text(stringResource(R.string.migration_remove)) }
                    }
                } else if (checking || busy || source) {
                    if (busy && progress.step == 1 && progress.total > 0) {
                        LinearProgressIndicator(progress = {
                            (progress.bytes.toDouble() / progress.total).toFloat().coerceIn(0f, 1f)
                        }, modifier = Modifier.fillMaxWidth())
                        Text(
                            stringResource(
                                R.string.migration_copy_progress,
                                (100.0 * progress.bytes / progress.total).toInt().coerceIn(0, 100),
                                android.text.format.Formatter.formatFileSize(this@MigrationActivity, progress.bytes),
                                android.text.format.Formatter.formatFileSize(this@MigrationActivity, progress.total),
                            ),
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(
                            stringResource(
                                when {
                                    source -> R.string.migration_prepare
                                    progress.step == 2 -> R.string.migration_verify
                                    else -> R.string.migration_prepare
                                },
                            ),
                        )
                    }
                    if (busy && progress.step < 2) {
                        TextButton(onClick = MigrationTransfer::cancel) {
                            Text(stringResource(R.string.migration_cancel))
                        }
                    }
                } else if (available) {
                    progress.error?.let {
                        Text(
                            stringResource(R.string.migration_error_code, it),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (folderError) {
                        Text(
                            stringResource(R.string.migration_folder_wrong),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Button(onClick = {
                        if (needsFolder && pendingGrant != null) {
                            selectFolder.launch(folder?.let(Uri::parse))
                        } else {
                            authorize.launch(
                                Intent().setComponent(
                                    ComponentName(
                                        BuildConfig.LEGACY_APPLICATION_ID,
                                        MigrationConsentActivity::class.java.name,
                                    ),
                                ),
                            )
                        }
                    }) {
                        Text(
                            stringResource(
                                if (needsFolder && pendingGrant != null) {
                                    R.string.migration_folder
                                } else if (progress.failed) {
                                    R.string.migration_retry
                                } else {
                                    R.string.migration_start
                                },
                            ),
                        )
                    }
                    TextButton(onClick = {
                        lifecycleScope.launch {
                            pendingGrant?.let { token ->
                                withContext(Dispatchers.IO) {
                                    runCatching { MigrationTransfer.call(this@MigrationActivity, "abort", token) }
                                }
                            }
                            pendingGrant = null
                            MigrationState.update(this@MigrationActivity) { it.put("declined", true) }
                            openApp()
                        }
                    }) { Text(stringResource(R.string.migration_not_now)) }
                } else {
                    Button(enabled = !checking, onClick = {
                        packageManager.getLaunchIntentForPackage(
                            BuildConfig.LEGACY_APPLICATION_ID,
                        )?.let(::startActivity)
                    }) { Text(stringResource(R.string.migration_open_legacy)) }
                    TextButton(onClick = {
                        MigrationState.update(this@MigrationActivity) { it.put("declined", true) }
                        openApp()
                    }) { Text(stringResource(R.string.migration_start_empty)) }
                }
            }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("completed", ready || MigrationTransfer.progress.value.done)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            removableLegacy = withContext(Dispatchers.IO) { MigrationState.mayRemoveLegacy(this@MigrationActivity) }
        }
    }

    private fun openApp() {
        if (openingApp || MigrationState.frozen(this)) return
        openingApp = true
        lifecycleScope.launch {
            // Give the opening feedback one frame before cold startup of the normal app process.
            kotlinx.coroutines.delay(100)
            startActivity(
                Intent(this@MigrationActivity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
            finish()
        }
    }
}
