package eu.kanade.tachiyomi.benchmark

import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.entries.details.DetailPart
import eu.kanade.presentation.entries.details.TitleDetailsScreen
import eu.kanade.presentation.motion.ModernMotion
import eu.kanade.presentation.motion.PosterNavigationTransition

/** The production details page, resolving across separate composition branches like its model. */
@Composable
fun DetailsLoadingProbe(manga: Boolean) {
    var loaded by remember { mutableStateOf(false) }
    val transition = updateTransition(Unit, label = "details_probe")
    Box(Modifier.fillMaxSize()) {
        PosterNavigationTransition(
            navigation = transition,
            routeKey = { "detail_probe" },
            retainedRoutes = setOf("detail_probe"),
            enabled = true,
            transitionSpec = { ModernMotion.transform(true) },
        ) {
            if (loaded) {
                DetailsFixture(manga, true)
            } else {
                DetailsFixture(manga, false)
            }
        }
        Button(onClick = { loaded = !loaded }, modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)) {
            Text(if (loaded) "DETAIL_READY" else "Resolve details")
        }
    }
}

@Composable
private fun DetailsFixture(manga: Boolean, loaded: Boolean) {
    val parts = remember {
        (1..20).map { index ->
            DetailPart(index.toLong(), index.toString(), "Part $index", "", false, false, false, false)
        }
    }
    TitleDetailsScreen(
        entryId = if (loaded) 42 else -1,
        manga = manga,
        title = "A long example title for a stable detail page",
        kicker = "Example",
        subtitle = if (loaded) "Example library with a deliberately long description that wraps onto two lines" else "",
        artwork = null,
        parts = if (loaded) parts else emptyList(),
        currentId = if (loaded) 1 else null,
        primaryText = if (loaded) "Resume part 1" else "",
        primaryInformation = if (loaded) "Page 12" else "",
        totalCount = if (loaded) 20 else 0,
        completedCount = if (loaded) 7 else 0,
        favorite = false,
        loading = !loaded,
        onBack = {}, onContinue = {}, onLibrary = {}, onCover = {}, onFilter = {}, onRefresh = {}, onOpenPart = {},
        follow = {}, details = {}, actions = emptyList(), partActions = { _, _ -> emptyList() }, download = {},
        swipe = { _, content -> content() },
    )
}
