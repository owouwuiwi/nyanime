package it.lorelore.nyanime.updates

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.motion.ModernMotion
import eu.kanade.presentation.motion.appMotionEnabled
import eu.kanade.tachiyomi.R
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Owns the page before navigation consumes a launch intent or opens other upgrade notices. */
@Composable
fun InitialSupportEndUpdateGate(onReady: () -> Unit, content: @Composable () -> Unit) {
    val notice = remember { SupportEndUpgradeNotice(Injekt.get()) }
    val state by notice.state.collectAsState()
    val motion = appMotionEnabled()
    AnimatedContent(
        targetState = state == SupportEndUpgradeNotice.PENDING,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = { ModernMotion.transform(motion) },
        label = "supportEndUpgrade",
    ) { pending ->
        if (pending) {
            LaunchedEffect(Unit) { onReady() }
            BackHandler(onBack = notice::acknowledge)
            SupportEndUpdatePage(onContinue = notice::acknowledge)
        } else {
            content()
        }
    }
}

/** A single message, with a shorter equivalent only when the viewport cannot fit the full copy. */
@Composable
internal fun SupportEndUpdatePage(onContinue: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Scaffold(containerColor = colors.background, contentWindowInsets = WindowInsets.safeDrawing) { insets ->
        Box(
            Modifier.fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(colors.primary.copy(alpha = 0.10f), colors.background),
                    ),
                )
                .padding(insets)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            BoxWithConstraints(Modifier.widthIn(max = 640.dp).fillMaxWidth().fillMaxHeight()) {
                val fontScale = LocalDensity.current.fontScale
                val compact = maxHeight < 600.dp || fontScale > 1.15f
                val concise = maxHeight < 400.dp || fontScale >= 1.6f
                val tight = maxHeight < 360.dp
                val showThanks = !tight && !(concise && maxHeight < 600.dp)
                val bodyStyle =
                    when {
                        concise && fontScale > 1.5f -> MaterialTheme.typography.bodySmall
                        compact -> MaterialTheme.typography.bodyMedium
                        else -> MaterialTheme.typography.bodyLarge
                    }
                val message =
                    stringResource(
                        if (concise) {
                            R.string.support_end_compact_body
                        } else {
                            R.string.support_end_body
                        },
                    )
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(28.dp),
                    color = colors.surfaceContainerLow.copy(alpha = 0.86f),
                    border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.35f)),
                ) {
                    Column(
                        Modifier.fillMaxSize()
                            .padding(
                                horizontal = if (tight) 12.dp else 22.dp,
                                vertical = if (tight) 8.dp else 20.dp,
                            ),
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement =
                            Arrangement.spacedBy(if (compact) 12.dp else 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SupportEndHeart(if (compact) 36 else 56)
                            Text(
                                stringResource(R.string.support_end_title),
                                Modifier.weight(1f).semantics { heading() },
                                style =
                                if (compact) {
                                    MaterialTheme.typography.titleLarge
                                } else {
                                    MaterialTheme.typography.headlineSmall
                                },
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Text(
                            message,
                            modifier =
                            Modifier.fillMaxWidth()
                                .padding(vertical = if (tight) 4.dp else 16.dp),
                            style = bodyStyle,
                            color = colors.onSurface,
                        )
                        Column(
                            Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(if (tight) 4.dp else 12.dp),
                        ) {
                            if (showThanks) {
                                Text(
                                    stringResource(R.string.support_end_thanks),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.onSurfaceVariant,
                                )
                            }
                            Button(
                                onClick = onContinue,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                                shape = RoundedCornerShape(18.dp),
                            ) {
                                Text(stringResource(R.string.support_end_continue))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SupportEndHeart(size: Int) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.size(size.dp)
            .background(
                Brush.linearGradient(
                    listOf(colors.primary.copy(alpha = 0.18f), colors.primary.copy(alpha = 0.05f)),
                ),
                RoundedCornerShape((size / 3).dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Favorite, null, Modifier.size((size / 2).dp), tint = colors.primary)
    }
}
