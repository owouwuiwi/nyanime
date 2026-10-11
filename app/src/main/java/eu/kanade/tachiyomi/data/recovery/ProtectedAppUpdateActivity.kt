package eu.kanade.tachiyomi.data.recovery

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import eu.kanade.tachiyomi.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A retained operation prevents duplicate exports when Android recreates the screen. */
class ProtectedUpdateModel(app: Application) : AndroidViewModel(app) {
    data class State(val phase: Int = 0, val failed: Boolean = false, val ready: Boolean = false)
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private var job: kotlinx.coroutines.Job? = null
    private var installed = false
    fun start() {
        if (job?.isActive == true || mutable.value.ready) return
        mutable.value = State()
        job = viewModelScope.launch {
            try {
                PreventiveRecoveryBackup.create(getApplication()) { mutable.value = State(phase = it) }
                mutable.value = State(ready = true)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                mutable.value = State(failed = true)
            }
        }
    }
    fun consume(): Boolean = if (installed) {
        false
    } else {
        installed = true
        true
    }
    fun installerFailed() {
        installed = false
        mutable.value = State(failed = true)
    }
}
class ProtectedAppUpdateActivity : ComponentActivity() {
    private val model by viewModels<ProtectedUpdateModel>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val apk = intent.data ?: run {
            finish()
            return
        }
        if (!RecoveryAccess(this).hasGrants()) {
            if (!install(apk)) {
                Toast.makeText(this, R.string.recovery_installer_unavailable, Toast.LENGTH_LONG).show()
                finish()
            }
            return
        }
        model.start()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                val state by model.state.collectAsState()
                var confirm by remember { mutableStateOf(false) }
                LaunchedEffect(state.ready) {
                    if (state.ready && model.consume()) install(apk)
                }
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.safeDrawingPadding().padding(28.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        Text(
                            stringResource(R.string.recovery_update_title),
                            style = MaterialTheme.typography.headlineMedium,
                        )
                        val explanation = if (state.failed) {
                            R.string.recovery_update_failed
                        } else {
                            R.string.recovery_update_body
                        }
                        Text(stringResource(explanation), style = MaterialTheme.typography.bodyLarge)
                        if (!state.failed) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            val phases = listOf(
                                R.string.recovery_phase_prepare,
                                R.string.recovery_phase_save,
                                R.string.recovery_phase_verify,
                            )
                            Text(stringResource(phases[state.phase.coerceIn(phases.indices)]))
                        }
                        if (state.failed) {
                            Button(onClick = { model.start() }, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.recovery_retry))
                            }
                            TextButton(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.recovery_update_unprotected))
                            }
                        }
                        TextButton(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.recovery_not_now))
                        }
                    }
                    if (confirm) {
                        AlertDialog(
                            onDismissRequest = { confirm = false },
                            text = { Text(stringResource(R.string.recovery_update_unprotected_warning)) },
                            confirmButton = {
                                TextButton(onClick = { install(apk) }) {
                                    Text(stringResource(R.string.recovery_continue))
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { confirm = false }) {
                                    Text(stringResource(R.string.recovery_not_now))
                                }
                            },
                        )
                    }
                }
            }
        }
    }
    private fun install(apk: Uri): Boolean =
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(apk, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
            finish()
            true
        } catch (error: Exception) {
            model.installerFailed()
            false
        }
}
