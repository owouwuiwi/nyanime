package it.lorelore.nyanime.tracking

import android.app.Application
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.data.track.BaseTracker
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import tachiyomi.domain.entries.anime.interactor.GetAnime
import tachiyomi.domain.entries.manga.interactor.GetManga
import tachiyomi.domain.track.anime.interactor.GetAnimeTracks
import tachiyomi.domain.track.manga.interactor.GetMangaTracks
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

data class TrackingQueueTitle(val title: String?, val cover: String?, val available: Boolean)

data class TrackingQueueRow(
    val edit: PendingTrackingEdit,
    val title: TrackingQueueTitle,
    val service: Tracker?,
    val active: Boolean,
    val receipt: TrackingEditReceipt?,
    val canUndo: Boolean,
    val accountMatches: Boolean,
    val needsAccountConfirmation: Boolean,
    val cooldown: Long = 0,
    val accountLabel: String = edit.account,
) {
    val id: String get() = if (active) "pending:${edit.key}" else "receipt:${receipt!!.id}"
}

data class LegacyTrackingChoice(val edit: PendingTrackingEdit, val title: TrackingQueueTitle, val service: Tracker)
data class TrackingLegacyRow(val request: LegacyTrackingRequest, val choices: List<LegacyTrackingChoice>)

data class TrackingQueueState(
    val loading: Boolean = true,
    val pending: List<TrackingQueueRow> = emptyList(),
    val recent: List<TrackingQueueRow> = emptyList(),
    val legacy: List<TrackingLegacyRow> = emptyList(),
    val incognito: Boolean = false,
    val busy: Set<String> = emptySet(),
    val error: Boolean = false,
)

class TrackingQueueModel : StateScreenModel<TrackingQueueState>(TrackingQueueState()) {
    private val context = Injekt.get<Application>()
    private val store = PersonalTrackingStore.get(context)
    private val manager = Injekt.get<TrackerManager>()
    private val preferences = Injekt.get<BasePreferences>()

