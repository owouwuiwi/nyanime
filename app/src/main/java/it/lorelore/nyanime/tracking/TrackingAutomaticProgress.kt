package it.lorelore.nyanime.tracking

import android.content.Context
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.data.track.TrackerManager
import tachiyomi.domain.entries.anime.interactor.GetAnime
import tachiyomi.domain.entries.manga.interactor.GetManga
import tachiyomi.domain.track.anime.interactor.GetAnimeTracks
import tachiyomi.domain.track.manga.interactor.GetMangaTracks
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Automatic progress is durably accepted by the same account-bound queue as manual edits. */
object TrackingAutomaticProgress {
    suspend fun enqueue(context: Context, manga: Boolean, entryId: Long, progress: Double, trackerId: Long? = null) =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            enqueueOnIo(context, manga, entryId, progress, trackerId)
        }

    private suspend fun enqueueOnIo(
        context: Context,
        manga: Boolean,
        entryId: Long,
        progress: Double,
        trackerId: Long?,
    ) {
        if (eu.kanade.tachiyomi.BuildConfig.ISOLATED_CLOUD_PROBE) return
        if (!progress.isFinite() ||
            progress !in 0.0..1_000_000.0 ||
            Injekt.get<BasePreferences>().incognitoMode().get()
        ) {
            return
        }
        val store = PersonalTrackingStore.get(context)
        val manager = Injekt.get<TrackerManager>()
        var titleHint = ""
        val subject = if (manga) {
            Injekt.get<GetManga>().await(entryId)?.let {
                titleHint = it.title.take(2048)
                TrackingSubject(TrackingMedium.MANGA, it.source, it.url)
            }
        } else {
            Injekt.get<GetAnime>().await(entryId)?.let {
                titleHint = it.title.take(2048)
                TrackingSubject(TrackingMedium.ANIME, it.source, it.url)
            }
        } ?: return
        val isPrivate = if (manga) {
            Injekt.get<eu.kanade.domain.source.manga.interactor.GetMangaIncognitoState>().await(subject.source)
        } else {
            Injekt.get<eu.kanade.domain.source.anime.interactor.GetAnimeIncognitoState>().await(subject.source)
        }
        if (isPrivate) return
        PersonalTrackingObserver.observe(context, manga, entryId, progress)
        data class Target(
            val id: Long,
            val tracker: Long,
            val remote: Long,
            val values: TrackingValues,
            val remoteUrl: String,
        )
        val targets = if (manga) {
            Injekt.get<GetMangaTracks>().await(entryId).map {
                Target(it.id, it.trackerId, it.remoteId, it.trackingValues(), it.remoteUrl)
            }
        } else {
            Injekt.get<GetAnimeTracks>().await(entryId).map {
                Target(it.id, it.trackerId, it.remoteId, it.trackingValues(), it.remoteUrl)
            }
        }
        var queued = false
        for (target in targets.filter { trackerId == null || it.tracker == trackerId }) {
            val service = manager.get(target.tracker)?.takeIf { it.isLoggedIn } ?: continue
            if (progress <= target.values.progress) continue
            val key = TrackingEditGate.key(manga, target.id)
            val gate = TrackingEditGate.entry(key)
            val generation = gate.progressGeneration.get()
            val account = service.getUsername()
            val epoch = runCatching { TrackingAccountBinding.current(service, subject) }.getOrNull() ?: continue
            synchronized(gate.mutations) {
                if (generation != gate.progressGeneration.get() ||
                    !service.isLoggedIn ||
                    service.getUsername() != account
                ) {
                    return@synchronized
                }
                val incoming = PendingTrackingEdit(
                    subject, entryId, target.id, target.tracker, target.remote, account,
                    TrackingPatch(target.values.copy(progress = progress), setOf(TrackingField.PROGRESS)),
                    System.currentTimeMillis().coerceAtLeast(1), origin = TrackingEditOrigin.AUTOMATIC,
                    accountEpoch = epoch, base = target.values, automaticProgress = true,
                    titleHint = titleHint, remoteUrl = target.remoteUrl,
                )
                val pending = store.pending(key)
                if (!TrackingQueuePolicy.acceptsAutomatic(pending, incoming)) return@synchronized
                val current = pending?.patch?.applyTo(target.values) ?: target.values
                if (progress <= current.progress) return@synchronized
                store.enqueue(
                    incoming.copy(
                        patch = TrackingPatch(current.copy(progress = progress), setOf(TrackingField.PROGRESS)),
                    ),
                    current,
                )
                gate.generation.incrementAndGet()
                queued = true
            }
        }
        if (queued) TrackingEditWorker.schedule(context)
    }
}
