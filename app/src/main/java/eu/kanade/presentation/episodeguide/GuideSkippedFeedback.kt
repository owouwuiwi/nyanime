package eu.kanade.presentation.episodeguide

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.motion.ModernMotion
import eu.kanade.presentation.motion.modernMotionEnabled
import eu.kanade.tachiyomi.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import tachiyomi.domain.items.episode.interactor.GetEpisode
import tachiyomi.domain.items.episode.model.Episode
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
fun GuideSkippedFeedback(count: Int, onHidden: () -> Unit = {}, onView: () -> Unit) {
    if (count < 1) return
    var visible by remember(count) { mutableStateOf(true) }
    val motion = modernMotionEnabled()
    LaunchedEffect(count) {
        delay(8000)
        visible = false
        delay(ModernMotion.EXIT_MILLIS.toLong())
        onHidden()
    }
    AnimatedVisibility(
        visible,
        enter = if (motion) ModernMotion.enter() else EnterTransition.None,
        exit = if (motion) ModernMotion.exit() else ExitTransition.None,
    ) {
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.guide_skipped, count),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = onView) { Text(stringResource(R.string.guide_view_skipped)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CastGuideSkippedFeedback(ids: List<Long>, play: (Long) -> Unit) {
    var show by remember { mutableStateOf(false) }
    var episodes by remember(ids) { mutableStateOf<List<Episode>>(emptyList()) }
    LaunchedEffect(ids, show) {
        if (show) {
            episodes = withContext(Dispatchers.IO) {
                ids.mapNotNull { Injekt.get<GetEpisode>().await(it) }
            }
        }
    }
    key(ids) { GuideSkippedFeedback(ids.size) { show = true } }
    if (show) {
        ModalBottomSheet(onDismissRequest = { show = false }) {
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 600.dp),
                contentPadding = PaddingValues(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text(stringResource(R.string.guide_skipped, ids.size), style = MaterialTheme.typography.titleLarge)
                }
                items(episodes, key = { it.id }) { episode ->
                    TextButton(
                        onClick = {
                            show = false
                            play(episode.id)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(episode.name) }
                }
            }
        }
    }
}
