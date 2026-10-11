package eu.kanade.presentation.discovery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.privacy.nsfwPrivacy
import tachiyomi.domain.discovery.homePresentation
import tachiyomi.domain.entries.anime.model.Anime

@Composable
fun SourceHomePosterCard(
    anime: Anime,
    sourceLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    refreshKey: Int = 0,
    onSources: (() -> Unit)? = null,
) {
    val presentation = anime.homePresentation
    val sourceCount = presentation?.choices.orEmpty().size
    Box(modifier.nsfwPrivacy(anime)) {
        PosterCard(
            title = anime.title,
            cover = anime,
            subtitle = (presentation?.details.orEmpty() + sourceLabel).filter { it.isNotBlank() }.joinToString("\n"),
            onClick = onClick,
            badges = presentation?.badges.orEmpty(),
            subtitleMaxLines = 12,
            artworkRefreshKey = refreshKey,
        )
        if (onSources != null && sourceCount > 1) {
            Surface(
                onClick = onSources,
                modifier = Modifier.align(Alignment.TopStart).padding(5.dp)
                    .semantics { contentDescription = "Scegli tra $sourceCount fonti" },
                shape = RoundedCornerShape(14.dp),
                color = Color.Black.copy(alpha = 0.78f),
            ) {
                Row(
                    Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.SwapHoriz, null, tint = Color.White)
                    Text("$sourceCount", color = Color.White)
                }
            }
        }
    }
}
