package eu.kanade.tachiyomi.data.releases

import eu.kanade.domain.ui.model.NavStyle
import eu.kanade.tachiyomi.ui.browse.BrowseTab
import eu.kanade.tachiyomi.ui.history.HistoriesTab
import eu.kanade.tachiyomi.ui.library.LibrariesTab
import eu.kanade.tachiyomi.ui.library.anime.AnimeLibraryTab
import eu.kanade.tachiyomi.ui.library.manga.MangaLibraryTab
import eu.kanade.tachiyomi.ui.more.MoreTab
import eu.kanade.tachiyomi.ui.releases.ReleasesTab
import eu.kanade.tachiyomi.ui.search.AtlasSearchTab
import eu.kanade.tachiyomi.ui.updates.UpdatesTab
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReleaseNavigationTest {
    @Test fun searchConnectsLibraryAndReleasesWithoutCrowdingTheBar() {
        val visible = NavStyle.DISCOVERY.visibleTabs
        assertEquals(5, visible.size)
        assertEquals(visible.indexOf(LibrariesTab) + 1, visible.indexOf(AtlasSearchTab))
        assertEquals(visible.indexOf(AtlasSearchTab) + 1, visible.indexOf(ReleasesTab))
    }

    @Test fun everyLegacyNavigationKeepsExistingDestinationsReachable() {
        for (style in NavStyle.entries.filterNot { it == NavStyle.DISCOVERY }) {
            val visible = style.visibleTabs
            assertEquals(5, visible.size)
            assertEquals(5, visible.distinct().size)
            assertTrue(ReleasesTab in visible)
            val reachable = visible + style.overflowTabs
            assertTrue(
                reachable.containsAll(
                    listOf(
                        AnimeLibraryTab,
                        MangaLibraryTab,
                        UpdatesTab,
                        HistoriesTab,
                        BrowseTab,
                        MoreTab,
                    ),
                ),
            )
        }
    }
}
