package tachiyomi.presentation.core.components.material

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * @param refreshing Whether the layout is currently refreshing
 * @param onRefresh Lambda which is invoked when a swipe to refresh gesture is completed.
 * @param enabled Whether the the layout should react to swipe gestures or not.
 * @param indicatorPadding Content padding for the indicator, to inset the indicator in if required.
 * @param indicatorOnGestureOnly Hide the floating indicator for automatic loads.
 * @param content The content containing a vertically scrollable composable.
 */
@Composable
fun PullRefresh(
    refreshing: Boolean,
    enabled: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    indicatorPadding: PaddingValues = PaddingValues(0.dp),
    indicatorOnGestureOnly: Boolean = false,
    content: @Composable () -> Unit,
) {
    val state = rememberPullToRefreshState()
    var gestureRequested by remember { mutableStateOf(false) }
    val currentRefreshing by rememberUpdatedState(refreshing)
    LaunchedEffect(gestureRequested) {
        if (!gestureRequested) return@LaunchedEffect
        try {
            // A rejected or immediately completed refresh must not arm a future automatic load.
            withTimeoutOrNull(1_500L) {
                snapshotFlow { currentRefreshing }.first { it }
            } ?: return@LaunchedEffect
            snapshotFlow { currentRefreshing }.first { !it }
        } finally {
            gestureRequested = false
        }
    }
    val showRefreshing = refreshing && (!indicatorOnGestureOnly || gestureRequested)
    Box(
        modifier = modifier
            .pullToRefresh(
                isRefreshing = showRefreshing,
                state = state,
                enabled = enabled && (!indicatorOnGestureOnly || (!refreshing && !gestureRequested)),
                onRefresh = {
                    if (indicatorOnGestureOnly) gestureRequested = true
                    onRefresh()
                },
            ),
    ) {
        content()

        if (!indicatorOnGestureOnly || gestureRequested || state.distanceFraction > 0f) {
            PullToRefreshDefaults.Indicator(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(indicatorPadding),
                isRefreshing = showRefreshing,
                state = state,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
