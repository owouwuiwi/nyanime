package it.lorelore.nyanime.tracking

import android.app.Application
import android.content.Context
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.entries.manga.model.toSManga
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.track.AnimeTracker
import eu.kanade.tachiyomi.data.track.MangaTracker
import eu.kanade.tachiyomi.data.track.SourceTrackingHints
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.workinfo.SourceWorkIdentity
import eu.kanade.tachiyomi.data.workinfo.WorkIdentity
import eu.kanade.tachiyomi.data.workinfo.WorkInfoPreferences
import eu.kanade.tachiyomi.data.workinfo.WorkKind
import eu.kanade.tachiyomi.data.workinfo.WorkProvider
import eu.kanade.tachiyomi.data.workinfo.WorkReference
import eu.kanade.tachiyomi.util.system.isOnline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.entries.anime.interactor.GetAnime
import tachiyomi.domain.entries.manga.interactor.GetManga
import tachiyomi.domain.items.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.items.episode.interactor.GetEpisodesByAnimeId
import tachiyomi.domain.track.anime.interactor.GetAnimeTracks
import tachiyomi.domain.track.manga.interactor.GetMangaTracks
import tachiyomi.i18n.MR
import tachiyomi.i18n.aniyomi.AYMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import eu.kanade.domain.track.anime.model.toDbTrack as toAnimeDb
import eu.kanade.domain.track.anime.model.toDomainTrack as toAnimeDomain
import eu.kanade.domain.track.manga.model.toDbTrack as toMangaDb
import eu.kanade.domain.track.manga.model.toDomainTrack as toMangaDomain

data class TrackingPanelState(
    val loading: Boolean = true,
    val local: PersonalTrackingRecord? = null,
    val targets: List<RemoteTrackingTarget> = emptyList(),
    val selected: Long? = null,
    val values: TrackingValues = TrackingValues(),
    val total: Long = 0,
    val ratings: CommunityRatings? = null,
    val ratingsLoading: Boolean = false,
    val ratingsFailed: Boolean = false,
    val ratingsEnabled: Boolean = false,
    val canLoadRatings: Boolean = false,
    val pending: PendingTrackingEdit? = null,
    val saving: Boolean = false,
    val error: Boolean = false,
    val undo: TrackingUndo? = null,
    val incognito: Boolean = false,
    val hasAccounts: Boolean = false,
    val accountChanged: Boolean = false,
)

data class TrackingUndo(
    val target: Long?,
    val before: TrackingValues,
    val patch: TrackingPatch,
    val personal: Boolean = false,
    val accepted: kotlinx.coroutines.CompletableDeferred<String?> = kotlinx.coroutines.CompletableDeferred(),
)

/** The actor outlives sheets, so dismissing after a tap cannot cancel an accepted local write. */
private object TrackingPanelWrites {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    init {
        scope.launch { for (request in requests) runCatching { request() } }
    }
    fun submit(block: suspend () -> Unit) {
        check(requests.trySend(block).isSuccess)
    }
}

