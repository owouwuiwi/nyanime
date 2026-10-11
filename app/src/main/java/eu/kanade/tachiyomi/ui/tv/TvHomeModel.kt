package eu.kanade.tachiyomi.ui.tv

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.data.tv.TvPersonal
import eu.kanade.tachiyomi.data.tv.TvRepository
import eu.kanade.tachiyomi.data.tv.TvSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nyanime.tv.api.TvCapabilities
import nyanime.tv.api.TvCatalogueRequest
import nyanime.tv.api.TvChannel
import nyanime.tv.api.TvChannelRef
import nyanime.tv.api.TvFacet
import nyanime.tv.api.TvGuideRequest
import nyanime.tv.api.TvHomeSection
import nyanime.tv.api.TvProgramme
import java.time.LocalDate
import java.time.ZoneId

data class TvHomeState(
    val channels: List<TvChannel> = emptyList(),
    val personalChannels: Map<TvChannelRef, TvChannel> = emptyMap(),
    val personalLoading: Boolean = false,
    val personalUnavailable: Set<TvChannelRef> = emptySet(),
    val personalErrors: Set<TvChannelRef> = emptySet(),
    val sections: List<TvHomeSection> = emptyList(),
    val loading: Set<Long> = emptySet(),
    val nextPages: Map<Long, String> = emptyMap(),
    val paging: Set<Long> = emptySet(),
    val errors: Set<Long> = emptySet(),
    val saved: Set<Long> = emptySet(),
    val personal: TvPersonal = TvPersonal(),
    val personalFault: Boolean = false,
    val extensionFailures: Set<String> = emptySet(),
    val countries: List<TvFacet> = emptyList(),
    val languages: List<TvFacet> = emptyList(),
    val categories: List<TvFacet> = emptyList(),
    val selection: TvSelection = TvSelection(),
    val guide: Boolean = false,
    val day: LocalDate = LocalDate.now(),
    val programmes: Map<TvChannelRef, List<TvProgramme>> = emptyMap(),
    val guideLoading: Set<TvChannelRef> = emptySet(),
    val guideUnavailable: Set<TvChannelRef> = emptySet(),
    val guideErrors: Set<TvChannelRef> = emptySet(),
    val guideSaved: Set<TvChannelRef> = emptySet(),
    val guideRanges: Map<Long, Pair<Long, Long>> = emptyMap(),
    val attributions: List<String> = emptyList(),
)

/** A screen owns cancellation, so old countries and guide days cannot overwrite the new view. */
class TvHomeModel(val repository: TvRepository, private val adultContent: StateFlow<Boolean>) : ScreenModel {
    private val mutable = MutableStateFlow(TvHomeState(personal = repository.preferences.read()))
    val state = mutable.asStateFlow()
    private val catalogues = mutableMapOf<Long, List<TvChannel>>()
    private val sections = mutableMapOf<Long, List<TvHomeSection>>()
    private val options = mutableMapOf<Long, TvCapabilities>()
    private var catalogueJob: Job? = null
    private var pageJob: Job? = null
    private var active = false
    private var guideRequest: Pair<LocalDate, List<TvChannelRef>>? = null
    private var guideJob: Job? = null
    private var personalJob: Job? = null
    private var personalGeneration = 0L
    private var generation = 0L
    private var guideGeneration = 0L
    private var catalogueRevision = 0L
    private var visible = emptyList<TvChannelRef>()
    private var programmeFocus: TvChannelRef? = null

    init {
        screenModelScope.launch {
            repository.registry.state.collectLatest { registered ->
                catalogues.keys.retainAll(registered.keys)
                sections.keys.retainAll(registered.keys)
                options.keys.retainAll(registered.keys)
                val first = registered.values.firstOrNull()?.source
                val saved = first?.let { mutable.value.personal.sources[it.id.toString()] }
                mutable.update { it.copy(selection = saved ?: TvSelection(first?.capabilities?.initialCountry)) }
                refresh()
            }
        }
        screenModelScope.launch {
            repository.preferences.changes.collect {
                mutable.update { it.copy(personal = repository.preferences.read()) }
                refreshPersonal()
            }
        }
        screenModelScope.launch {
            repository.preferences.fault.collect { fault -> mutable.update { it.copy(personalFault = fault) } }
        }
        screenModelScope.launch {
            repository.registry.failures.collect { failures ->
                mutable.update { it.copy(extensionFailures = failures) }
            }
        }
        screenModelScope.launch {
            adultContent.collect { refresh() }
        }
    }

