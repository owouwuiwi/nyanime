package eu.kanade.tachiyomi.data.recovery

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.addon.AddonExtension
import eu.kanade.tachiyomi.data.addon.AddonRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Works without the normal Application graph, including after a clean reinstall. */
class RecoveryConsentActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val caller = callingPackage ?: run {
            finish()
            return
        }
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                var addon by remember { mutableStateOf<AddonExtension?>(null) }
                var checked by remember { mutableStateOf(false) }
                var busy by remember { mutableStateOf(false) }
                LaunchedEffect(caller) {
                    addon = withContext(Dispatchers.IO) {
                        val registry = AddonRegistry(this@RecoveryConsentActivity)
                        registry.reload()
                        registry.state.value.singleOrNull {
                            it.packageName == caller && it.compatible && it.dataRecovery
                        }
                    }
                    checked = true
                }
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.safeDrawingPadding().padding(28.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        Column(
                            Modifier.weight(1f).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            Text(
                                stringResource(R.string.recovery_authorize_title),
                                style = MaterialTheme.typography.headlineMedium,
                            )
                            Text(
                                stringResource(R.string.recovery_authorize_body, addon?.label.orEmpty()),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                stringResource(R.string.recovery_private_warning),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (checked && addon == null) Text(stringResource(R.string.recovery_unavailable))
                        }
                        Button(modifier = Modifier.fillMaxWidth(), enabled = addon != null && !busy, onClick = {
                            busy = true
                            lifecycleScope.launch {
                                val approved = withContext(Dispatchers.IO) {
                                    runCatching {
                                        val registry = AddonRegistry(this@RecoveryConsentActivity)
                                        registry.reload()
                                        val current = registry.state.value.single {
                                            it.packageName == caller &&
                                                it.compatible &&
                                                it.dataRecovery
                                        }
                                        check(current.metadata.signers == addon?.metadata?.signers)
                                        if (!current.usable) registry.trust(caller)
                                        registry.reload()
                                        RecoveryAccess(this@RecoveryConsentActivity).approve(
                                            registry.state.value.single {
                                                it.packageName ==
                                                    caller
                                            },
                                        )
                                    }.isSuccess
                                }
                                setResult(if (approved) RESULT_OK else RESULT_CANCELED)
                                finish()
                            }
                        }) { Text(stringResource(R.string.recovery_authorize)) }
                        TextButton(modifier = Modifier.fillMaxWidth(), onClick = {
                            finish()
                        }) { Text(stringResource(R.string.recovery_not_now)) }
                    }
                }
            }
        }
    }
}
