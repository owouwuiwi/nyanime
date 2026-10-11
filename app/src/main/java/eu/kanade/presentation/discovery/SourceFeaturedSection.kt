package eu.kanade.presentation.discovery

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R
import tachiyomi.domain.discovery.SectionState
import tachiyomi.domain.discovery.SourceHomePage
import tachiyomi.domain.entries.anime.model.Anime
import androidx.compose.ui.res.stringResource as androidStringResource

/** Featured content opens directly from the carousel, without a duplicate browse heading. */
@Composable
fun SourceFeaturedSection(
    state: SectionState<SourceHomePage>,
    title: String,
    refreshKey: Int = 0,
    limit: Int? = null,
    onRetry: () -> Unit,
    onOpen: (Anime) -> Unit,
    onSources: ((Anime) -> Unit)? = null,
    autoplay: Boolean = false,
) {
    val items = state.data?.items.orEmpty().let { if (limit == null) it else it.take(limit) }
    Column {
        HomeLoadingTransition(
            loading = state.awaitingContent,
            placeholder = {
                HomeHeroSkeleton(withBrowseAction = false, withSourceAction = onSources != null)
            },
        ) {
            if (items.isNotEmpty()) {
                SourceFeaturedCarousel(
                    items,
                    refreshKey,
                    state.data?.title ?: title,
                    onSources,
                    autoplay && !state.loading,
                    onOpen,
                )
            } else if (!state.loading && state.error == null) {
                Text(androidStringResource(R.string.home_featured_empty), Modifier.padding(16.dp))
            }
        }
        LoadNotice(state.loading, state.error, state.stale, showLoadingIndicator = false, retry = onRetry)
    }
}
