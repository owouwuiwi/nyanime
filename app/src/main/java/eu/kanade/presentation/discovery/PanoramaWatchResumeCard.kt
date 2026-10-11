package eu.kanade.presentation.discovery

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R
import androidx.compose.ui.res.stringResource as androidStringResource

internal object PanoramaResumeLayout {
    // One compact bookmark and a peek at the next; tablets show several, not an oversized card.
    fun width(viewport: Dp, count: Int): Dp = (viewport - if (count > 1) 56.dp else 40.dp)
        .coerceIn(1.dp, 380.dp)

    fun coverWidth(width: Dp, height: Dp): Dp = (height * .64f).coerceAtMost(width * .28f)
}

/** A short bookmark: the whole surface resumes, while its separate menu keeps every secondary action. */
@Composable
internal fun PanoramaWatchResumeCard(
    title: String,
    episode: String,
    timing: String?,
    progress: Float?,
    onResume: () -> Unit,
    modifier: Modifier = Modifier,
    foregroundModifier: Modifier = Modifier,
    menu: @Composable (() -> Unit) -> Unit,
    artwork: @Composable (Modifier) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val height = watchResumeHeight()
    val resumeLabel = androidStringResource(R.string.home_resume_action)
    BoxWithConstraints(modifier) {
        val coverWidth = PanoramaResumeLayout.coverWidth(maxWidth, height)
        Surface(
            onClick = onResume,
            modifier = Modifier.fillMaxWidth().height(height),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .3f)),
        ) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(coverWidth).fillMaxHeight()) {
                    artwork(Modifier.fillMaxSize())
                    Box(
                        Modifier.align(Alignment.Center).then(foregroundModifier).size(38.dp)
                            .background(Color.Black.copy(alpha = .76f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.PlayArrow, resumeLabel, Modifier.size(24.dp), tint = Color.White)
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight().then(foregroundModifier)) {
                    Column(
                        Modifier.fillMaxSize().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            minLines = 2,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            episode,
                            modifier = Modifier.padding(end = 36.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            timing ?: resumeLabel,
                            modifier = Modifier.padding(end = 36.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Box(Modifier.align(Alignment.BottomEnd).padding(end = 2.dp, bottom = 4.dp)) {
                        IconButton(onClick = { expanded = true }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Outlined.MoreHoriz, androidStringResource(R.string.home_panorama_options, title))
                        }
                        DropdownMenu(expanded, onDismissRequest = { expanded = false }) { menu { expanded = false } }
                    }
                    if (progress != null) {
                        LinearProgressIndicator(
                            progress = { progress.coerceIn(0f, 1f) },
                            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().height(2.dp),
                            color = panoramaAccent(manga = false),
                            trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f),
                            gapSize = 0.dp,
                            drawStopIndicator = {},
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun watchResumeHeight(): Dp {
    val density = LocalDensity.current
    val typography = MaterialTheme.typography
    val measurer = rememberTextMeasurer(cacheSize = 3)
    return remember(density, typography, measurer) {
        // Measure the actual lines: Android's non-linear font scaling means converting
        // lineHeight from sp alone can underestimate the space needed by large text.
        val textHeight = measurer.measure(
            "M\nM",
            typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        ).size.height +
            measurer.measure("M", typography.bodySmall).size.height +
            measurer.measure("M", typography.labelMedium).size.height
        (with(density) { textHeight.toDp() } + 32.dp).coerceAtLeast(116.dp)
    }
}

@Composable
internal fun PanoramaWatchResumeSkeleton() {
    val height = watchResumeHeight()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = PanoramaResumeLayout.width(maxWidth, 2)
        val coverWidth = PanoramaResumeLayout.coverWidth(width, height)
        HomeSkeleton {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                userScrollEnabled = false,
            ) {
                items(2) {
                    Row(
                        Modifier.width(width).height(height).clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerLow),
                    ) {
                        SkeletonBlock(Modifier.width(coverWidth).fillMaxHeight())
                        Column(Modifier.weight(1f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SkeletonBlock(Modifier.fillMaxWidth(.9f).height(16.dp))
                            SkeletonBlock(Modifier.fillMaxWidth(.65f).height(16.dp))
                            SkeletonBlock(Modifier.fillMaxWidth(.55f).height(12.dp))
                            SkeletonBlock(Modifier.fillMaxWidth(.45f).height(10.dp))
                        }
                    }
                }
            }
        }
    }
}
