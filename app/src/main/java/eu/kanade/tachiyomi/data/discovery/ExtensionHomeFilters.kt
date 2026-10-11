package eu.kanade.tachiyomi.data.discovery

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import tachiyomi.domain.discovery.SourceHomeFilter
import tachiyomi.domain.discovery.SourceHomeSection
import tachiyomi.domain.discovery.SourceHomeSectionGroup

/** Each request owns fresh filter instances; capabilities depend on labels, never their positions. */
object ExtensionHomeFilters {
    fun sections(manifest: ExtensionHomeManifest, filters: AnimeFilterList) = manifest.sections.map {
        SourceHomeSection(
            it.id,
            it.title,
            manifest.defaults + it.filters,
            it.layout.takeIf { it in setOf("featured", "ranking") } ?: "posters",
            it.group?.let { group -> SourceHomeSectionGroup(group.id, group.title, group.tab) },
            it.dateFilter?.takeIf { label -> textFilter(filters, label) != null },
            it.moreFilters?.let { values -> manifest.defaults + values },
        )
    }.filter { supports(filters, it) }

    fun categories(manifest: ExtensionHomeManifest, filters: AnimeFilterList): List<SourceHomeSection> {
        val category = manifest.categories ?: return emptyList()
        val control = filters.singleOrNull { it.name == category.filter }
        val values = when (control) {
            is AnimeFilter.Select<*> -> control.values.filterIsInstance<String>()
            is AnimeFilter.Group<*> -> control.state.filterIsInstance<AnimeFilter.CheckBox>().map { it.name }
            else -> emptyList()
        }
        return values.filter { it !in category.exclude && it.isNotBlank() && it.length <= 100 }
            .distinct().take(100).map {
                SourceHomeSection(
                    "category:$it",
                    it,
                    manifest.defaults +
                        if (control is AnimeFilter.Select<*>) mapOf(category.filter to it) else emptyMap(),
                    browseValues = mapOf(category.filter to listOf(it)),
                )
            }.filter { supports(filters, it) }
    }

    fun search(manifest: ExtensionHomeManifest, filters: AnimeFilterList) = manifest.search?.let {
        SourceHomeSection(it.id, it.title, manifest.defaults + it.filters)
    }?.takeIf { supports(filters, it) }

    fun random(manifest: ExtensionHomeManifest, filters: AnimeFilterList) = manifest.random?.let {
        SourceHomeSection(it.id, it.title, manifest.defaults + it.filters)
    }?.takeIf { supports(filters, it) }

    fun randomEpisode(manifest: ExtensionHomeManifest, filters: AnimeFilterList) = manifest.randomEpisode?.let {
        SourceHomeSection(it.id, it.title, manifest.defaults + it.filters)
    }?.takeIf { supports(filters, it) }

    fun apply(filters: AnimeFilterList, section: SourceHomeSection, date: String? = null): AnimeFilterList {
        require(supports(filters, section)) { "Aggiorna l’estensione: filtri non compatibili" }
        section.selections.forEach { (label, value) ->
            val filter = requireNotNull(select(filters, label))
            filter.state = filter.values.indexOfFirst { it == value }
        }
        if (date != null) {
            // Validate even callers that do not construct a SourceHomeRequest themselves.
            tachiyomi.domain.discovery.SourceHomeRequest(section.id, date = date)
            requireNotNull(section.dateFilter?.let { textFilter(filters, it) }) {
                "La fonte non supporta la scelta della data"
            }.state = date
        } else {
            section.dateFilter?.let { textFilter(filters, it)?.state = "" }
        }
        return filters
    }

    fun browseFilters(manifest: ExtensionHomeManifest, filters: AnimeFilterList): List<SourceHomeFilter> {
        val searchDefaults = manifest.defaults + manifest.search?.filters.orEmpty()
        return (manifest.browseFilters + listOfNotNull(manifest.categories?.filter)).distinct().mapNotNull { name ->
            val filter = filters.singleOrNull { it.name == name } ?: return@mapNotNull null
            when (filter) {
                is AnimeFilter.Select<*> -> {
                    val options = filter.values.filterIsInstance<String>()
                    if (options.size != filter.values.size ||
                        options.isEmpty() ||
                        options.size > 200
                    ) {
                        return@mapNotNull null
                    }
                    SourceHomeFilter(
                        name,
                        SourceHomeFilter.Kind.SINGLE,
                        options,
                        listOfNotNull(searchDefaults[name] ?: options.getOrNull(filter.state)),
                    )
                }
                is AnimeFilter.Group<*> -> {
                    val checks = filter.state.filterIsInstance<AnimeFilter.CheckBox>()
                    if (checks.size != filter.state.size ||
                        checks.isEmpty() ||
                        checks.size > 200
                    ) {
                        return@mapNotNull null
                    }
                    SourceHomeFilter(
                        name,
                        SourceHomeFilter.Kind.MULTIPLE,
                        checks.map { it.name },
                        checks.filter { it.state }.map { it.name },
                    )
                }
                is AnimeFilter.Text -> SourceHomeFilter(
                    name,
                    SourceHomeFilter.Kind.TEXT,
                    defaults = listOf(filter.state),
                )
                else -> null
            }
        }.filter { it.options.all { option -> option.isNotBlank() && option.length <= 100 } }
    }

    fun applyBrowse(filters: AnimeFilterList, definitions: List<SourceHomeFilter>, values: Map<String, List<String>>) {
        values.forEach { (name, selection) ->
            val definition = requireNotNull(definitions.singleOrNull { it.name == name }) {
                "Filtro non più disponibile: aggiorna l’estensione"
            }
            require(definition.accepts(selection)) { "Aggiorna l’estensione: selezione non più disponibile" }
            when (val filter = filters.singleOrNull { it.name == name }) {
                is AnimeFilter.Select<*> -> filter.state = filter.values.indexOfFirst { it == selection.single() }
                is AnimeFilter.Group<*> -> filter.state.filterIsInstance<AnimeFilter.CheckBox>().forEach {
                    it.state = it.name in selection
                }
                is AnimeFilter.Text -> filter.state = selection.firstOrNull().orEmpty()
                else -> error("Aggiorna l’estensione: filtro non compatibile")
            }
        }
    }

    fun supports(filters: AnimeFilterList, section: SourceHomeSection) = section.selections.all { (label, value) ->
        select(filters, label)?.values?.any { it == value } == true
    }

    private fun select(filters: AnimeFilterList, name: String) =
        filters.filterIsInstance<AnimeFilter.Select<*>>().singleOrNull { it.name == name }

    private fun textFilter(filters: AnimeFilterList, name: String) =
        filters.filterIsInstance<AnimeFilter.Text>().singleOrNull { it.name == name }
}
