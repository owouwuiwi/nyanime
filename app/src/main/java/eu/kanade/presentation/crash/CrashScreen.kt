package eu.kanade.presentation.crash

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.motion.ModernMotion
import eu.kanade.presentation.motion.appMotionEnabled
import eu.kanade.presentation.theme.NyanimeWordmark
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.crash.crashDetails

/** Recovery uses local assets and callbacks, without initializing the player or extensions. */
@Composable
fun CrashScreen(
    exception: Throwable?,
    preparingReport: Boolean,
    reportFailed: Boolean,
    onShareClick: () -> Unit,
    onRestartClick: () -> Unit,
    onCloseClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val details = remember(exception) { crashDetails(exception) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val motion = appMotionEnabled()

    Box(
        Modifier.fillMaxSize().background(colors.background).background(
            Brush.verticalGradient(listOf(colors.primary.copy(alpha = 0.09f), colors.background), endY = 650f),
        ).safeDrawingPadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        BoxWithConstraints(Modifier.widthIn(max = 560.dp).fillMaxSize()) {
            val pinnedActions = maxHeight >= 600.dp
            val scroll = rememberScrollState()
            Column(
                (if (pinnedActions) Modifier.fillMaxSize() else Modifier.fillMaxWidth().verticalScroll(scroll))
                    .heightIn(min = maxHeight).padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(if (pinnedActions) Modifier.weight(1f).verticalScroll(scroll) else Modifier) {
                    NyanimeWordmark()
                    Spacer(Modifier.height(24.dp))
                    Surface(
                        shape = RoundedCornerShape(28.dp),
                        color = colors.primary.copy(alpha = 0.12f),
                        contentColor = colors.primary,
                    ) {
                        Icon(Icons.Outlined.RestartAlt, null, Modifier.padding(18.dp).size(36.dp))
                    }
                    Spacer(Modifier.height(20.dp))
                    Text(
                        stringResource(R.string.crash_recovery_title),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.onBackground,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.crash_recovery_description),
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(20.dp))
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = colors.surfaceContainer,
                        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.5f)),
                    ) {
                        Column {
                            Surface(
                                onClick = { expanded = !expanded },
                                color = colors.surfaceContainer,
                                contentColor = colors.onSurface,
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().heightIn(
                                        min = 64.dp,
                                    ).padding(horizontal = 18.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Text(
                                        stringResource(R.string.crash_recovery_details),
                                        Modifier.weight(1f),
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    val detailAction = if (expanded) {
                                        R.string.crash_recovery_collapse
                                    } else {
                                        R.string.crash_recovery_expand
                                    }
                                    Icon(
                                        if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ChevronRight,
                                        stringResource(detailAction),
                                    )
                                }
                            }
                            AnimatedVisibility(
                                visible = expanded,
                                enter = ModernMotion.enter(if (motion) ModernMotion.RESIZE_MILLIS else 0) +
                                    expandVertically(tween(if (motion) ModernMotion.RESIZE_MILLIS else 0)),
                                exit = fadeOut(tween(if (motion) ModernMotion.EXIT_MILLIS else 0)) +
                                    shrinkVertically(tween(if (motion) ModernMotion.EXIT_MILLIS else 0)),
                            ) {
                                Column(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 18.dp)) {
                                    SelectionContainer {
                                        Text(
                                            details?.text ?: stringResource(R.string.crash_recovery_no_details),
                                            Modifier.fillMaxWidth().heightIn(
                                                max = 200.dp,
                                            ).verticalScroll(rememberScrollState()),
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 12.sp,
                                            ),
                                            color = colors.onSurfaceVariant,
                                        )
                                    }
                                    if (details?.truncated == true) {
                                        Text(
                                            stringResource(R.string.crash_recovery_truncated),
                                            Modifier.padding(top = 12.dp),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colors.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Text(
                        stringResource(
                            R.string.crash_recovery_version,
                            BuildConfig.VERSION_NAME,
                            Build.VERSION.RELEASE,
                        ),
                        Modifier.padding(top = 12.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
                Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (reportFailed) {
                        Text(
                            stringResource(R.string.crash_recovery_report_failed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.error,
                        )
                    }
                    Button(
                        onClick = onRestartClick,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.RestartAlt, null)
                            Text(
                                stringResource(R.string.crash_recovery_restart),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = onShareClick,
                        enabled = !preparingReport,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (preparingReport) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Outlined.Share, null)
                            }
                            val shareLabel = if (preparingReport) {
                                R.string.crash_recovery_preparing
                            } else {
                                R.string.crash_recovery_share
                            }
                            Text(
                                stringResource(shareLabel),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                    Text(
                        stringResource(R.string.crash_recovery_privacy),
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    TextButton(onClick = onCloseClick, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text(stringResource(R.string.crash_recovery_close))
                    }
                }
            }
        }
    }
}

@PreviewLightDark
@Composable
private fun CrashScreenPreview() {
    TachiyomiPreviewTheme {
        CrashScreen(RuntimeException("Example failure"), false, false, {}, {}, {})
    }
}