    init {
        screenModelScope.launch(Dispatchers.IO) {
            try {
                TrackingLegacyMigration.migrate(context)
                combine(store.changes, manager.loggedInTrackersFlow(), preferences.incognitoMode().changes()) {
                        _,
                        _,
                        private,
                    ->
                    private
                }
                    .collect { isPrivate ->
                        try {
                            reload(isPrivate)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            mutableState.update { it.copy(loading = false, error = true) }
                        }
                    }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(loading = false, error = true) }
            }
        }
        TrackingEditWorker.resumePending(context)
    }

    private suspend fun title(edit: PendingTrackingEdit): TrackingQueueTitle {
        if (edit.subject.medium == TrackingMedium.ANIME) {
            val entry = Injekt.get<GetAnime>().await(edit.entryId)
            val same = entry?.source == edit.subject.source && entry?.url == edit.subject.url
            return TrackingQueueTitle(
                if (same) entry?.title else edit.titleHint.ifBlank { null },
                if (same) entry?.thumbnailUrl else null,
                same,
            )
        }
        val entry = Injekt.get<GetManga>().await(edit.entryId)
        val same = entry?.source == edit.subject.source && entry?.url == edit.subject.url
        return TrackingQueueTitle(
            if (same) entry?.title else edit.titleHint.ifBlank { null },
            if (same) entry?.thumbnailUrl else null,
            same,
        )
    }

    private suspend fun reload(isPrivate: Boolean) {
        val receipts = store.recent()
        val edits = store.pending()
        val titles = mutableMapOf<String, TrackingQueueTitle>()
        suspend fun row(edit: PendingTrackingEdit, active: Boolean, receipt: TrackingEditReceipt?): TrackingQueueRow {
            val service = manager.get(edit.trackerId)
            val account = service?.isLoggedIn == true && service.getUsername() == edit.account
            val epoch = service?.let {
                runCatching { TrackingAccountBinding.current(it, edit.subject) }.getOrDefault("unavailable")
            }.orEmpty()
            val sameEpoch = if (service != null && TrackingAccountBinding.enhanced(service)) {
                edit.accountEpoch.isNotBlank() && epoch == edit.accountEpoch
            } else {
                edit.accountEpoch.isBlank() || epoch == edit.accountEpoch
            }
            val art = titles[edit.subject.key] ?: title(edit).also { titles[edit.subject.key] = it }
            return TrackingQueueRow(
                edit, art, service, active, receipt,
                receipt != null &&
                    account &&
                    sameEpoch &&
                    TrackingQueuePolicy.undoPatch(receipt, store.owners(edit)).fields.isNotEmpty(),
                account, account && !sameEpoch, store.cooldown(edit.trackerId),
                accountLabel = if (account && service is BaseTracker) {
                    service.trackPreferences.trackDisplayName(service).get().ifBlank { edit.account }
                } else {
                    edit.account
                },
            )
        }
        val pending = edits.sortedBy { it.createdAt }.map { edit ->
            row(
                edit,
                true,
                receipts.find {
                    it.id ==
                        edit.receiptId
                },
            )
        }
        val pendingReceipts = edits.mapTo(mutableSetOf()) { it.receiptId }
        val recent = receipts.filter { it.id !in pendingReceipts && (it.sentAt > 0 || it.undone || it.discarded) }
            .take(40).map { row(it.edit, false, it) }
        val legacy = store.legacy().map { request ->
            val choices = buildList {
                Injekt.get<GetAnimeTracks>().awaitOne(request.trackId)?.let { track ->
                    val entry = Injekt.get<GetAnime>().await(track.animeId)
                    val service = manager.get(track.trackerId)?.takeIf { it.isLoggedIn }
                    val epoch = if (entry != null && service != null) {
                        runCatching {
                            TrackingAccountBinding.current(
                                service,
                                TrackingSubject(TrackingMedium.ANIME, entry.source, entry.url),
                            )
                        }.getOrNull()
                    } else {
                        null
                    }
                    if (entry != null && service != null && epoch != null) {
                        add(
                            LegacyTrackingChoice(
                                PendingTrackingEdit(
                                    TrackingSubject(TrackingMedium.ANIME, entry.source, entry.url), entry.id, track.id,
                                    track.trackerId, track.remoteId, service.getUsername(),
                                    TrackingPatch(
                                        track.trackingValues().copy(progress = request.progress),
                                        setOf(TrackingField.PROGRESS),
                                    ),
                                    System.currentTimeMillis().coerceAtLeast(1), origin = TrackingEditOrigin.LEGACY,
                                    accountEpoch = epoch,
                                    remoteUrl = track.remoteUrl,
                                    base = track.trackingValues(),
                                ),
                                TrackingQueueTitle(entry.title, entry.thumbnailUrl, true),
                                service,
                            ),
                        )
                    }
                }
                Injekt.get<GetMangaTracks>().awaitOne(request.trackId)?.let { track ->
                    val entry = Injekt.get<GetManga>().await(track.mangaId)
                    val service = manager.get(track.trackerId)?.takeIf { it.isLoggedIn }
                    val epoch = if (entry != null && service != null) {
                        runCatching {
                            TrackingAccountBinding.current(
                                service,
                                TrackingSubject(TrackingMedium.MANGA, entry.source, entry.url),
                            )
                        }.getOrNull()
                    } else {
                        null
                    }
                    if (entry != null && service != null && epoch != null) {
                        add(
                            LegacyTrackingChoice(
                                PendingTrackingEdit(
                                    TrackingSubject(TrackingMedium.MANGA, entry.source, entry.url), entry.id, track.id,
                                    track.trackerId, track.remoteId, service.getUsername(),
                                    TrackingPatch(
                                        track.trackingValues().copy(progress = request.progress),
                                        setOf(TrackingField.PROGRESS),
                                    ),
                                    System.currentTimeMillis().coerceAtLeast(1), origin = TrackingEditOrigin.LEGACY,
                                    accountEpoch = epoch,
                                    remoteUrl = track.remoteUrl,
                                    base = track.trackingValues(),
                                ),
                                TrackingQueueTitle(entry.title, entry.thumbnailUrl, true),
                                service,
                            ),
                        )
                    }
                }
            }
            TrackingLegacyRow(request, choices)
        }
        mutableState.update {
            it.copy(
                loading = false,
                pending = pending,
                recent = recent,
                legacy = legacy,
                incognito = isPrivate,
                error = false,
            )
        }
    }

    private fun action(id: String, block: suspend () -> Unit) {
        if (id in state.value.busy) return
        mutableState.update { it.copy(busy = it.busy + id, error = false) }
        screenModelScope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(error = true) }
            } finally {
                mutableState.update { it.copy(busy = it.busy - id) }
            }
        }
    }

    fun retry(row: TrackingQueueRow) = action(row.id) {
        if (!row.accountMatches || row.needsAccountConfirmation || state.value.incognito) return@action
        store.retry(row.edit.key, row.edit.revision)
        TrackingEditWorker.schedule(context, immediately = true)
    }

    fun confirmAccount(row: TrackingQueueRow) = action(row.id) {
        val service = manager.get(row.edit.trackerId) as? BaseTracker ?: return@action
        if (!service.isLoggedIn ||
            service.getUsername() != row.edit.account ||
            state.value.incognito ||
            TrackingAccountBinding.enhanced(service)
        ) {
            return@action
        }
        val gate = TrackingEditGate.entry(row.edit.key)
        gate.mutex.withLock { store.confirmAccount(row.edit, service.queueAccountEpoch()) }
        TrackingEditWorker.schedule(context, immediately = true)
    }

    fun discard(row: TrackingQueueRow) = action(row.id) {
        val gate = TrackingEditGate.entry(row.edit.key)
        gate.mutex.withLock {
            synchronized(gate.mutations) {
                gate.generation.incrementAndGet()
                store.discard(row.edit)
            }
        }
    }

    fun undo(row: TrackingQueueRow) = action(row.id) {
        if (state.value.incognito) return@action
        val receipt = row.receipt ?: return@action
        val gate = TrackingEditGate.entry(row.edit.key)
        synchronized(gate.mutations) {
            gate.generation.incrementAndGet()
            gate.progressGeneration.incrementAndGet()
            store.undo(receipt.id, System.currentTimeMillis())
        }
        TrackingEditWorker.schedule(context, immediately = true)
    }

    fun acceptLegacy(row: TrackingLegacyRow, choice: LegacyTrackingChoice) = action("legacy:${row.request.id}") {
        if (state.value.incognito ||
            !choice.service.isLoggedIn ||
            choice.service.getUsername() != choice.edit.account
        ) {
            return@action
        }
        val gate = TrackingEditGate.entry(choice.edit.key)
        synchronized(gate.mutations) {
            gate.generation.incrementAndGet()
            gate.progressGeneration.incrementAndGet()
            store.acceptLegacy(row.request.id, choice.edit, choice.edit.base!!)
        }
        TrackingEditWorker.schedule(context, immediately = true)
    }

    fun discardLegacy(row: TrackingLegacyRow) = action("legacy:${row.request.id}") {
        store.discardLegacy(row.request.id)
    }

    fun refresh() = action("refresh") { reload(preferences.incognitoMode().get()) }
}
