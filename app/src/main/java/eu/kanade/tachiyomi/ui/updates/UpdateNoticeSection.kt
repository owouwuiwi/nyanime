package eu.kanade.tachiyomi.ui.updates

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import tachiyomi.core.common.preference.Preference

/** A notice is acknowledged only when the section heading actually reaches the viewport. */
@Composable
fun AcknowledgeUpdateNoticeWhenVisible(
    listState: LazyListState,
    sectionKey: String,
    updateKeys: Collection<String>,
    enabled: Boolean,
    lastSeenPreference: Preference<Long>,
    obscuredTop: Dp = 0.dp,
) {
    val topInset = with(LocalDensity.current) { obscuredTop.roundToPx() }
    LaunchedEffect(listState, sectionKey, updateKeys, enabled, lastSeenPreference, topInset) {
        if (!enabled || updateKeys.isEmpty()) return@LaunchedEffect
        snapshotFlow {
            val layout = listState.layoutInfo
            layout.visibleItemsInfo.any {
                it.key == sectionKey && it.offset >= layout.viewportStartOffset + topInset
            }
        }.first { it }
        markLibraryUpdateNoticesSeen(lastSeenPreference, updateKeys)
    }
}
