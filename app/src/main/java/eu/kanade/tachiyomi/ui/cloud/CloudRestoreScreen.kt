package eu.kanade.tachiyomi.ui.cloud

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.work.WorkInfo
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.backup.BackupFileValidator
import eu.kanade.tachiyomi.data.backup.restore.BackupRestoreJob
import eu.kanade.tachiyomi.data.backup.restore.RestoreAlreadyRunningException
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import eu.kanade.tachiyomi.data.cloud.CloudRepository
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.presentation.core.components.LabeledCheckbox
import java.io.File
import java.util.UUID
import eu.kanade.tachiyomi.ui.cloud.CloudMotion as ModernMotion
import eu.kanade.tachiyomi.ui.cloud.cloudMotionEnabled as appMotionEnabled
import tachiyomi.presentation.core.i18n.stringResource as translatedString

/** Uses the existing backup validator and restorer; keeps confirmation, progress and outcome in one place. */
class CloudRestoreScreen(
    private val uri: String,
    private val deviceName: String,
    private val serverDate: Long,
    private val bytes: Long,
    private val setupAccount: Long = 0,
    private val localBackup: Boolean = false,
) : Screen() {
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val motion = appMotionEnabled()
        var preview by remember { mutableStateOf<BackupFileValidator.Results?>(null) }
        var checking by remember { mutableStateOf(true) }
        var error by remember { mutableStateOf<String?>(null) }
        var options by rememberSaveable { mutableStateOf(RestoreOptions(extensions = true).asBooleanArray()) }
        var advanced by rememberSaveable { mutableStateOf(false) }
        var jobId by rememberSaveable { mutableStateOf<String?>(null) }
        var starting by remember { mutableStateOf(false) }
        var report by remember { mutableStateOf<String?>(null) }
        val work by remember(jobId) {
            jobId?.let { context.workManager.getWorkInfoByIdFlow(UUID.fromString(it)) } ?: flowOf(null)
        }.collectAsState(initial = null)
        val finished = work?.state?.isFinished == true
        val succeeded = work?.state == WorkInfo.State.SUCCEEDED
        val running = jobId != null && !finished
        val failures = work?.outputData?.getInt("error_count", 0) ?: 0

        LaunchedEffect(uri) {
            try {
                preview = withContext(Dispatchers.IO) { BackupFileValidator(context).validate(uri.toUri()) }
                if (jobId == null) jobId = BackupRestoreJob.activeFor(context, uri.toUri())?.toString()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                error = context.getString(R.string.cloud_restore_invalid)
            } finally {
                checking = false
            }
        }

        fun startRestore() {
            scope.launch {
                starting = true
                error = null
                try {
                    jobId =
                        BackupRestoreJob.startTracked(context, uri.toUri(), RestoreOptions.fromBooleanArray(options))
                            .toString()
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (failure: Exception) {
                    error = context.getString(
                        if (failure is RestoreAlreadyRunningException) {
                            R.string.cloud_restore_already_running
                        } else {
                            R.string.cloud_action_failed
                        },
                    )
                } finally {
                    starting = false
                }
            }
        }

        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(
                        if (localBackup) R.string.welcome_restore_file else R.string.cloud_restore_title,
                    ),
                    navigateUp = navigator::pop,
                )
            },
            bottomBar = {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Column(Modifier.widthIn(max = 640.dp)) {
                            when {
                                succeeded -> CloudActionButton(
                                    stringResource(R.string.cloud_restore_continue),
                                    !starting,
                                ) {
                                    scope.launch {
                                        starting = true
                                        try {
                                            if (setupAccount >
                                                0
                                            ) {
                                                CloudRepository.get(context).completeSetup(setupAccount)
                                            }
                                            navigator.pop()
                                        } catch (cancel: CancellationException) {
                                            throw cancel
                                        } catch (_: Exception) {
                                            error = context.getString(R.string.cloud_action_failed)
                                        } finally {
                                            starting = false
                                        }
                                    }
                                }
                                finished -> CloudActionButton(
                                    stringResource(R.string.cloud_restore_retry),
                                    !starting,
                                    busy = starting,
                                    onClick = ::startRestore,
                                )
                                running -> Text(
                                    stringResource(R.string.cloud_restore_background),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                else -> CloudActionButton(
                                    stringResource(R.string.cloud_restore_start),
                                    !checking &&
                                        preview != null &&
                                        !starting &&
                                        RestoreOptions.fromBooleanArray(options).canRestore(),
                                    busy = starting,
                                    onClick = ::startRestore,
                                )
                            }
                        }
                    }
                }
            },
        ) { padding ->
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                item {
                    RestorePanel {
                        Icon(
                            if (succeeded) Icons.Outlined.CheckCircle else Icons.Outlined.CloudDownload,
                            null,
                            Modifier.size(42.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        val heading = when {
                            succeeded && failures == 0 -> R.string.cloud_restore_done
                            succeeded -> R.string.cloud_restore_partial
                            running -> R.string.cloud_restore_running
                            finished -> R.string.cloud_restore_interrupted
                            else -> R.string.cloud_restore_heading
                        }
                        Crossfade(
                            targetState = heading,
                            animationSpec = tween(ModernMotion.duration(motion, ModernMotion.PAGE_MILLIS)),
                            label = "restorePhase",
                        ) { title ->
                            Text(
                                stringResource(title),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Text(
                            if (localBackup) deviceName else "$deviceName · ${formattedCloudDate(serverDate)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (bytes > 0) {
                            Text(
                                android.text.format.Formatter.formatFileSize(context, bytes),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        when {
                            running -> {
                                val total = work?.progress?.getInt("total", 0) ?: 0
                                val completed = work?.progress?.getInt("completed", 0) ?: 0
                                if (total > 0) {
                                    val fraction = (completed.toFloat() / total).coerceIn(0f, 1f)
                                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                                    Text("${(fraction * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
                                } else {
                                    LinearProgressIndicator(Modifier.fillMaxWidth())
                                }
                            }
                            checking -> LinearProgressIndicator(Modifier.fillMaxWidth())
                            succeeded -> Text(
                                stringResource(
                                    if (failures == 0) {
                                        R.string.cloud_restore_done_summary
                                    } else {
                                        R.string.cloud_restore_partial_summary
                                    },
                                    failures,
                                ),
                            )
                            finished -> Text(stringResource(R.string.cloud_restore_retry_summary))
                            else -> Text(stringResource(R.string.cloud_restore_summary))
                        }
                        CloudInlineError(error)
                    }
                }
                if (preview != null) {
                    preview?.let { contents ->
                        item {
                            Column(Modifier.widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    RestoreMetric(
                                        stringResource(R.string.cloud_restore_anime),
                                        contents.animeCount,
                                        Modifier.weight(1f),
                                    )
                                    RestoreMetric(
                                        stringResource(R.string.cloud_restore_manga),
                                        contents.mangaCount,
                                        Modifier.weight(1f),
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    RestoreMetric(
                                        stringResource(R.string.cloud_restore_progress),
                                        contents.episodeCount + contents.chapterCount,
                                        Modifier.weight(1f),
                                    )
                                    RestoreMetric(
                                        stringResource(R.string.cloud_restore_settings),
                                        contents.settingCount,
                                        Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                        item {
                            RestorePanel {
                                Text(
                                    stringResource(R.string.cloud_restore_extensions_summary, contents.extensionCount),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                TextButton(
                                    onClick = { advanced = !advanced },
                                    enabled = jobId == null,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(stringResource(R.string.cloud_restore_options), Modifier.weight(1f))
                                    Icon(if (advanced) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                                }
                                AnimatedVisibility(
                                    visible = advanced && jobId == null,
                                    enter = ModernMotion.enter(
                                        ModernMotion.duration(motion, ModernMotion.SURFACE_ENTER_MILLIS),
                                    ),
                                    exit = ModernMotion.exit(
                                        ModernMotion.duration(motion, ModernMotion.SURFACE_EXIT_MILLIS),
                                    ),
                                ) {
                                    Column {
                                        RestoreOptions.options.forEach { option ->
                                            val selected = RestoreOptions.fromBooleanArray(options)
                                            LabeledCheckbox(
                                                label = translatedString(option.label),
                                                checked = option.getter(selected),
                                                onCheckedChange = {
                                                    options =
                                                        option.setter(selected, it).asBooleanArray()
                                                },
                                            )
                                        }
                                        if (contents.missingSources.isNotEmpty() ||
                                            contents.missingTrackers.isNotEmpty()
                                        ) {
                                            Text(
                                                stringResource(R.string.cloud_restore_missing),
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                            Text(
                                                (contents.missingSources + contents.missingTrackers).joinToString("\n"),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (succeeded && failures > 0) {
                    item {
                        RestorePanel {
                            TextButton(onClick = {
                                scope.launch {
                                    val path = work?.outputData?.getString("error_log")
                                    report = withContext(Dispatchers.IO) {
                                        runCatching {
                                            val file = File(requireNotNull(path)).canonicalFile
                                            val allowed = listOfNotNull(context.externalCacheDir, context.cacheDir)
                                                .any { file.parentFile == it.canonicalFile }
                                            check(allowed)
                                            file.reader().use {
                                                val buffer = CharArray(64 * 1024)
                                                val count = it.read(buffer).coerceAtLeast(0)
                                                String(buffer, 0, count)
                                            }
                                        }.getOrElse { context.getString(R.string.cloud_restore_report_unavailable) }
                                    }
                                }
                            }) { Text(stringResource(R.string.cloud_restore_report)) }
                            report?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RestorePanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(26.dp),
    ) {
        Column(
            Modifier.animateContentSize(ModernMotion.resize(appMotionEnabled())).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

@Composable
private fun RestoreMetric(label: String, value: Int, modifier: Modifier) {
    Surface(
        modifier.heightIn(min = 100.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(value.toString(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
