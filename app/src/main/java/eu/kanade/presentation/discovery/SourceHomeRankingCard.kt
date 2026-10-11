package eu.kanade.presentation.discovery

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R
import tachiyomi.domain.discovery.homePresentation
import tachiyomi.domain.entries.anime.model.Anime
import androidx.compose.ui.res.stringResource as androidStringResource

@Composable
fun SourceHomeRankingCard(
    anime: Anime,
    position: Int,
    sourceLabel: String,
    onOpen: () -> Unit,
    onSources: (() -> Unit)? = null,
) {
    val presentation = anime.homePresentation
    Row(
        Modifier.width(286.dp).clickable(onClick = onOpen),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${presentation?.rank ?: position}",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.primary,
        )
        Box(Modifier.size(64.dp, 94.dp).clip(RoundedCornerShape(6.dp))) {
            SourceHomeArtwork(anime, Modifier.matchParentSize())
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                anime.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                presentation?.details?.firstOrNull().orEmpty(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                sourceLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
            )
            if (onSources != null && presentation?.choices.orEmpty().size > 1) {
                TextButton(onClick = onSources) { Text(androidStringResource(R.string.home_sources)) }
            }
        }
    }
}
