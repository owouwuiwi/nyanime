package eu.kanade.presentation.components.releases

import android.app.Application
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.releases.AiringIssue
import eu.kanade.tachiyomi.data.releases.FollowMode
import eu.kanade.tachiyomi.data.releases.ReleaseMedium
import eu.kanade.tachiyomi.data.releases.ReleaseMonitor
import eu.kanade.tachiyomi.data.releases.ReleasePreferences
import eu.kanade.tachiyomi.data.releases.ReleaseReminders
import eu.kanade.tachiyomi.data.releases.ReleaseStore
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.LocalDate
import java.time.YearMonth

/** Reads local snapshots only. Source and catalog requests belong to the durable monitor. */
class ReleaseCalendarScreenModel(private val scope: ReleaseMedium? = null) :
    StateScreenModel<ReleaseCalendarScreenModel.State>(State(medium = scope)) {
    private val store = ReleaseStore()
    private val app: Application = Injekt.get()
    private var allItems = emptyList<ReleaseAgendaItem>()
    val snackbarHostState = SnackbarHostState()
    private val dismissedAgenda = ReleasePreferences().dismissedAgenda

    init {
        screenModelScope.launch(Dispatchers.IO) {
            ReleaseAgendaRepository().snapshots(scope).collectLatest {
                allItems = it.items.sortedBy { item -> item.at }
                val warnings = it.warnings
                publish { state -> state.copy(loading = false, warnings = warnings, issues = it.issues) }
            }
        }
        refresh()
    }

    fun refresh() = ReleaseMonitor.enqueue(app)

    fun dismiss(item: ReleaseAgendaItem) {
        val added = ReleaseAgendaActions.dismissalKeys(item) - dismissedAgenda.get()
        if (added.isEmpty()) return
        dismissedAgenda.set(dismissedAgenda.get() + added)
        screenModelScope.launch {
            if (snackbarHostState.showSnackbar(
                    app.getString(R.string.release_entry_removed),
                    app.getString(R.string.release_action_undo),
                ) == SnackbarResult.ActionPerformed
            ) {
                dismissedAgenda.set(dismissedAgenda.get() - added)
            }
        }
    }

    fun unfollow(item: ReleaseAgendaItem) {
        screenModelScope.launch {
            try {
                val previous = ReleaseAgendaActions.entries(item).associateWith { store.subscription(item.medium, it) }
                store.setSubscriptions(item.medium, previous.mapValues { it.value.copy(mode = FollowMode.IGNORE) })
                updateReminders()
                if (snackbarHostState.showSnackbar(
                        app.getString(R.string.release_entry_unfollowed),
                        app.getString(R.string.release_action_undo),
                    ) == SnackbarResult.ActionPerformed
                ) {
                    val restored = previous.mapNotNull { (id, old) ->
                        val current = store.subscription(item.medium, id)
                        if (current.mode == FollowMode.IGNORE) id to current.copy(mode = old.mode) else null
                    }.toMap()
                    store.setSubscriptions(item.medium, restored)
                    updateReminders()
                    ReleaseMonitor.enqueue(app)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                snackbarHostState.showSnackbar(app.getString(R.string.release_save_failed))
            }
        }
    }

    private fun updateReminders() {
        screenModelScope.launch(Dispatchers.IO) {
            try {
                ReleaseReminders.schedule(app)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Subscription changes are durable; the monitor retries scheduling independently.
            }
        }
    }

    fun setMonth(month: YearMonth) {
        publish { it.copy(month = month, date = null) }
    }

    fun setDate(date: LocalDate?) {
        publish { it.copy(date = date) }
    }

    fun setMedium(medium: ReleaseMedium?) {
        if (scope != null) return
        publish { it.copy(medium = medium) }
    }

    private fun publish(transform: (State) -> State) {
        mutableState.update { previous ->
            val state = transform(previous)
            val filtered = allItems.filter { state.medium == null || it.medium == state.medium }
            state.copy(
                items = filtered,
                events = filtered.groupingBy { it.date }.eachCount().toImmutableMap(),
            )
        }
    }

    data class State(
        val month: YearMonth = YearMonth.now(),
        val date: LocalDate? = null,
        val medium: ReleaseMedium? = null,
        val items: List<ReleaseAgendaItem> = emptyList(),
        val events: ImmutableMap<LocalDate, Int> = persistentMapOf(),
        val loading: Boolean = true,
        val warnings: Map<ReleaseMedium, String> = emptyMap(),
        val issues: List<AiringIssue> = emptyList(),
    ) {
        val warning: String? get() = if (medium == null) warnings.values.firstOrNull() else warnings[medium]
    }
}
