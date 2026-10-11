package eu.kanade.domain.ui.model

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.browse.BrowseTab
import eu.kanade.tachiyomi.ui.history.HistoriesTab
import eu.kanade.tachiyomi.ui.library.LibrariesTab
import eu.kanade.tachiyomi.ui.library.anime.AnimeLibraryTab
import eu.kanade.tachiyomi.ui.library.manga.MangaLibraryTab
import eu.kanade.tachiyomi.ui.more.MoreTab
import eu.kanade.tachiyomi.ui.updates.UpdatesTab
import tachiyomi.i18n.aniyomi.AYMR

enum class NavStyle(
    val titleRes: StringResource,
    private val moreTab: Tab,
) {
    DISCOVERY(titleRes = AYMR.strings.discovery_navigation, moreTab = HistoriesTab),
    MOVE_MANGA_TO_MORE(titleRes = AYMR.strings.pref_bottom_nav_no_manga, moreTab = MangaLibraryTab),
    MOVE_UPDATES_TO_MORE(titleRes = AYMR.strings.pref_bottom_nav_no_updates, moreTab = UpdatesTab),
    MOVE_HISTORY_TO_MORE(titleRes = AYMR.strings.pref_bottom_nav_no_history, moreTab = HistoriesTab),
    MOVE_BROWSE_TO_MORE(titleRes = AYMR.strings.pref_bottom_nav_no_browse, moreTab = BrowseTab),
    ;

    val moreIcon: ImageVector
        @Composable
        get() = when (this) {
            DISCOVERY -> Icons.Outlined.History
            MOVE_MANGA_TO_MORE -> Icons.Outlined.CollectionsBookmark
            MOVE_UPDATES_TO_MORE -> ImageVector.vectorResource(id = R.drawable.ic_updates_outline_24dp)
            MOVE_HISTORY_TO_MORE -> Icons.Outlined.History
            MOVE_BROWSE_TO_MORE -> Icons.Outlined.Explore
        }

    @Composable
    fun overflowIcon(tab: Tab): ImageVector = when (tab) {
        eu.kanade.tachiyomi.ui.discovery.DiscoveryTab -> Icons.Outlined.Home
        BrowseTab -> Icons.Outlined.Explore
        HistoriesTab -> Icons.Outlined.History
        UpdatesTab -> ImageVector.vectorResource(id = R.drawable.ic_updates_outline_24dp)
        else -> moreIcon
    }

    val overflowTabs: List<Tab>
        get() = if (this == DISCOVERY) {
            listOf(BrowseTab, UpdatesTab, HistoriesTab)
        } else {
            listOf(moreTab, eu.kanade.tachiyomi.ui.discovery.DiscoveryTab, HistoriesTab, UpdatesTab)
                .distinct().filterNot { it in visibleTabs }
        }

    val visibleTabs: List<Tab>
        get() {
            if (this == DISCOVERY) {
                return listOf(
                    eu.kanade.tachiyomi.ui.discovery.DiscoveryTab,
                    LibrariesTab,
                    eu.kanade.tachiyomi.ui.search.AtlasSearchTab,
                    eu.kanade.tachiyomi.ui.releases.ReleasesTab,
                    MoreTab,
                )
            }
            return mutableListOf(
                AnimeLibraryTab,
                MangaLibraryTab,
                UpdatesTab,
                HistoriesTab,
                BrowseTab,
                MoreTab,
            ).apply {
                remove(this@NavStyle.moreTab)
                remove(HistoriesTab)
                if (size == 5) remove(UpdatesTab)
                add(1, eu.kanade.tachiyomi.ui.releases.ReleasesTab)
            }
        }
}