    fun active(value: Boolean) {
        if (active == value) return
        active = value
        if (value) {
            refresh()
        } else {
            catalogueJob?.cancel()
            pageJob?.cancel()
            guideJob?.cancel()
            personalJob?.cancel()
            generation++
            guideGeneration++
            personalGeneration++
            mutable.update {
                it.copy(loading = emptySet(), paging = emptySet(), guideLoading = emptySet(), personalLoading = false)
            }
        }
    }

    fun select(selection: TvSelection) {
        if (selection == mutable.value.selection) return
        val saved = runCatching {
            repository.preferences.update { old ->
                old.copy(
                    sources =
                    old.sources + repository.registry.state.value.keys.associate { it.toString() to selection },
                )
            }
        }
        if (saved.isFailure) return
        catalogues.clear()
        sections.clear()
        mutable.update {
            it.copy(
                selection = selection,
                channels = emptyList(),
                sections = emptyList(),
                programmes = emptyMap(),
                saved = emptySet(),
            )
        }
        refresh()
    }

    fun refresh(force: Boolean = false) {
        if (!active) return
        pageJob?.cancel()
        catalogueJob?.cancel()
        guideJob?.cancel()
        guideGeneration++
        val version = ++generation
        val selected = mutable.value.selection
        val sources = repository.registry.state.value
        mutable.update {
            it.copy(
                channels = it.channels.filter { channel -> channel.ref.sourceId in sources },
                personalChannels = it.personalChannels.filter { (ref, channel) ->
                    ref.sourceId in sources &&
                        (adultContent.value || !channel.adult)
                },
                sections = sections.values.flatten(),
                loading = sources.keys,
                errors = emptySet(),
                guideErrors = emptySet(),
                guideLoading = emptySet(),
                nextPages = emptyMap(),
                paging = emptySet(),
            )
        }
        refreshPersonal()
        catalogueJob = screenModelScope.launch {
            sources.forEach { (id, _) ->
                launch {
                    repository.catalogue(
                        id,
                        TvCatalogueRequest(selected.country, selected.language, selected.category, refresh = force),
                    ).collect { result ->
                        if (generation != version) return@collect
                        result.data?.let { page ->
                            catalogues[id] = page.channels
                            sections[id] = page.sections
                            page.options?.let { options[id] = it }
                        }
                        val capabilities = sources.map { (key, value) -> options[key] ?: value.source.capabilities }
                        mutable.update { previous ->
                            previous.copy(
                                loading = if (result.loading) previous.loading + id else previous.loading - id,
                                nextPages =
                                result.data?.let { page ->
                                    if (page.nextCursor ==
                                        null
                                    ) {
                                        previous.nextPages - id
                                    } else {
                                        previous.nextPages +
                                            (id to requireNotNull(page.nextCursor))
                                    }
                                }
                                    ?: previous.nextPages,
                                errors = if (result.error) previous.errors + id else previous.errors - id,
                                saved = if (result.saved) previous.saved + id else previous.saved - id,
                                countries = capabilities.flatMap { it.countries }.distinctBy { it.id },
                                languages = capabilities.flatMap { it.languages }.distinctBy { it.id },
                                categories = capabilities.flatMap { it.categories }.distinctBy { it.id },
                                attributions = capabilities.mapNotNull { it.attribution }.distinct(),
                            )
                        }
                        val emission = ++catalogueRevision
                        val snapshot = catalogues.values.flatten()
                        val allowAdult = adultContent.value
                        val merged =
                            withContext(Dispatchers.Default) {
                                snapshot.filter {
                                    allowAdult || !it.adult
                                }.distinctBy { it.ref }.sortedBy { it.name.lowercase() }
                            }
                        if (generation != version || emission != catalogueRevision) return@collect
                        mutable.update { it.copy(channels = merged, sections = sections.values.flatten()) }
                        refreshPersonal()
                        updateGuide(guideRefs(), force)
                    }
                }
            }
        }
    }

