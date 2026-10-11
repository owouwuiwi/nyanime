package eu.kanade.tachiyomi.ui.player.controls.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.player.Anime4KHealth
import eu.kanade.tachiyomi.ui.player.Anime4KSmartDiagnostics
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

/** Opt-in, low-cost diagnostic view for validating Smart decisions on a real device. */
@Composable
fun Anime4KDiagnosticsOverlay(
    diagnostics: Anime4KSmartDiagnostics,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.widthIn(max = 300.dp),
        color = Color.Black.copy(alpha = 0.78f),
        contentColor = Color.White,
        shape = MaterialTheme.shapes.small,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MaterialTheme.padding.small),
        ) {
            Text(
                text = "${stringResource(AYMR.strings.pref_anime4k)} ${diagnostics.mode.name}",
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = "${dimensions(diagnostics.sourceWidth, diagnostics.sourceHeight)} → " +
                    dimensions(diagnostics.targetWidth, diagnostics.targetHeight),
                style = MaterialTheme.typography.labelSmall,
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "FPS ${decimal(diagnostics.targetFramesPerSecond)}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    text = "${diagnostics.health.name}${if (diagnostics.probing) " · probe" else ""}",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Text(
                text = "GPU ${decimal(diagnostics.renderTimeMillis)} ms / frame",
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                text = "headroom ${decimal(diagnostics.headroom)} · conf " +
                    percent(diagnostics.confidence),
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                text = "drop ${percent(diagnostics.outputDropRate)} · dec " +
                    "${percent(diagnostics.decoderDropRate)} · delay " +
                    "${percent(diagnostics.delayedFrameRate)} · mist " +
                    percent(diagnostics.mistimedFrameRate),
                style = MaterialTheme.typography.labelSmall,
            )
            if (diagnostics.suspended || diagnostics.reason != null) {
                Text(
                    text = (if (diagnostics.suspended) "Suspended · " else "") +
                        (diagnostics.reason ?: ""),
                    color = if (diagnostics.suspended || diagnostics.health == Anime4KHealth.Severe) {
                        MaterialTheme.colorScheme.error
                    } else {
                        Color.White
                    },
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

private fun dimensions(width: Int?, height: Int?): String =
    if (width != null && height != null) "$width×$height" else "—"

private fun decimal(value: Double?): String = value?.let { "%.2f".format(it) } ?: "—"

private fun percent(value: Double?): String = value?.let { "%.2f%%".format(it * 100.0) } ?: "—"