class TrackingPanelModel(
    private val manga: Boolean,
    private val entryId: Long,
    private val sourceId: Long,
) : StateScreenModel<TrackingPanelState>(TrackingPanelState()) {
    private val context = Injekt.get<Application>()
    private val store = PersonalTrackingStore.get(context)
    private val manager = Injekt.get<TrackerManager>()
    private val refreshGate = Mutex()
    private lateinit var subject: TrackingSubject
    private lateinit var initial: PersonalTrackingRecord
    private var localTotal = 0L
    private var titleHint = ""
    private var identity: WorkIdentity? = null
    private var reference: WorkReference? = null
    private var choosingInitial = true
    private var requestedRemoteRefresh = false
    private val session = MutableStateFlow<PersonalTrackingRecord?>(null)
    private val acceptedWrites = AtomicInteger()
    private data class LocalRetry(val selected: Long?, val patch: TrackingPatch, val personal: Boolean)

    @Volatile private var localRetry: LocalRetry? = null

    init {
        screenModelScope.launch(Dispatchers.IO) {
            try {
                val incognito = Injekt.get<BasePreferences>().incognitoMode().get() ||
                    if (manga) {
                        Injekt.get<eu.kanade.domain.source.manga.interactor.GetMangaIncognitoState>().await(sourceId)
                    } else {
                        Injekt.get<eu.kanade.domain.source.anime.interactor.GetAnimeIncognitoState>().await(sourceId)
                    }
                val observed: Double
                if (manga) {
                    val entry = Injekt.get<GetManga>().await(entryId) ?: error("Title unavailable")
                    titleHint = entry.title.take(2048)
                    subject = TrackingSubject(TrackingMedium.MANGA, entry.source, entry.url)
                    identity = SourceWorkIdentity.fromManga(entry.source, entry.toSManga())
                    val chapters = Injekt.get<GetChaptersByMangaId>().await(entryId)
                    observed =
                        chapters.filter { it.read && it.chapterNumber > 0 }.maxOfOrNull { it.chapterNumber } ?: 0.0
                    if (entry.status == eu.kanade.tachiyomi.source.model.SManga.COMPLETED.toLong()) {
                        localTotal = chapters.maxOfOrNull { it.chapterNumber }?.toLong()?.coerceAtLeast(0) ?: 0
                    }
                } else {
                    val entry = Injekt.get<GetAnime>().await(entryId) ?: error("Title unavailable")
                    titleHint = entry.title.take(2048)
                    subject = TrackingSubject(TrackingMedium.ANIME, entry.source, entry.url)
                    val hints = SourceTrackingHints.from(entry)
                    identity = SourceWorkIdentity.apply(
                        WorkIdentity(
                            WorkInfoPreferences.localKey(false, entry.source, entry.url),
                            entry.title,
                            WorkKind.VIDEO,
                            references = listOfNotNull(
                                hints?.anilistId?.let {
                                    WorkReference(WorkProvider.ANILIST, it.toString(), WorkKind.ANIME)
                                },
                            ),
                        ),
                        entry.memo["nyanime.work.v1"] as? kotlinx.serialization.json.JsonObject,
                    )
                    val episodes = Injekt.get<GetEpisodesByAnimeId>().await(entryId)
                    observed =
                        episodes.filter { it.seen && it.episodeNumber > 0 }.maxOfOrNull { it.episodeNumber } ?: 0.0
                    if (entry.status == eu.kanade.tachiyomi.animesource.model.SAnime.COMPLETED.toLong()) {
                        localTotal = episodes.maxOfOrNull { it.episodeNumber }?.toLong()?.coerceAtLeast(0) ?: 0
                    }
                }
                initial = PersonalTrackingRecord(subject).observe(observed)
                if (incognito) session.value = store.get(subject) ?: initial else store.observe(subject, observed)
                mutableState.update { it.copy(incognito = incognito) }
                val tracks = if (manga) {
                    Injekt.get<GetMangaTracks>().subscribe(
                        entryId,
                    ).mapToSignal()
                } else {
                    Injekt.get<GetAnimeTracks>().subscribe(entryId).mapToSignal()
                }
                combine(tracks, store.changes, manager.loggedInTrackersFlow(), session) { _, _, _, _ -> Unit }
                    .collect { reload() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(loading = false, error = true) }
            }
        }
    }

    private suspend fun reload() = refreshGate.withLock {
        val targets = targets()
        if (choosingInitial) {
            mutableState.update { it.copy(selected = targets.firstOrNull()?.tracker?.id) }
            choosingInitial = false
        }
        val local = if (state.value.incognito) session.value ?: initial else store.get(subject) ?: initial
        val selected = state.value.selected?.takeIf { id -> targets.any { it.tracker.id == id } }
        val target = targets.find { it.tracker.id == selected }
        val pending = target?.let { store.pending(it.key) }
        val activePending = pending?.takeIf {
            it.account == target.account &&
                it.remoteId == target.remoteId &&
                (it.accountEpoch.isBlank() || it.accountEpoch == target.accountEpoch)
        }
        val values = target?.values?.let { activePending?.patch?.applyTo(it) ?: it } ?: local.values
        val preferences = Injekt.get<WorkInfoPreferences>()
        val binding = identity?.let(preferences::binding)
        reference = if (binding != null) {
            binding.reference
        } else {
            val refs = identity?.references.orEmpty().filter { it.provider == WorkProvider.ANILIST && it.isValid() }
            val tracked = targets.filter { it.tracker.id == TrackerManager.ANILIST && it.remoteId > 0 }
                .map {
                    WorkReference(
                        WorkProvider.ANILIST,
                        it.remoteId.toString(),
                        if (manga) WorkKind.MANGA else WorkKind.ANIME,
                    )
                }
            (refs + tracked).distinct().singleOrNull()
        }
        mutableState.update {
            it.copy(
                loading = false,
                local = if (acceptedWrites.get() >
                    0
                ) {
                    it.local
                } else {
                    local
                },
                targets = targets, selected = selected,
                values = if (acceptedWrites.get() > 0 && it.selected == selected) it.values else values,
                total = target?.total ?: localTotal, pending = pending,
                accountChanged =
                pending != null && activePending == null,
                hasAccounts = manager.loggedInTrackers().any { tracker ->
                    if (manga) tracker is MangaTracker else tracker is AnimeTracker
                },
                ratingsEnabled = preferences.consent().get() == WorkInfoPreferences.ENABLED,
                canLoadRatings = reference?.let { r -> r.provider == WorkProvider.ANILIST && r.isValid() } == true,
            )
        }
        if (state.value.ratingsEnabled &&
            state.value.canLoadRatings &&
            !state.value.ratingsLoading &&
            state.value.ratings == null &&
            !state.value.ratingsFailed
        ) {
            loadRatings()
        }
        if (!requestedRemoteRefresh && !state.value.incognito && context.isOnline()) {
            requestedRemoteRefresh = true
            screenModelScope.launch(Dispatchers.IO) { refreshRemote() }
        }
    }

    private suspend fun targets(): List<RemoteTrackingTarget> {
        fun statuses(
            service: Tracker,
            ids: List<Long>,
            label: (Long) -> dev.icerock.moko.resources.StringResource?,
        ): List<TrackingStatusChoice> =
            ids.map { id ->
                val resource = label(id)
                val tone = when (resource) {
                    AYMR.strings.watching, MR.strings.reading -> PersonalTrackingStatus.CURRENT
                    AYMR.strings.repeating_anime, MR.strings.repeating -> PersonalTrackingStatus.REPEATING
                    MR.strings.completed -> PersonalTrackingStatus.COMPLETED
                    MR.strings.paused -> PersonalTrackingStatus.PAUSED
                    MR.strings.dropped -> PersonalTrackingStatus.DROPPED
                    AYMR.strings.plan_to_watch, MR.strings.plan_to_read -> PersonalTrackingStatus.PLANNED
                    else -> null
                }
                TrackingStatusChoice(id, resource?.let(context::stringResource) ?: id.toString(), tone)
            }.sortedBy { it.tone?.ordinal ?: 99 }
        return if (manga) {
            Injekt.get<GetMangaTracks>().await(entryId).mapNotNull { track ->
                val service = manager.get(track.trackerId)?.takeIf { it.isLoggedIn } ?: return@mapNotNull null
                val tracker = service as? MangaTracker ?: return@mapNotNull null
                RemoteTrackingTarget(
                    service,
                    subject,
                    entryId,
                    track.id,
                    track.remoteId,
                    service.getUsername(),
                    track.trackingValues(),
                    track.totalChapters,
                    statuses(service, tracker.getStatusListManga(), tracker::getStatusForManga),
                    tracker.getScoreList().mapIndexed { index, label ->
                        val value = tracker.indexToScore(index)
                        TrackingScoreChoice(value, label, tracker.get10PointScore(track.copy(score = value)))
                    },
                    tracker.displayScore(track),
                    accountEpoch = runCatching {
                        TrackingAccountBinding.current(service, subject)
                    }.getOrDefault("unavailable"),
                    titleHint = titleHint,
                    remoteUrl = track.remoteUrl,
                )
            }
        } else {
            Injekt.get<GetAnimeTracks>().await(entryId).mapNotNull { track ->
                val service = manager.get(track.trackerId)?.takeIf { it.isLoggedIn } ?: return@mapNotNull null
                val tracker = service as? AnimeTracker ?: return@mapNotNull null
                RemoteTrackingTarget(
                    service,
                    subject,
                    entryId,
                    track.id,
                    track.remoteId,
                    service.getUsername(),
                    track.trackingValues(),
                    track.totalEpisodes,
                    statuses(service, tracker.getStatusListAnime(), tracker::getStatusForAnime),
                    tracker.getScoreList().mapIndexed { index, label ->
                        val value = tracker.indexToScore(index)
                        TrackingScoreChoice(value, label, tracker.get10PointScore(track.copy(score = value)))
                    },
                    tracker.displayScore(track),
                    accountEpoch = runCatching {
                        TrackingAccountBinding.current(service, subject)
                    }.getOrDefault("unavailable"),
                    titleHint = titleHint,
                    remoteUrl = track.remoteUrl,
                )
            }
        }
    }

    fun select(trackerId: Long?) {
        mutableState.update { current ->
            val target = current.targets.find { it.tracker.id == trackerId }
            current.copy(
                selected = trackerId,
                values = target?.values ?: current.local?.values ?: initial.values,
                total = target?.total ?: localTotal,
                pending = null,
                undo = null,
                error = false,
            )
        }
        screenModelScope.launch(Dispatchers.IO) { reload() }
    }

    fun status(value: Long) {
        val target = state.value.targets.find { it.tracker.id == state.value.selected }
        val completed = if (target == null) {
            value == PersonalTrackingStatus.COMPLETED.ordinal.toLong()
        } else {
            target.statuses.find { it.value == value }?.tone == PersonalTrackingStatus.COMPLETED
        }
        edit(TrackingControls.status(state.value.values, value, completed, state.value.total))
    }

    fun progress(value: Double) {
        val target = state.value.targets.find { it.tracker.id == state.value.selected }
        fun status(tone: PersonalTrackingStatus): Long? =
            if (target == null) tone.ordinal.toLong() else target.statuses.find { it.tone == tone }?.value
        val date = if (target == null || target.tracker.supportsReadingDates) {
            java.time.LocalDate.now().atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        } else {
            null
        }
        edit(
            TrackingControls.progress(
                state.value.values,
                value,
                state.value.total,
                status(PersonalTrackingStatus.CURRENT),
                status(PersonalTrackingStatus.REPEATING),
                status(PersonalTrackingStatus.COMPLETED),
                date,
            ),
        )
    }

    fun edit(patch: TrackingPatch, rememberUndo: Boolean = true, retry: Boolean = false) {
        val before = state.value.values
        val after = patch.applyTo(before)
        if (after == before && !retry || !after.valid() || !::subject.isInitialized) return
        if (patch.fields.any { it == TrackingField.START || it == TrackingField.FINISH } &&
            after.startDate > 0 &&
            after.finishDate > 0 &&
            after.finishDate < after.startDate
        ) {
            mutableState.update { it.copy(error = true) }
            return
        }
        val target = state.value.targets.find { it.tracker.id == state.value.selected }
        if (target != null && state.value.incognito) return
        if (target == null && (after.status !in 0L..5L || after.score !in 0.0..10.0)) return
        val existing = state.value.pending
        if (target != null &&
            existing != null &&
            (
                existing.account != target.account ||
                    existing.remoteId != target.remoteId ||
                    existing.accountEpoch != target.accountEpoch ||
                    existing.remoteUrl != target.remoteUrl ||
                    existing.subject != target.subject ||
                    existing.entryId != target.entryId ||
                    existing.trackId != target.trackId ||
                    existing.trackerId != target.tracker.id
                )
        ) {
            mutableState.update { it.copy(error = true) }
            return
        }
        val selected = state.value.selected
        localRetry = null
        val undo = if (rememberUndo) TrackingUndo(selected, before, patch) else null
        acceptedWrites.incrementAndGet()
        mutableState.update {
            it.copy(
                values = after,
                local = if (target == null) (it.local ?: initial).copy(values = after) else it.local,
                saving = true,
                error = false,
                undo = undo,
            )
        }
        TrackingPanelWrites.submit {
            try {
                if (target == null) {
                    if (state.value.incognito) {
                        session.value = (session.value ?: initial).copy(values = after)
                    } else {
                        store.update(subject, initial, patch)
                    }
                } else {
                    val gate = TrackingEditGate.entry(target.key)
                    val accepted = synchronized(gate.mutations) {
                        gate.generation.incrementAndGet()
                        if (patch.fields.any { it == TrackingField.PROGRESS || it == TrackingField.STATUS }) {
                            gate.progressGeneration.incrementAndGet()
                        }
                        store.enqueue(
                            PendingTrackingEdit(
                                subject,
                                entryId,
                                target.trackId,
                                target.tracker.id,
                                target.remoteId,
                                target.account,
                                patch,
                                System.currentTimeMillis(),
                                accountEpoch = target.accountEpoch,
                                base = target.values,
                                titleHint = target.titleHint,
                                remoteUrl = target.remoteUrl,
                            ),
                            before,
                        )
                    }
                    undo?.accepted?.complete(accepted.receiptId)
                    TrackingEditWorker.schedule(context)
                }
            } catch (_: Exception) {
                if (target == null) localRetry = LocalRetry(selected, patch, personal = false)
                mutableState.update { current ->
                    current.copy(
                        saving = false,
                        error = true,
                        values = if (current.selected ==
                            selected
                        ) {
                            patch.inverse(before, current.values).applyTo(current.values)
                        } else {
                            current.values
                        },
                    )
                }
            } finally {
                undo?.accepted?.complete(null)
                val remaining = acceptedWrites.decrementAndGet()
                mutableState.update { it.copy(saving = remaining > 0) }
                if (remaining == 0) reload()
            }
        }
    }

    fun editPersonal(patch: TrackingPatch, rememberUndo: Boolean = true, retry: Boolean = false) {
        if (!::subject.isInitialized) return
        if (state.value.selected == null) {
            edit(patch, rememberUndo, retry)
            return
        }
        val current = state.value.local ?: initial
        val next = patch.applyTo(current.values)
        if (!next.valid() || next == current.values && !retry) return
        val selected = state.value.selected
        localRetry = null
        acceptedWrites.incrementAndGet()
        mutableState.update {
            it.copy(
                local = current.copy(values = next),
                saving = true,
                undo = if (rememberUndo) TrackingUndo(it.selected, current.values, patch, personal = true) else null,
            )
        }
        TrackingPanelWrites.submit {
            try {
                if (state.value.incognito) {
                    session.value = current.copy(
                        values = next,
                    )
                } else {
                    store.update(subject, initial, patch)
                }
            } catch (_: Exception) {
                localRetry = LocalRetry(selected, patch, personal = true)
                mutableState.update { it.copy(error = true) }
            } finally {
                val remaining = acceptedWrites.decrementAndGet()
                mutableState.update { it.copy(saving = remaining > 0) }
                if (remaining == 0) reload()
            }
        }
    }

    fun undo() {
        val undo = state.value.undo ?: return
        if (undo.target != state.value.selected) return
        if (undo.target != null && !undo.personal) {
            mutableState.update { it.copy(undo = null, saving = true) }
            TrackingPanelWrites.submit {
                try {
                    val id = undo.accepted.await() ?: return@submit
                    val receipt = store.receipt(id) ?: return@submit
                    val gate = TrackingEditGate.entry(receipt.edit.key)
                    synchronized(gate.mutations) {
                        gate.generation.incrementAndGet()
                        gate.progressGeneration.incrementAndGet()
                        store.undo(id, System.currentTimeMillis())
                    }
                    TrackingEditWorker.schedule(context)
                } finally {
                    mutableState.update { it.copy(saving = false) }
                    reload()
                }
            }
        } else if (undo.personal) {
            editPersonal(
                undo.patch.inverse(undo.before, state.value.local?.values ?: initial.values),
                rememberUndo = false,
            )
        } else {
            edit(undo.patch.inverse(undo.before, state.value.values), rememberUndo = false)
        }
    }

    fun retry() {
        val failed = localRetry
        if (failed != null && failed.selected == state.value.selected) {
            localRetry = null
            if (failed.personal) editPersonal(failed.patch, retry = true) else edit(failed.patch, retry = true)
            return
        }
        if (state.value.pending != null) {
            val pending = state.value.pending!!
            screenModelScope.launch(Dispatchers.IO) {
                try {
                    store.retry(pending.key, pending.revision)
                    TrackingEditWorker.schedule(context, immediately = true)
                } catch (_: Exception) {
                    mutableState.update { it.copy(error = true) }
                }
            }
        }
        mutableState.update { it.copy(error = false) }
        screenModelScope.launch(Dispatchers.IO) { reload() }
    }

    private suspend fun refreshRemote() {
        for (target in state.value.targets) {
            val gate = TrackingEditGate.entry(target.key)
            val generation = gate.generation.get()
            gate.mutex.withLock {
                if (generation != gate.generation.get() ||
                    store.pending(target.key) != null ||
                    !target.tracker.isLoggedIn ||
                    target.tracker.getUsername() != target.account
                ) {
                    return@withLock
                }
                try {
                    if (manga) {
                        val current = Injekt.get<GetMangaTracks>().awaitOne(target.trackId) ?: return@withLock
                        val refreshed = target.tracker.mangaService.refresh(current.toMangaDb())
                        val response = refreshed.toMangaDomain() ?: return@withLock
                        if (target.tracker.getUsername() == target.account &&
                            response.remoteId == target.remoteId &&
                            generation == gate.generation.get() &&
                            store.pending(target.key) == null
                        ) {
                            Injekt.get<tachiyomi.domain.track.manga.repository.MangaTrackRepository>().insertManga(
                                response,
                            )
                        }
                    } else {
                        val current = Injekt.get<GetAnimeTracks>().awaitOne(target.trackId) ?: return@withLock
                        val refreshed = target.tracker.animeService.refresh(current.toAnimeDb())
                        val response = refreshed.toAnimeDomain() ?: return@withLock
                        if (target.tracker.getUsername() == target.account &&
                            response.remoteId == target.remoteId &&
                            generation == gate.generation.get() &&
                            store.pending(target.key) == null
                        ) {
                            Injekt.get<tachiyomi.domain.track.anime.repository.AnimeTrackRepository>().insertAnime(
                                response,
                            )
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) { /* The cached record remains editable; this is not a failed user write. */ }
            }
        }
    }

    fun discard() {
        val pending = state.value.pending ?: return
        TrackingPanelWrites.submit {
            val gate = TrackingEditGate.entry(pending.key)
            gate.mutex.withLock {
                gate.generation.incrementAndGet()
                store.discard(pending)
            }
        }
    }

    fun copyLocalToSelected() {
        val target = state.value.targets.find { it.tracker.id == state.value.selected } ?: return
        val local = state.value.local?.values ?: return
        val matchingStatus = target.statuses.find { it.tone?.ordinal?.toLong() == local.status } ?: return
        val score = target.scores.minByOrNull { abs(it.tenPoint - local.score) }?.value ?: target.values.score
        val fields = mutableSetOf(TrackingField.STATUS, TrackingField.PROGRESS)
        if (target.scores.isNotEmpty()) fields += TrackingField.SCORE
        edit(
            TrackingPatch(
                target.values.copy(status = matchingStatus.value, progress = local.progress, score = score),
                fields,
            ),
        )
    }

    fun enableRatings() {
        Injekt.get<WorkInfoPreferences>().consent().set(WorkInfoPreferences.ENABLED)
        mutableState.update { it.copy(ratingsEnabled = true, ratingsFailed = false) }
        loadRatings()
    }

    fun loadRatings() {
        val ref = reference ?: return
        if (!state.value.ratingsEnabled || ref.provider != WorkProvider.ANILIST) return
        mutableState.update { it.copy(ratingsLoading = true, ratingsFailed = false) }
        screenModelScope.launch(Dispatchers.IO) {
            try {
                val network = context.isOnline() && !Injekt.get<BasePreferences>().downloadedOnly().get()
                val ratings = CommunityRatingsRepository(context).load(ref, network, !state.value.incognito)
                if (ref == reference) {
                    ratings?.totalParts?.let { localTotal = it }
                    mutableState.update {
                        it.copy(
                            ratings = ratings,
                            ratingsLoading = false,
                            total = if (it.selected ==
                                null
                            ) {
                                localTotal
                            } else {
                                it.total
                            },
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (ref == reference) mutableState.update { it.copy(ratingsLoading = false, ratingsFailed = true) }
            }
        }
    }
}

private fun <T> kotlinx.coroutines.flow.Flow<T>.mapToSignal() = map { Unit }
