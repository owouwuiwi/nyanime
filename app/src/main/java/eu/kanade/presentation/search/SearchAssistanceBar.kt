package eu.kanade.presentation.search

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.motion.ModernMotion
import eu.kanade.presentation.motion.appMotionEnabled
import eu.kanade.tachiyomi.R
import tachiyomi.domain.search.SearchAssistance

/** Suggestions are titles, not playable results. A reserved strip keeps progressive updates stable. */
@Composable
fun SearchAssistanceBar(state: SearchAssistance, onSuggestion: (String) -> Unit, onExact: () -> Unit) {
    if (state.query.length < 3 || !state.enabled) return
    val motionEnabled = appMotionEnabled()
    val messageHeight =
        with(LocalDensity.current) { MaterialTheme.typography.labelMedium.lineHeight.toDp() } * 2 + 10.dp
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = maxOf(48.dp, messageHeight)),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Crossfade(
                state.exact to state.correctedQuery,
                animationSpec = tween(if (motionEnabled) ModernMotion.PAGE_MILLIS else 0),
                modifier = Modifier.weight(1f),
            ) { (exact, corrected) ->
                Text(
                    when {
                        exact -> stringResource(R.string.search_exact_active)
                        corrected != null -> stringResource(R.string.search_recovered, corrected)
                        state.loading -> stringResource(R.string.search_finding_titles)
                        state.unavailable -> stringResource(R.string.search_assistance_unavailable)
                        state.suggestions.isNotEmpty() -> stringResource(R.string.search_perhaps)
                        state.resolved -> stringResource(R.string.search_no_suggestions)
                        else -> stringResource(R.string.search_original_preserved)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            TextButton(onClick = onExact) {
                Text(
                    stringResource(if (state.exact) R.string.search_smart_resume else R.string.search_exact),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        LazyRow(Modifier.heightIn(min = 48.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.suggestions, key = { it.key }) { suggestion ->
                AssistChip(
                    modifier = Modifier.widthIn(max = 240.dp),
                    onClick = { onSuggestion(suggestion.title) },
                    label = { Text(suggestion.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
        }
    }
}