    fun loadMore() {
        if (!active || mutable.value.loading.isNotEmpty() || mutable.value.paging.isNotEmpty()) return
        val pages = mutable.value.nextPages
        if (pages.isEmpty()) return
        val version = generation
        val selected = mutable.value.selection
        mutable.update { it.copy(paging = pages.keys) }
        pageJob = screenModelScope.launch {
            pages.forEach { (id, cursor) ->
                launch {
                    repository.catalogue(
                        id,
                        TvCatalogueRequest(selected.country, selected.language, selected.category, cursor),
                    ).collect { result ->
                        if (generation != version) return@collect
                        result.data?.let { page ->
                            catalogues[id] = (catalogues[id].orEmpty() + page.channels).distinctBy { it.ref }
                            sections[id] = (sections[id].orEmpty() + page.sections).distinctBy { it.id }
                        }
                        val snapshot = catalogues.values.flatten()
                        val emission = ++catalogueRevision
                        val allowAdult = adultContent.value
                        val merged =
                            withContext(Dispatchers.Default) {
                                snapshot.filter {
                                    allowAdult || !it.adult
                                }.distinctBy { it.ref }.sortedBy { it.name.lowercase() }
                            }
                        if (generation != version || emission != catalogueRevision) return@collect
                        mutable.update {
                            it.copy(
                                channels = merged,
                                sections = sections.values.flatten(),
                                paging = if (result.loading) it.paging else it.paging - id,
                                nextPages =
                                result.data?.let { page ->
                                    if (page.nextCursor == null ||
                                        page.nextCursor == cursor
                                    ) {
                                        it.nextPages - id
                                    } else {
                                        it.nextPages +
                                            (id to requireNotNull(page.nextCursor))
                                    }
                                }
                                    ?: it.nextPages,
                                errors = if (result.error) it.errors + id else it.errors - id,
                            )
                        }
                    }
                }
            }
        }
    }

    fun guide(enabled: Boolean) {
        mutable.update { it.copy(guide = enabled) }
        // Channel cards always describe the current broadcast, not the last guide day opened.
        if (!enabled) day(LocalDate.now())
    }

    fun programmeFocus(reference: TvChannelRef?) {
        if (programmeFocus == reference) return
        programmeFocus = reference
        updateGuide(guideRefs())
    }

    fun day(day: LocalDate) {
        if (day == mutable.value.day) return
        mutable.update {
            it.copy(
                day = day,
                programmes = emptyMap(),
                guideErrors = emptySet(),
                guideUnavailable = emptySet(),
                guideSaved = emptySet(),
                guideLoading = emptySet(),
            )
        }
        updateGuide(guideRefs())
    }

    fun visible(refs: List<TvChannelRef>) {
        val selected = refs.distinct().take(64)
        if (selected == visible) return
        visible = selected
        updateGuide(guideRefs())
    }

    fun favourite(channel: TvChannel) {
        if (runCatching {
                repository.preferences.favourite(
                    channel.ref,
                    channel.ref !in mutable.value.personal.favourites,
                )
            }.isFailure
        ) {
            return
        }
        mutable.update { it.copy(personal = repository.preferences.read()) }
        refreshPersonal()
        updateGuide(guideRefs())
    }

    fun grid(value: Boolean) {
        if (runCatching { repository.preferences.update { it.copy(gridGuide = value) } }.isFailure) return
        mutable.update { it.copy(personal = repository.preferences.read()) }
    }

    fun moveFavourite(channel: TvChannel, offset: Int) {
        if (runCatching { repository.preferences.moveFavourite(channel.ref, offset) }.isFailure) return
        mutable.update { it.copy(personal = repository.preferences.read()) }
    }

    /** Called only while the TV content is visible. Repository TTL avoids repeated network calls. */
    fun refreshVisibleGuide() = updateGuide(guideRefs())

    private fun guideRefs(): List<TvChannelRef> =
        (listOfNotNull(programmeFocus) + visible + mutable.value.personal.favourites.take(32)).distinct()
            .filter { repository.registry.state.value[it.sourceId] != null }.take(64)

