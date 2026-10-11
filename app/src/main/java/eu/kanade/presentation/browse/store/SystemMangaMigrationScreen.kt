package eu.kanade.presentation.browse.store

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.addon.RinAddonRuntime
import eu.kanade.tachiyomi.data.extensionstore.ExtensionStoreRepository
import eu.kanade.tachiyomi.data.extensionstore.SystemMangaMigration
import eu.kanade.tachiyomi.data.extensionstore.SystemMangaMigration.Position
import eu.kanade.tachiyomi.data.extensionstore.SystemMangaMigration.Side
import eu.kanade.tachiyomi.data.rin.RinSessions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import tachiyomi.presentation.core.components.material.Scaffold

internal class SystemMangaMigrationScreen(private val packageName: String? = null) : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val migration = remember { SystemMangaMigration.get(context) }
        val scope = rememberCoroutineScope()
        val sessions by migration.sessions.collectAsState()
        val mediaBusy by RinSessions.busy.collectAsState()
        val addonBusy by RinAddonRuntime.busy.collectAsState()
        var snapshot by remember { mutableStateOf<SystemMangaMigration.Snapshot?>(null) }
        var loaded by remember { mutableStateOf(false) }
        var working by remember { mutableStateOf(false) }
        var androidOpen by remember { mutableStateOf(false) }
        var failure by remember { mutableStateOf<Int?>(null) }
        var refresh by remember { mutableIntStateOf(0) }
        val actionsAvailable = loaded && !working && !androidOpen && !mediaBusy && !addonBusy

        suspend fun readState() {
            if (packageName == null) migration.refreshSessions() else snapshot = migration.snapshot(packageName)
            loaded = true
        }

        fun runAction(block: suspend () -> Unit) {
            if (!actionsAvailable || working) return
            failure = null
            working = true
            scope.launch {
                try {
                    block()
                    readState()
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: SystemMangaMigration.SplitPackage) {
                    failure = R.string.store_android_migration_split
                } catch (_: Exception) {
                    failure = R.string.store_android_migration_failed
                } catch (_: LinkageError) {
                    failure = R.string.store_android_migration_failed
                } finally {
                    working = false
                }
            }
        }

        val android = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            androidOpen = false
            ExtensionStoreRepository.get(context).resume()
            refresh++
        }
        val apk = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null && packageName != null) runAction { migration.prepare(packageName, uri) }
        }
        LifecycleResumeEffect(migration) {
            refresh++
            onPauseOrDispose {}
        }
        LaunchedEffect(migration, refresh, mediaBusy, addonBusy) {
            try {
                readState()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                failure = R.string.store_android_migration_failed
                loaded = true
            }
        }

        fun launchAndroid(side: Side, remove: Boolean) {
            val name = packageName ?: return
            runAction {
                val intent = if (remove) migration.removeIntent(name, side) else migration.installIntent(name, side)
                android.launch(intent)
                androidOpen = true
            }
        }

        Scaffold(topBar = { behavior ->
            AppBar(
                title = stringResource(R.string.store_android_migration_title),
                navigateUp = navigator::pop,
                scrollBehavior = behavior,
            )
        }) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (!loaded || working) CircularProgressIndicator()
                failure?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                if (mediaBusy || addonBusy) Text(stringResource(R.string.store_session_busy))
                when {
                    packageName == null -> {
                        if (loaded && sessions.isEmpty()) Text(stringResource(R.string.store_android_migration_empty))
                        sessions.forEach { saved ->
                            OutlinedButton(
                                onClick = { navigator.push(SystemMangaMigrationScreen(saved.packageName)) },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = actionsAvailable,
                            ) { Text(saved.name) }
                        }
                    }
                    !loaded -> Unit
                    snapshot == null -> {
                        Text(stringResource(R.string.store_android_migration_intro))
                        Button(
                            onClick = { apk.launch(arrayOf("*/*")) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = actionsAvailable,
                        ) { Text(stringResource(R.string.store_manga_migration_select)) }
                    }
                    else -> {
                        val current = requireNotNull(snapshot)
                        Text(current.session.name, style = MaterialTheme.typography.headlineSmall)
                        Text(
                            stringResource(
                                R.string.store_android_migration_versions,
                                current.session.original.versionCode,
                                current.session.candidate.versionCode,
                            ),
                        )
                        when (current.position) {
                            Position.ORIGINAL -> {
                                if (current.session.verified) {
                                    Text(stringResource(R.string.store_android_migration_remove_hint))
                                    Button(
                                        onClick = { launchAndroid(Side.ORIGINAL, remove = true) },
                                        modifier = Modifier.fillMaxWidth(),
                                        enabled = actionsAvailable,
                                    ) { Text(stringResource(R.string.store_android_migration_remove_original)) }
                                } else {
                                    Text(stringResource(R.string.store_android_migration_verify_hint))
                                    Button(
                                        onClick = { runAction { migration.verify(packageName) } },
                                        modifier = Modifier.fillMaxWidth(),
                                        enabled = actionsAvailable,
                                    ) { Text(stringResource(R.string.store_android_migration_verify)) }
                                }
                                OutlinedButton(
                                    onClick = { runAction { migration.cancel(packageName) } },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = actionsAvailable,
                                ) { Text(stringResource(R.string.store_android_migration_clear)) }
                            }
                            Position.ABSENT -> {
                                Text(stringResource(R.string.store_android_migration_install_hint))
                                Button(
                                    onClick = { launchAndroid(Side.CANDIDATE, remove = false) },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = actionsAvailable && current.session.verified,
                                ) { Text(stringResource(R.string.store_android_migration_install_new)) }
                                OutlinedButton(
                                    onClick = { launchAndroid(Side.ORIGINAL, remove = false) },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = actionsAvailable,
                                ) { Text(stringResource(R.string.store_android_migration_restore_original)) }
                            }
                            Position.CANDIDATE -> {
                                Text(stringResource(R.string.store_android_migration_installed_hint))
                                OutlinedButton(
                                    onClick = { launchAndroid(Side.CANDIDATE, remove = true) },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = actionsAvailable,
                                ) { Text(stringResource(R.string.store_android_migration_remove_candidate)) }
                            }
                            Position.OTHER -> Text(stringResource(R.string.store_android_migration_other))
                        }
                    }
                }
            }
        }
    }
}
