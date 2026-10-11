package it.lorelore.nyanime.studio

import eu.kanade.tachiyomi.data.discovery.ExtensionHomeManifest

/** The existing Home/filter gateway consumes exactly the same contract as installed extensions. */
fun StudioHomeProvider.homeManifest(): ExtensionHomeManifest? = definition.home?.let { home ->
    ExtensionHomeManifest(
        id = "personal-${definition.uuid.take(8)}", title = home.title,
        source = ExtensionHomeManifest.Source(definition.name, definition.language),
        sections = home.sections.map { section ->
            ExtensionHomeManifest.Section(
                id = section.id,
                title = section.title,
                filters = section.filters + (StudioBlueprints.HOME_FILTER to section.id),
                layout = section.layout,
                group = section.groupId?.let {
                    ExtensionHomeManifest.Group(it, section.groupTitle.orEmpty(), section.groupTab.orEmpty())
                },
                dateFilter = section.dateFilter,
                moreFilters = section.moreFilters?.plus(StudioBlueprints.HOME_FILTER to section.id),
            )
        },
        search = ExtensionHomeManifest.Section("search", home.title),
        categories = home.categoryFilter?.let { ExtensionHomeManifest.Categories(it, home.excludedCategories) },
        primary = home.primary, browseFilters = definition.filters.map { it.title },
        random = home.randomFlow?.let {
            ExtensionHomeManifest.Section("random", home.title, mapOf(StudioBlueprints.HOME_FILTER to it))
        },
        randomEpisode = home.randomEntryFlow?.let {
            ExtensionHomeManifest.Section("random-episode", home.title, mapOf(StudioBlueprints.HOME_FILTER to it))
        },
    )
}
