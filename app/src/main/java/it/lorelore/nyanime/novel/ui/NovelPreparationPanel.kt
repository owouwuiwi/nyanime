package it.lorelore.nyanime.novel.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.motion.ModernMotion
import eu.kanade.presentation.motion.appMotionEnabled
import eu.kanade.tachiyomi.R
import it.lorelore.nyanime.novel.source.NovelPreparationPhase
import it.lorelore.nyanime.novel.source.NovelPreparationProgress

@Composable
internal fun NovelPreparationPanel(
    progress: NovelPreparationProgress?,
    onPause: () -> Unit,
    onResume: () -> Unit,
) {
    val motion = appMotionEnabled()
    AnimatedVisibility(
        visible = progress != null && progress.phase != NovelPreparationPhase.READY,
        enter =
        if (motion) {
            ModernMotion.enter() +
                expandVertically(
                    tween(ModernMotion.RESIZE_MILLIS, easing = ModernMotion.SmoothOutEasing),
                )
        } else {
            EnterTransition.None
        },
        exit =
        if (motion) {
            ModernMotion.exit() +
                shrinkVertically(
                    tween(ModernMotion.RESIZE_MILLIS, easing = ModernMotion.SmoothOutEasing),
                )
        } else {
            ExitTransition.None
        },
    ) {
        val running =
            progress?.phase in setOf(NovelPreparationPhase.RUNNING, NovelPreparationPhase.WAITING)
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(
                        when (progress?.phase) {
                            NovelPreparationPhase.FAILED -> R.string.novel_index_failed
                            NovelPreparationPhase.PAUSED -> R.string.novel_index_paused
                            NovelPreparationPhase.WAITING -> R.string.novel_index_waiting
                            else -> R.string.novel_index_preparing
                        },
                    ),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    stringResource(
                        R.string.novel_index_counts,
                        progress?.pages ?: 0,
                        progress?.found ?: 0,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (running && motion) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    stringResource(
                        if (progress?.readyAvailable == true) {
                            R.string.novel_index_saved_available
                        } else {
                            R.string.novel_index_resume_explanation
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(if (running) onPause else onResume) {
                        Text(
                            stringResource(
                                if (running) {
                                    R.string.novel_index_pause
                                } else {
                                    R.string.novel_index_resume
                                },
                            ),
                        )
                    }
                }
            }
        }
    }
}
