package eu.kanade.tachiyomi.ui.discovery

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.domain.discovery.SectionState
import tachiyomi.domain.discovery.SourceHomeGroupAccess
import tachiyomi.domain.discovery.SourceHomeGroupRepository
import tachiyomi.domain.discovery.SourceHomePage
import tachiyomi.domain.discovery.SourceHomeRequest

/** Owns visited requests; the repository decides freshness when the visible Home resumes. */
class SourceHomeFeedLoader(
    private val scope: CoroutineScope,
    private val repository: SourceHomeGroupRepository,
    private val access: () -> SourceHomeGroupAccess,
) {
    private val jobs = mutableMapOf<String, Job>()
    private val requests = mutableMapOf<String, SourceHomeRequest>()
    private val mutableSections = MutableStateFlow<Map<String, SectionState<SourceHomePage>>>(emptyMap())
    val sections = mutableSections.asStateFlow()
    private var generation = 0
    private val tickets = mutableMapOf<String, Int>()
    private var active = true

    fun pause() {
        active = false
        jobs.values.forEach(Job::cancel)
        jobs.clear()
        tickets.clear()
        mutableSections.update { sections -> sections.mapValues { it.value.copy(loading = false) } }
    }

    fun resume() {
        active = true
        refresh(force = false)
    }

    fun reset() {
        generation++
        jobs.values.forEach(Job::cancel)
        jobs.clear()
        requests.clear()
        tickets.clear()
        mutableSections.value = emptyMap()
    }

    fun load(request: SourceHomeRequest, refresh: Boolean = false, revalidate: Boolean = false) {
        val current = access()
        if (!active || current.group == null || current.offline || current.loading) return
        val id = request.sectionId
        val sameRequest = requests[id] == request
        if (sameRequest && !refresh && (!revalidate || jobs[id]?.isActive == true)) return
        jobs.remove(id)?.cancel()
        requests[id] = request
        val ticket = ++generation
        tickets[id] = ticket
        // A date change must never display the previous date's events, including after an error.
        if (!sameRequest) mutableSections.update { it + (id to SectionState()) }
        // A merged feed emits intermediate subsets as its providers restore their caches.
        // Keep the complete visible snapshot during revalidation, rather than removing and
        // restoring cards on each provider response. First loads remain progressive.
        val retained = mutableSections.value[id]?.data
        jobs[id] = scope.launch {
            repository.observe(current, request, refresh).collect { value ->
                if (current == access() && requests[id] == request && tickets[id] == ticket) {
                    mutableSections.update { previous ->
                        previous +
                            (
                                id to value.copy(
                                    data = if (value.loading &&
                                        retained != null
                                    ) {
                                        retained
                                    } else {
                                        value.data ?: previous[id]?.data
                                    },
                                )
                                )
                    }
                }
            }
        }
    }

    fun refresh(force: Boolean) {
        requests.values.toList().forEach { load(it, refresh = force, revalidate = true) }
    }
}
