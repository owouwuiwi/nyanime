package eu.kanade.tachiyomi.discovery

import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.NavStyle
import eu.kanade.domain.ui.model.StartScreen
import eu.kanade.tachiyomi.ui.browse.BrowseTab
import eu.kanade.tachiyomi.ui.discovery.DiscoveryTab
import eu.kanade.tachiyomi.ui.history.HistoriesTab
import eu.kanade.tachiyomi.ui.library.LibrariesTab
import eu.kanade.tachiyomi.ui.library.anime.AnimeLibraryTab
import eu.kanade.tachiyomi.ui.library.manga.MangaLibraryTab
import eu.kanade.tachiyomi.ui.more.MoreTab
import eu.kanade.tachiyomi.ui.releases.ReleasesTab
import eu.kanade.tachiyomi.ui.search.AtlasSearchTab
import eu.kanade.tachiyomi.ui.updates.UpdatesTab
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

class DiscoveryNavigationTest {
    @Test
    fun `new navigation keeps all destinations with no duplicate or lost tab`() {
        assertEquals(
            listOf(DiscoveryTab, LibrariesTab, AtlasSearchTab, ReleasesTab, MoreTab),
            NavStyle.DISCOVERY.visibleTabs,
        )
        assertEquals(listOf(BrowseTab, UpdatesTab, HistoriesTab), NavStyle.DISCOVERY.overflowTabs)
        assertEquals(8, (NavStyle.DISCOVERY.visibleTabs + NavStyle.DISCOVERY.overflowTabs).distinct().size)
        NavStyle.entries.filterNot { it == NavStyle.DISCOVERY }.forEach {
            val all = it.visibleTabs + it.overflowTabs
            assertEquals(5, it.visibleTabs.size)
            assertEquals(8, all.size)
            assertEquals(
                setOf(
                    DiscoveryTab,
                    AnimeLibraryTab,
                    MangaLibraryTab,
                    UpdatesTab,
                    HistoriesTab,
                    BrowseTab,
                    MoreTab,
                    ReleasesTab,
                ),
                all.toSet(),
            )
        }
    }

    @Test
    fun `one time migration activates Home without overriding later customization`() {
        val store = mockk<PreferenceStore>()
        val start = stored(StartScreen.ANIME)
        val navigation = stored(NavStyle.MOVE_HISTORY_TO_MORE)
        val migrated = stored(false)
        every { store.getObject<StartScreen>("start_screen", any(), any(), any()) } returns start
        every { store.getObject<NavStyle>("bottom_rail_nav_style", any(), any(), any()) } returns navigation
        every { store.getBoolean("fork_discovery_navigation_v1", false) } returns migrated
        val preferences = UiPreferences(store)
        preferences.installDiscoveryNavigationOnce()
        assertEquals(StartScreen.HOME, start.get())
        assertEquals(NavStyle.DISCOVERY, navigation.get())
        assertTrue(migrated.get())
        start.set(StartScreen.ANIME)
        navigation.set(NavStyle.MOVE_BROWSE_TO_MORE)
        preferences.installDiscoveryNavigationOnce()
        assertEquals(StartScreen.ANIME, start.get())
        assertEquals(NavStyle.MOVE_BROWSE_TO_MORE, navigation.get())
    }

    private inline fun <reified T : Any> stored(initial: T): Preference<T> {
        var current = initial
        return mockk<Preference<T>>().also {
            every { it.get() } answers { current }
            every { it.set(any()) } answers { current = firstArg() }
        }
    }
}
