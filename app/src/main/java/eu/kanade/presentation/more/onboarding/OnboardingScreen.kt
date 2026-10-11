package eu.kanade.presentation.more.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.domain.ui.model.ThemeMode
import eu.kanade.presentation.more.UpdateChannelChoice
import eu.kanade.presentation.motion.ModernMotion
import eu.kanade.presentation.motion.appMotionEnabled
import eu.kanade.presentation.theme.NyanimeWordmark
import eu.kanade.presentation.theme.ThemeModeCards
import eu.kanade.tachiyomi.R
import tachiyomi.domain.release.model.UpdateChannel

const val GETTING_STARTED_URL = "https://github.com/owouwuiwi/nyanime/blob/main/docs/getting-started.md"

@Composable
fun OnboardingScreen(
    step: Int,
    storageReady: Boolean,
    managedStorage: Boolean,
    storageError: Boolean,
    saving: Boolean,
    error: Boolean,
    theme: ThemeMode,
    channel: UpdateChannel,
    restoring: Boolean,
    restoreResult: Int?,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onRetryStorage: () -> Unit,
    onRepairStorage: () -> Unit,
    onTheme: (ThemeMode) -> Unit,
    onChannel: (UpdateChannel) -> Unit,
    onRestoreBackup: () -> Unit,
    onRestoreCloud: () -> Unit,
) {
    val motion = appMotionEnabled()
    val colors = MaterialTheme.colorScheme
    val labels =
        listOf(
            R.string.welcome_step_start,
            R.string.welcome_step_appearance,
            R.string.welcome_step_permissions,
            R.string.welcome_step_updates,
            R.string.welcome_step_restore,
        )
    val titles =
        listOf(
            R.string.welcome_title,
            R.string.welcome_appearance_title,
            R.string.welcome_permissions_title,
            R.string.welcome_updates_title,
            R.string.welcome_restore_title,
        )
    val subtitles =
        listOf(
            R.string.welcome_subtitle,
            R.string.welcome_appearance_description,
            R.string.welcome_permissions_description,
            R.string.welcome_updates_description,
            R.string.welcome_restore_description,
        )
    BackHandler { if (!saving) onBack() }
    Scaffold(
        containerColor = colors.background,
        topBar = {
            Column(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(Modifier.widthIn(max = 600.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(48.dp)) {
                        if (step > 0) {
                            IconButton(onClick = onBack, enabled = !saving) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.welcome_back))
                            }
                        }
                    }
                    NyanimeWordmark(Modifier.height(32.dp).weight(1f))
                    Text(
                        stringResource(R.string.welcome_step_count, step + 1, labels.size),
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp),
                    )
                }
                Row(Modifier.widthIn(max = 600.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    labels.indices.forEach { index ->
                        Surface(
                            Modifier.weight(1f).height(3.dp),
                            shape = RoundedCornerShape(3.dp),
                            color = if (index <= step) colors.primary else colors.outlineVariant.copy(alpha = 0.45f),
                        ) {}
                    }
                }
            }
        },
        bottomBar = {
            Surface(color = colors.background) {
                Column(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (error) Text(stringResource(R.string.welcome_save_error), color = colors.error)
                    Button(
                        onClick = onNext,
                        enabled = storageReady && !saving && !restoring,
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth().heightIn(min = 56.dp),
                    ) {
                        val actionLabel = when {
                            saving -> R.string.welcome_saving
                            step == 4 -> R.string.welcome_start
                            else -> R.string.welcome_continue
                        }
                        Text(stringResource(actionLabel), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.size(12.dp))
                        Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(20.dp))
                    }
                    Text(
                        stringResource(
                            if (step ==
                                4
                            ) {
                                R.string.welcome_restore_later
                            } else {
                                labels[step]
                            },
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Crossfade(
                targetState = step,
                animationSpec = tween(if (motion) ModernMotion.PAGE_MILLIS else 0),
                label = "welcomeStep",
            ) { page ->
                Column(
                    Modifier.widthIn(
                        max = 648.dp,
                    ).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            stringResource(titles[page]),
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.semantics {
                                heading()
                            },
                        )
                        Text(
                            stringResource(subtitles[page]),
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    when (page) {
                        0 -> {
                            WelcomePanel {
                                Icon(
                                    if (storageReady) Icons.Outlined.CheckCircle else Icons.Outlined.Folder,
                                    null,
                                    Modifier.size(36.dp),
                                    tint = if (storageError) colors.error else colors.primary,
                                )
                                val storageTitle = when {
                                    storageError -> R.string.welcome_storage_error
                                    storageReady -> R.string.welcome_storage_ready
                                    else -> R.string.welcome_storage_preparing
                                }
                                val storageDescription = when {
                                    storageError -> R.string.welcome_storage_error_description
                                    managedStorage -> R.string.welcome_storage_description
                                    else -> R.string.welcome_storage_existing
                                }
                                Text(
                                    stringResource(storageTitle),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(stringResource(storageDescription), color = colors.onSurfaceVariant)
                                if (!storageReady && !storageError) LinearProgressIndicator(Modifier.fillMaxWidth())
                                if (storageError) {
                                    Button(onClick = onRetryStorage) { Text(stringResource(R.string.welcome_retry)) }
                                    TextButton(onClick = onRepairStorage) {
                                        Text(stringResource(R.string.welcome_storage_repair))
                                    }
                                }
                            }
                            WelcomePanel {
                                Icon(Icons.Outlined.Extension, null, Modifier.size(28.dp), tint = colors.primary)
                                Text(
                                    stringResource(R.string.welcome_extensions_title),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    stringResource(R.string.welcome_extensions_description),
                                    color = colors.onSurfaceVariant,
                                )
                                var more by rememberSaveable { mutableStateOf(false) }
                                TextButton(onClick = {
                                    more = !more
                                }) { Text(stringResource(if (more) R.string.welcome_less else R.string.welcome_more)) }
                                if (more) {
                                    Text(stringResource(R.string.addon_intro_choice), color = colors.onSurfaceVariant)
                                    Text(stringResource(R.string.addon_intro_beta), color = colors.onSurfaceVariant)
                                    Text(stringResource(R.string.addon_intro_future), color = colors.onSurfaceVariant)
                                }
                            }
                        }
                        1 -> ThemeModeCards(theme, onTheme, enabled = !saving, animationsEnabled = motion)
                        2 -> PermissionStep()
                        3 -> UpdateChannelChoice(channel, onChannel)
                        4 -> {
                            if (restoring || restoreResult != null) {
                                WelcomePanel {
                                    val resultLabel = when {
                                        restoring -> R.string.welcome_restore_running
                                        restoreResult == 0 -> R.string.welcome_restore_done
                                        else -> R.string.welcome_restore_failed
                                    }
                                    Text(stringResource(resultLabel), style = MaterialTheme.typography.titleMedium)
                                    if (restoring) LinearProgressIndicator(Modifier.fillMaxWidth())
                                }
                            }
                            WelcomeAction(
                                Icons.Outlined.Restore,
                                R.string.welcome_restore_file,
                                R.string.welcome_restore_file_description,
                                enabled = !restoring,
                                onClick = onRestoreBackup,
                            )
                            WelcomeAction(
                                Icons.Outlined.CloudDownload,
                                R.string.welcome_restore_cloud,
                                R.string.welcome_restore_cloud_description,
                                enabled = !restoring,
                                onClick = onRestoreCloud,
                            )
                            Text(
                                stringResource(R.string.welcome_restore_excludes),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun WelcomePanel(content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = colors.surfaceContainerLow,
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.background(
                Brush.verticalGradient(listOf(colors.primary.copy(alpha = 0.05f), colors.surfaceContainerLow)),
            ).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun WelcomeAction(icon: ImageVector, title: Int, description: Int, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(stringResource(description), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(20.dp))
        }
    }
}
