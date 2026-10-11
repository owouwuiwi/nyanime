package eu.kanade.presentation.more.settings.screen.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.WarningBanner
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.backup.BackupExportStore
import eu.kanade.tachiyomi.data.backup.create.BackupCreateJob
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.util.system.DeviceUtil
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.update
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.LabeledCheckbox
import tachiyomi.presentation.core.components.LazyColumnWithAction
import tachiyomi.presentation.core.components.SectionCard
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import androidx.compose.ui.res.stringResource as androidStringResource

class CreateBackupScreen(private val completePreset: Boolean = true) : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val model = rememberScreenModel {
            CreateBackupScreenModel(if (completePreset) BackupOptions.complete() else BackupOptions())
        }
        val state by model.state.collectAsState()
        val permissionRequest =
            rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (granted) {
                    model.createBackup(context)
                    navigator.pop()
                } else {
                    android.widget.Toast.makeText(
                        context,
                        context.getString(R.string.quick_backup_permission),
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            }

        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(MR.strings.pref_create_backup),
                    navigateUp = navigator::pop,
                    scrollBehavior = it,
                )
            },
        ) { contentPadding ->
            LazyColumnWithAction(
                contentPadding = contentPadding,
                actionLabel = stringResource(MR.strings.action_create),
                actionEnabled = state.options.canCreate(),
                onClickAction = {
                    if (!BackupCreateJob.isManualJobRunning(context)) {
                        if (BackupExportStore.requiresLegacyStoragePermission() &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            permissionRequest.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        } else {
                            model.createBackup(context)
                            navigator.pop()
                        }
                    }
                },
            ) {
                if (DeviceUtil.isMiui && DeviceUtil.isMiuiOptimizationDisabled()) {
                    item {
                        WarningBanner(MR.strings.restore_miui_warning)
                    }
                }

                item {
                    SectionCard(MR.strings.backup_complete_title) {
                        Text(
                            androidStringResource(R.string.quick_backup_subtitle),
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                        Text(
                            stringResource(MR.strings.backup_complete_description),
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                        Button(
                            onClick = model::selectComplete,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Text(stringResource(MR.strings.backup_complete_select))
                        }
                    }
                }

                if (state.options.privateSettings) {
                    item { WarningBanner(MR.strings.backup_complete_private_warning) }
                }

                item {
                    SectionCard(MR.strings.label_library) {
                        Options(BackupOptions.libraryOptions, state, model)
                    }
                }

                item {
                    SectionCard(MR.strings.label_settings) {
                        Options(BackupOptions.settingsOptions, state, model)
                    }
                }

                item {
                    SectionCard(MR.strings.label_extensions) {
                        Options(BackupOptions.extensionOptions, state, model)
                    }
                }
            }
        }
    }

    @Composable
    private fun Options(
        options: ImmutableList<BackupOptions.Entry>,
        state: CreateBackupScreenModel.State,
        model: CreateBackupScreenModel,
    ) {
        options.forEach { option ->
            LabeledCheckbox(
                label = stringResource(option.label),
                checked = option.getter(state.options),
                onCheckedChange = {
                    model.toggle(option.setter, it)
                },
                enabled = option.enabled(state.options),
            )
        }
    }
}

private class CreateBackupScreenModel(initialOptions: BackupOptions) :
    StateScreenModel<CreateBackupScreenModel.State>(State(initialOptions)) {

    fun selectComplete() {
        mutableState.update { it.copy(options = BackupOptions.complete()) }
    }

    fun toggle(setter: (BackupOptions, Boolean) -> BackupOptions, enabled: Boolean) {
        mutableState.update {
            it.copy(
                options = setter(it.options, enabled),
            )
        }
    }

    fun createBackup(context: Context) {
        BackupCreateJob.startNow(context, state.value.options)
    }

    @Immutable
    data class State(
        val options: BackupOptions = BackupOptions(),
    )
}
