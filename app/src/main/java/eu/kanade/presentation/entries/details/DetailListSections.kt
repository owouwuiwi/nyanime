package eu.kanade.presentation.entries.details

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable

/** Owns prefix keys/order so item navigation and presentation cannot count different sections. */
@OptIn(ExperimentalFoundationApi::class)
internal fun LazyListScope.detailsPrefix(content: @Composable (DetailListSection) -> Unit) {
    DetailListSection.entries.forEach { section ->
        if (section == DetailListSection.TABS) {
            stickyHeader(key = section.key) { content(section) }
        } else {
            item(key = section.key, contentType = section.key) { content(section) }
        }
    }
}