    private fun refreshPersonal() {
        if (!active) return
        personalJob?.cancel()
        val version = ++personalGeneration
        val selected = (mutable.value.personal.favourites + mutable.value.personal.recent).distinct()
        val current = mutable.value.channels.associateBy { it.ref }
        val previous = mutable.value.personalChannels.filterKeys { it in selected }
        val missing = selected.filterNot { it in current }
        mutable.update {
            it.copy(
                personalChannels = previous +
                    current.filterKeys { ref ->
                        ref in selected
                    },
                personalLoading = missing.isNotEmpty(),
            )
        }
        if (missing.isEmpty()) {
            mutable.update { it.copy(personalErrors = emptySet(), personalUnavailable = emptySet()) }
            return
        }
        personalJob = screenModelScope.launch {
            try {
                var unavailable = emptySet<TvChannelRef>()
                var failed = emptySet<TvChannelRef>()
                for (batch in missing.chunked(64)) {
                    val lookup = repository.channels(batch)
                    unavailable += lookup.unavailable
                    failed += lookup.failed
                    if (version != personalGeneration) return@launch
                    val found = lookup.channels.filter { adultContent.value || !it.adult }.associateBy { it.ref }
                    mutable.update {
                        it.copy(
                            personalChannels = (it.personalChannels - lookup.unavailable) + found,
                            personalLoading = true,
                            personalErrors = failed,
                            personalUnavailable = unavailable,
                        )
                    }
                }
                if (version == personalGeneration) mutable.update { it.copy(personalLoading = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (version ==
                    personalGeneration
                ) {
                    mutable.update { it.copy(personalLoading = false, personalErrors = missing.toSet()) }
                }
            }
        }
    }

    private fun updateGuide(refs: List<TvChannelRef>, force: Boolean = false) {
        if (!active) return
        val request = mutable.value.day to refs
        if (!force && guideJob?.isActive == true && guideRequest == request) return
        guideRequest = request
        guideJob?.cancel()
        val requestVersion = ++guideGeneration
        mutable.update { it.copy(guideLoading = emptySet()) }
        val day = mutable.value.day
        val version = generation
        val start = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        guideJob = screenModelScope.launch {
            delay(250)
            val sourceRefs = refs.groupBy { it.sourceId }
            sourceRefs.forEach { (id, channels) ->
                launch {
                    if (repository.registry.state.value[id]?.source?.capabilities?.guide != true) {
                        mutable.update { it.copy(guideUnavailable = it.guideUnavailable + channels) }
                        return@launch
                    }
                    mutable.update { it.copy(guideLoading = it.guideLoading + channels) }
                    repository.guideFlow(TvGuideRequest(channels, start, end), force).collect { result ->
                        if (version != generation ||
                            requestVersion != guideGeneration ||
                            day != mutable.value.day
                        ) {
                            return@collect
                        }
                        val guide = result.data
                        val byChannel = guide.programmes.groupBy { it.channel }
                        mutable.update { previous ->
                            previous.copy(
                                programmes = previous.programmes +
                                    channels.associateWith { ref ->
                                        byChannel[ref].orEmpty().ifEmpty {
                                            previous.programmes[ref].orEmpty().takeIf {
                                                ref in result.loading || ref in guide.failed
                                            }.orEmpty()
                                        }
                                    },
                                guideLoading = (previous.guideLoading - channels.toSet()) + result.loading,
                                guideUnavailable = (previous.guideUnavailable - channels.toSet()) + guide.unavailable,
                                guideErrors = (previous.guideErrors - channels.toSet()) + guide.failed,
                                guideSaved = (previous.guideSaved - channels.toSet()) + result.saved,
                                guideRanges = if (guide.availableFromUtc != null && guide.availableUntilUtc != null) {
                                    previous.guideRanges +
                                        (
                                            id to
                                                (
                                                    requireNotNull(guide.availableFromUtc) to
                                                        requireNotNull(guide.availableUntilUtc)
                                                    )
                                            )
                                } else {
                                    previous.guideRanges
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
