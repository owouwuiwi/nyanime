package eu.kanade.tachiyomi.data.reading

import android.app.Activity
import android.content.Context
import eu.kanade.domain.entries.manga.model.toDomainManga
import eu.kanade.domain.entries.manga.model.toSManga
import eu.kanade.domain.items.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.source.manga.interactor.GetMangaIncognitoState
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.releases.ReleaseMedium
import eu.kanade.tachiyomi.data.releases.ReleaseUpdateGate
import eu.kanade.tachiyomi.data.watch.WatchCatalogReference
import eu.kanade.tachiyomi.data.watch.WatchTogetherManager
import eu.kanade.tachiyomi.source.MangaSourceUpdateGate
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import tachiyomi.domain.entries.manga.interactor.GetManga
import tachiyomi.domain.entries.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.items.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.source.manga.service.MangaSourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.ref.WeakReference

@Serializable
data class ReadingBookmark(val mangaId: Long, val chapterId: Long, val position: ReadingPosition) {
    fun valid(): Boolean = mangaId > 0 &&
        chapterId > 0 &&
        position.pages in 1..5000 &&
        position.page in 0 until position.pages &&
        position.title.isNotBlank()
}
data class ReadingTools(
    val visible: Boolean = true,
    val drawing: Boolean = false,
    val color: Int = 0,
    val width: Int = 2,
    val opening: Boolean = false,
    val error: String? = null,
    val returnTo: ReadingBookmark? = null,
    val navigation: Long = 0,
    val noteDraft: String? = null,
    val chapterNotice: ReadingSelection? = null,
    val fingerprintRevision: Long = 0,
)

/** Android catalog/navigation adapter. The room itself never calls into a reader or native player. */
class ReadingTogetherManager private constructor(context: Context) {
    val watch = WatchTogetherManager.get(context)
    val controller get() = watch.reading
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val text = eu.kanade.tachiyomi.data.watch.RoomText.from(context)
    private val preferences = context.getSharedPreferences("reading_together", Context.MODE_PRIVATE)
    private val returnPoint = ReadingReturnPoint(
        runCatching {
            eu.kanade.tachiyomi.data.watch.watchJson.decodeFromString<ReadingReturnCheckpoint>(
                preferences.getString("return_checkpoint", "").orEmpty(),
            )
        }.getOrNull(),
    )
    private val mutableTools = MutableStateFlow(
        ReadingTools(visible = preferences.getBoolean("visible", true)),
    )
    val tools = mutableTools.asStateFlow()
    private var owner = WeakReference<ReaderActivity>(null)
    private var lastReader = WeakReference<ReaderActivity>(null)
    private var current: ReadingBookmark? = null
    private var resolution: Job? = null
    private var target: ReadingBookmark? = null
    private var returning = false
    private var privacyJob: Job? = null
    private var observedSource: Long? = null
    private var selectionRoom = ""
    private var lastSelection: ReadingSelection? = null
    private var automaticSelection: ReadingSelection? = null
    private var chapterTarget: Pair<Long, Long>? = null
    private var openingRevision = 0L
    private var navigationToken = 0L
    private val fingerprintSlots = Semaphore(2)
    private val fingerprints = linkedMapOf<String, String>()
    private val fingerprintPositions = linkedMapOf<String, ReadingPosition>()
    private val fingerprintJobs = mutableMapOf<String, Job>()
    private val bindings = runCatching {
        eu.kanade.tachiyomi.data.watch.watchJson.decodeFromString<List<ReadingCopyBinding>>(
            preferences.getString("copies", "[]").orEmpty(),
        ).filter { it.valid() }.takeLast(64)
    }.getOrDefault(emptyList()).toMutableList()
    private fun roomHash(invite: String = controller.state.value.invite) = eu.kanade.tachiyomi.data.watch.watchHash(
        invite.toByteArray(),
    ).let { bytes -> bytes.joinToString("") { "%02x".format(it) } }

    fun forgetRoom() {
        val hash = roomHash()
        if (bindings.removeAll { it.room == hash }) persistBindings()
        returnPoint.clear()
        mutableTools.value = tools.value.copy(returnTo = null)
        persistReturn()
    }

    private fun persistBindings() {
        preferences.edit().putString(
            "copies",
            eu.kanade.tachiyomi.data.watch.watchJson.encodeToString(bindings),
        ).apply()
    }

    fun fingerprint(position: ReadingPosition): String? = fingerprints["${position.chapterKey}:${position.page}"]

    fun verifyPage(page: eu.kanade.tachiyomi.ui.reader.model.ReaderPage, position: ReadingPosition) {
        if (!controller.state.value.active ||
            !position.valid() ||
            Injekt.get<GetMangaIncognitoState>().await(position.source)
        ) {
            return
        }
        val key = "${position.chapterKey}:${position.page}"
        if (key in fingerprints || fingerprintJobs[key]?.isActive == true) return
        val room = controller.state.value.invite
        fingerprintJobs[key] = scope.launch {
            try {
                withTimeout(15000) {
                    page.statusFlow.first {
                        it == eu.kanade.tachiyomi.source.model.Page.State.READY ||
                            it == eu.kanade.tachiyomi.source.model.Page.State.ERROR
                    }
                    val open = page.stream ?: return@withTimeout
                    val hash = withContext(Dispatchers.IO) {
                        fingerprintSlots.withPermit { ReadingPageFingerprint.calculate(open) }
                    } ?: return@withTimeout
                    if (room != controller.state.value.invite || !controller.state.value.active) return@withTimeout
                    fingerprints[key] = hash
                    fingerprintPositions[key] = position.copy(fingerprint = hash)
                    while (fingerprints.size > 128) {
                        val oldest = fingerprints.keys.first()
                        fingerprints.remove(oldest)
                        fingerprintPositions.remove(oldest)
                    }
                    current?.takeIf {
                        it.position.chapterKey == position.chapterKey && it.position.page == position.page
                    }
                        ?.let { bookmark ->
                            current = bookmark.copy(position = bookmark.position.copy(fingerprint = hash))
                            current?.let(::publish)
                        }
                    mutableTools.value = tools.value.copy(fingerprintRevision = tools.value.fingerprintRevision + 1)
                }
            } catch (error: CancellationException) {
                if (error !is kotlinx.coroutines.TimeoutCancellationException) throw error
            } catch (_: Exception) {
                // Fingerprint failure never prevents reading or changes a local file.
            } finally {
                fingerprintJobs.remove(key)
            }
        }
    }

    fun pageForAnnotations(position: ReadingPosition): ReadingPosition? {
        val binding = bindings.lastOrNull { it.room == roomHash() && it.local.key == position.chapterKey }
            ?: return position
        val hash = fingerprint(position) ?: return null
        return controller.matchingPage(binding.shared.key, hash) ?: controller.state.value.members.values
            .mapNotNull { it.position }.filter {
                it.chapterKey == binding.shared.key && ReadingPageFingerprint.matches(hash, it.fingerprint)
            }.distinctBy { it.pageKey }.singleOrNull()
    }

    suspend fun copyTitles(): List<tachiyomi.domain.entries.manga.model.Manga> = withContext(Dispatchers.IO) {
        val selection = watch.readingSession.state.value.selection?.chapter
        val repository = Injekt.get<tachiyomi.domain.entries.manga.repository.MangaRepository>()
        (repository.getMangaFavorites() + repository.getReadMangaNotInLibrary()).distinctBy { it.id }
            .filter { !Injekt.get<GetMangaIncognitoState>().await(it.source) }
            .sortedWith(
                compareByDescending<tachiyomi.domain.entries.manga.model.Manga> {
                    selection?.sameCatalog(
                        ReadingChapter(
                            it.source,
                            it.url,
                            "local",
                            it.title.take(240),
                            "local",
                            catalogIds(it.source, it.url),
                        ),
                    ) == true
                }.thenBy { it.title.lowercase() },
            )
    }

    private fun catalogIds(source: Long, manga: String): Map<String, Long> {
        val hints = Injekt.get<eu.kanade.tachiyomi.data.track.ContinuityIdentityCache>()
            .get("manga", source, manga, Injekt.get<GetMangaIncognitoState>().await(source))
        return listOfNotNull(
            hints?.anilistId?.let { "anilist" to it },
            hints?.malId?.let { "myanimelist" to it },
            hints?.mangaUpdatesId?.let { "mangaupdates" to it },
        ).toMap()
    }

    suspend fun copyChapters(mangaId: Long): List<tachiyomi.domain.items.chapter.model.Chapter> =
        withContext(Dispatchers.IO) {
            val chapters = Injekt.get<GetChaptersByMangaId>()
            var result = chapters.await(mangaId)
            if (result.isEmpty()) {
                val manga = Injekt.get<GetManga>().await(mangaId) ?: return@withContext emptyList()
                require(!Injekt.get<GetMangaIncognitoState>().await(manga.source))
                val source = Injekt.get<MangaSourceManager>().get(manga.source) as? HttpSource
                    ?: return@withContext emptyList()
                ReleaseUpdateGate.withEntry(ReleaseMedium.MANGA, manga.id) {
                    val update = MangaSourceUpdateGate.await(
                        source,
                        manga.toSManga(),
                        emptyList(),
                        fetchDetails = false,
                        fetchChapters = true,
                    )
                    Injekt.get<SyncChaptersWithSource>().await(update.chapters, manga, source)
                }
                result = chapters.await(mangaId)
            }
            result.sortedBy { it.sourceOrder }
        }

    suspend fun bindCopy(mangaId: Long, chapterId: Long): Boolean {
        val room = controller.state.value.invite
        val shared = watch.readingSession.state.value.selection ?: return false
        val binding = withContext(Dispatchers.IO) {
            val manga = Injekt.get<GetManga>().await(mangaId) ?: return@withContext null
            val chapter = Injekt.get<GetChaptersByMangaId>().await(mangaId).singleOrNull { it.id == chapterId }
                ?: return@withContext null
            if (Injekt.get<GetMangaIncognitoState>().await(manga.source)) return@withContext null
            ReadingCopyBinding(
                roomHash(),
                shared.chapter,
                ReadingChapter(manga.source, manga.url, chapter.url, manga.title.take(240), chapter.name.take(240)),
                manga.id,
                chapter.id,
            ).takeIf { it.valid() }
        } ?: return false
        if (room != controller.state.value.invite || shared != watch.readingSession.state.value.selection) return false
        bindings.removeAll { it.room == binding.room && it.shared.key == binding.shared.key }
        bindings += binding
        while (bindings.size > 64) bindings.removeAt(0)
        persistBindings()
        watch.foregroundActivity()?.let { openShared(it, shared) }
        return true
    }

    init {
        if (!watch.controller.state.value.active && !watch.recoverable.value) returnPoint.clear()
        scope.launch {
            watch.controller.state.map {
                it.invite.takeIf { _ -> it.active }
            }.distinctUntilChanged().collect { invite ->
                syncReturnRoom()
                if (invite == null) {
                    resolution?.cancel()
                    fingerprintJobs.values.toList().forEach { it.cancel() }
                    fingerprintJobs.clear()
                    fingerprints.clear()
                    fingerprintPositions.clear()
                    automaticSelection = null
                    chapterTarget = null
                    openingRevision = 0
                    watch.navigation.cancel()
                    target = null
                    returning = false
                    mutableTools.value = tools.value.copy(
                        drawing = false,
                        opening = false,
                        noteDraft = null,
                        chapterNotice = null,
                        error = null,
                    )
                }
            }
        }
        scope.launch {
            watch.controller.state.map { it.active to it.sharedReadingMode }.distinctUntilChanged().collect { mode ->
                if (!mode.first || !mode.second) {
                    resolution?.cancel()
                    automaticSelection = null
                    chapterTarget = null
                    openingRevision = 0
                    mutableTools.value = tools.value.copy(opening = false, chapterNotice = null)
                }
            }
        }
        scope.launch {
            combine(
                watch.readingSession.state.map { it.selection }.distinctUntilChanged(),
                watch.controller.state.map {
                    Triple(it.active, it.invite, it.sharedReadingMode)
                }.distinctUntilChanged(),
            ) { selected, _ -> selected }.collect { selected ->
                val room = watch.controller.state.value
                if (selectionRoom != room.invite) {
                    selectionRoom = room.invite
                    lastSelection = null
                    if (room.active && bindings.removeAll { it.room != roomHash() }) persistBindings()
                }
                // Selection and mode are separate flows. Do not consume a choice before the
                // coordinator has applied its medium, or a resumed collector could miss opening it.
                if (selected != null && (!room.active || !room.sharedReadingMode)) return@collect
                if (selected == lastSelection) return@collect
                val previous = lastSelection
                lastSelection = selected
                resolution?.cancel()
                chapterTarget = null
                mutableTools.value = tools.value.copy(opening = false, chapterNotice = null)
                if (selected == null || room.host || !room.sharedReadingMode) return@collect
                mutableTools.value = tools.value.copy(chapterNotice = selected)
                if (watch.readingSession.state.value.personal) return@collect
                // Chapter changes are invitations; a new shared title opens without stealing pages.
                val sameManga = previous?.chapter?.let {
                    it.source == selected.chapter.source && it.manga == selected.chapter.manga
                } == true
                automaticSelection = selected.takeUnless { sameManga }
                watch.foregroundActivity()?.let(::present)
            }
        }
    }

    fun attach(activity: ReaderActivity) {
        owner = WeakReference(activity)
        lastReader = WeakReference(activity)
        if (eu.kanade.tachiyomi.ui.player.session.PlaybackSessions.current.value == null &&
            !watch.controller.observing
        ) {
            watch.controller.setReadingMode(true)
        }
        current?.takeIf { it.mangaId == activity.viewModel.manga?.id }?.let { publish(it) }
    }

    fun present(activity: Activity) {
        val selected = automaticSelection ?: return
        if (watch.readingSession.state.value.personal || tools.value.opening) return
        automaticSelection = null
        openShared(activity, selected)
    }

    fun chapterEntered(chapter: ReadingChapter) {
        scope.launch {
            if (!controller.state.value.active ||
                !chapter.valid() ||
                Injekt.get<GetMangaIncognitoState>().await(chapter.source) ||
                Injekt.get<MangaSourceManager>().get(chapter.source) !is HttpSource
            ) {
                return@launch
            }
            watch.readingSession.roomChanged(watch.controller.readingSessionContext())
            watch.readingSession.choose(chapter.copy(catalogIds = catalogIds(chapter.source, chapter.manga)))
        }
    }

    fun readPersonally(value: Boolean) {
        resolution?.cancel()
        watch.navigation.cancel()
        automaticSelection = null
        chapterTarget = null
        openingRevision = 0
        watch.readingSession.personal(value)
        mutableTools.value = tools.value.copy(opening = false, drawing = false)
        if (!value) {
            watch.foregroundActivity()?.let { activity ->
                val room = watch.controller.state.value
                when {
                    room.sharedReadingMode -> watch.readingSession.state.value.selection?.let {
                        openShared(activity, it)
                    }
                    room.liveMode -> watch.openSelectedLive()
                    room.media != null -> watch.openSelectedVideo()
                }
            }
        }
    }

    fun reachShared(activity: Activity) {
        watch.readingSession.personal(false)
        watch.readingSession.state.value.selection?.let { openShared(activity, it) }
    }

    fun proposeCurrent(): Boolean {
        val sent = currentPage()?.chapterReference()?.let(watch.readingSession::propose) == true
        if (!sent) fail(text(R.string.room_reading_command_failed))
        return sent
    }

    private fun openShared(activity: Activity, selected: ReadingSelection) {
        if (!controller.state.value.active || selected != watch.readingSession.state.value.selection) return
        resolution?.cancel()
        watch.navigation.cancel()
        navigationToken = watch.navigation.select(
            controller.state.value.invite,
            "reading:${selected.revision}:${selected.chapter.key}",
        )
        val token = navigationToken
        val room = controller.state.value.invite
        val weak = WeakReference(activity)
        openingRevision = selected.revision
        watch.readingSession.receipt(ReadingOpening.Opening)
        mutableTools.value = tools.value.copy(opening = true, error = null, drawing = false)
        resolution = scope.launch {
            try {
                val bookmark = withTimeout(45000) {
                    val binding = bindings.lastOrNull { it.room == roomHash() && it.shared.key == selected.chapter.key }
                    if (binding != null && validBinding(binding)) {
                        binding.mangaId to binding.chapterId
                    } else {
                        resolveCatalog(selected.chapter)
                    }
                }
                val screen = weak.get() ?: return@launch
                if (watch.navigation.current(token) && watch.foregroundActivity() == null) automaticSelection = selected
                if (!watch.navigation.current(token) ||
                    !controller.state.value.active ||
                    room != controller.state.value.invite ||
                    screen.isDestroyed ||
                    screen.isFinishing ||
                    watch.foregroundActivity() == null ||
                    selected != watch.readingSession.state.value.selection ||
                    watch.readingSession.state.value.personal
                ) {
                    return@launch
                }
                if (screen is ReaderActivity &&
                    screen.viewModel.manga?.id == bookmark.first &&
                    screen.viewModel.state.value.currentChapter?.chapter?.id == bookmark.second
                ) {
                    sharedArrived(bookmark.first, bookmark.second)
                } else {
                    if (current?.mangaId != bookmark.first || current?.chapterId != bookmark.second) rememberReturn()
                    chapterTarget = bookmark
                    lastReader.get()?.takeUnless { it.isFinishing || it.isDestroyed }?.finish()
                    screen.startActivity(ReaderActivity.newIntent(screen, bookmark.first, bookmark.second))
                }
            } catch (error: CancellationException) {
                if (error is kotlinx.coroutines.TimeoutCancellationException && watch.navigation.current(token)) {
                    fail(text(R.string.room_reading_source_error))
                } else {
                    throw error
                }
            } catch (error: Exception) {
                if (watch.navigation.current(token)) {
                    val missing = Injekt.get<MangaSourceManager>().get(selected.chapter.source) == null
                    watch.readingSession.receipt(if (missing) ReadingOpening.SourceMissing else ReadingOpening.Failed)
                    fail(error.message ?: text(R.string.room_page_open_failed))
                }
            } finally {
                if (watch.navigation.current(token) && chapterTarget == null) {
                    mutableTools.value = tools.value.copy(opening = false)
                }
            }
        }
    }

    fun sharedArrived(mangaId: Long, chapterId: Long, chapter: ReadingChapter? = null) {
        if (watch.controller.state.value.host) {
            if (chapter?.key == watch.readingSession.state.value.selection?.chapter?.key) {
                watch.readingSession.receipt(ReadingOpening.Ready)
            }
        }
        if (chapterTarget != null && chapterTarget != (mangaId to chapterId)) return
        if (openingRevision == 0L ||
            openingRevision != watch.readingSession.state.value.selection?.revision ||
            !watch.navigation.current(navigationToken)
        ) {
            return
        }
        chapterTarget = null
        openingRevision = 0
        watch.readingSession.receipt(ReadingOpening.Ready)
        mutableTools.value = tools.value.copy(
            opening = false,
            error = null,
            chapterNotice = null,
            navigation = tools.value.navigation + 1,
        )
    }

    fun detach(activity: ReaderActivity) {
        if (owner.get() !== activity) return
        owner.clear()
        controller.suspendReading()
        setDrawing(false)
    }

    fun selected(activity: ReaderActivity, bookmark: ReadingBookmark?) {
        if (owner.get() !== activity) return
        current = bookmark
        if (bookmark?.position?.source != observedSource) {
            privacyJob?.cancel()
            observedSource = bookmark?.position?.source
            privacyJob = scope.launch {
                Injekt.get<GetMangaIncognitoState>().subscribe(observedSource).collect { private ->
                    if (private) {
                        controller.position(null, false)
                        setDrawing(false)
                        persistReturn()
                    } else if (owner.get() != null) {
                        current?.let(::publish)
                    }
                }
            }
        }
        if (bookmark != null) publish(bookmark) else controller.position(null, false)
    }

    private fun publish(bookmark: ReadingBookmark) {
        if (!watch.controller.readingMode) return
        val private = Injekt.get<GetMangaIncognitoState>().await(bookmark.position.source)
        val shared = bookmark.position.takeIf {
            !private && it.valid() && Injekt.get<MangaSourceManager>().get(it.source) is HttpSource
        }
        controller.position(shared?.copy(fingerprint = fingerprint(shared).orEmpty()), shared != null)
    }

    fun currentPage(): ReadingPosition? = current?.position?.takeIf {
        owner.get() != null && it.valid() && Injekt.get<MangaSourceManager>().get(it.source) is HttpSource
    }?.takeUnless {
        Injekt.get<GetMangaIncognitoState>().await(it.source)
    }

    fun setVisible(value: Boolean) {
        preferences.edit().putBoolean("visible", value).apply()
        mutableTools.value =
            tools.value.copy(visible = value, drawing = tools.value.drawing && value)
    }
    fun setDrawing(value: Boolean) {
        if (value &&
            (
                !controller.state.value.active ||
                    !controller.state.value.supported ||
                    currentPage()?.let(::pageForAnnotations) == null
                )
        ) {
            return
        }
        mutableTools.value =
            tools.value.copy(drawing = value, visible = if (value) true else tools.value.visible, noteDraft = null)
    }
    fun startNote(text: String): Boolean {
        val draft = text.trim()
        if (draft.isEmpty() ||
            draft.length > 280 ||
            currentPage()?.let(::pageForAnnotations) == null ||
            !controller.state.value.active ||
            !controller.state.value.crdtEnabled
        ) {
            return false
        }
        mutableTools.value = tools.value.copy(noteDraft = draft, drawing = false, visible = true)
        return true
    }
    fun finishNote() {
        mutableTools.value = tools.value.copy(noteDraft = null)
    }
    fun setColor(value: Int) {
        if (value in 0..4) mutableTools.value = tools.value.copy(color = value)
    }
    fun setWidth(value: Int) {
        if (value in 1..3) mutableTools.value = tools.value.copy(width = value)
    }
    fun dismissError() {
        mutableTools.value = tools.value.copy(error = null)
    }

    fun jump(activity: Activity, position: ReadingPosition) {
        if (!controller.state.value.active || !position.valid() || tools.value.opening) return
        val binding = bindings.lastOrNull { it.room == roomHash() && it.shared.key == position.chapterKey }
        if (binding != null) {
            val verified = fingerprintPositions.values.filter {
                it.chapterKey == binding.local.key &&
                    ReadingPageFingerprint.matches(it.fingerprint, position.fingerprint)
            }.singleOrNull()
            if (verified == null) {
                fail(text(R.string.room_reading_page_unverified))
                return
            }
            resolve(activity, verified, null)
        } else {
            resolve(activity, position, null)
        }
    }

    fun returnToOwn(activity: Activity) {
        if (!watch.controller.state.value.active) return
        syncReturnRoom()
        val bookmark = returnPoint.bookmark ?: return
        if (tools.value.opening) return
        resolve(activity, bookmark.position, bookmark)
    }

    private fun resolve(activity: Activity, position: ReadingPosition, saved: ReadingBookmark?) {
        resolution?.cancel()
        val activityRef = WeakReference(activity)
        val roomId = controller.state.value.invite
        watch.navigation.cancel()
        val token = watch.navigation.select(roomId, "page:${position.pageKey}")
        mutableTools.value = tools.value.copy(opening = true, error = null, drawing = false)
        resolution = scope.launch {
            try {
                val bookmark = withTimeout(45000) {
                    if (saved != null && validLocalBookmark(saved)) saved else resolveCatalog(position)
                }
                val screen = activityRef.get() ?: return@launch
                if (screen.isFinishing ||
                    screen.isDestroyed ||
                    !controller.state.value.active ||
                    roomId != controller.state.value.invite ||
                    !watch.navigation.current(token)
                ) {
                    return@launch
                }
                if (saved == null && bookmark != current) rememberReturn()
                target = bookmark
                returning = saved != null
                if (screen is ReaderActivity &&
                    screen.viewModel.manga?.id == bookmark.mangaId &&
                    screen.viewModel.state.value.currentChapter?.chapter?.id == bookmark.chapterId
                ) {
                    screen.openReadingPage(bookmark)
                } else {
                    // ReaderActivity is singleTask: finish its old ViewModel before opening another title.
                    lastReader.get()?.takeUnless { it.isFinishing || it.isDestroyed }?.finish()
                    screen.startActivity(ReaderActivity.newIntent(screen, bookmark.mangaId, bookmark.chapterId))
                }
            } catch (e: CancellationException) {
                if (e is kotlinx.coroutines.TimeoutCancellationException) {
                    fail(
                        text(R.string.room_reading_source_error),
                    )
                } else {
                    throw e
                }
            } catch (e: Exception) {
                fail(
                    (e as? IllegalArgumentException)?.message
                        ?: text(R.string.room_page_open_failed),
                )
            } finally {
                if (watch.navigation.current(token)) mutableTools.value = tools.value.copy(opening = false)
            }
        }
    }

    fun requested(mangaId: Long, chapterId: Long): ReadingBookmark? = target?.takeIf {
        it.mangaId == mangaId &&
            it.chapterId == chapterId
    }

    fun arrived(bookmark: ReadingBookmark) {
        if (target != bookmark) return
        target = null
        mutableTools.value = tools.value.copy(navigation = tools.value.navigation + 1, error = null)
        if (returning) {
            returnPoint.clear()
            mutableTools.value = tools.value.copy(returnTo = null)
            persistReturn()
        }
        returning = false
    }

    fun fail(message: String) {
        target = null
        if (chapterTarget != null ||
            watch.controller.state.value.host
        ) {
            watch.readingSession.receipt(ReadingOpening.Failed)
        }
        chapterTarget = null
        mutableTools.value = tools.value.copy(error = message, opening = false)
    }

    private fun syncReturnRoom() {
        val room = watch.controller.state.value
        returnPoint.roomChanged(room.invite.takeIf { room.active }?.let(::roomHash))
        mutableTools.value = tools.value.copy(returnTo = returnPoint.bookmark)
        persistReturn()
    }

    private fun rememberReturn() {
        syncReturnRoom()
        returnPoint.remember(current)
        persistReturn()
    }

    private fun persistReturn() {
        if (returnPoint.checkpoint?.bookmark?.position?.source?.let {
                Injekt.get<GetMangaIncognitoState>().await(it)
            } == true
        ) {
            returnPoint.clear()
        }
        mutableTools.value = tools.value.copy(returnTo = returnPoint.bookmark)
        // Old, unscoped checkpoints cannot be attributed to a room safely.
        preferences.edit().remove("return").putString(
            "return_checkpoint",
            returnPoint.checkpoint?.let {
                eu.kanade.tachiyomi.data.watch.watchJson.encodeToString(it)
            },
        ).apply()
    }

    private suspend fun validLocalBookmark(bookmark: ReadingBookmark): Boolean = withContext(Dispatchers.IO) {
        val manga = Injekt.get<GetManga>().await(bookmark.mangaId) ?: return@withContext false
        manga.source == bookmark.position.source &&
            manga.url == bookmark.position.manga &&
            Injekt.get<GetChaptersByMangaId>().await(manga.id).any {
                it.id == bookmark.chapterId && it.url == bookmark.position.chapter
            }
    }

    private suspend fun validBinding(binding: ReadingCopyBinding): Boolean = withContext(Dispatchers.IO) {
        val manga = Injekt.get<GetManga>().await(binding.mangaId) ?: return@withContext false
        manga.source == binding.local.source &&
            manga.url == binding.local.manga &&
            !Injekt.get<GetMangaIncognitoState>().await(manga.source) &&
            Injekt.get<GetChaptersByMangaId>().await(manga.id).any {
                it.id == binding.chapterId && it.url == binding.local.chapter
            }
    }

    private suspend fun resolveCatalog(position: ReadingPosition): ReadingBookmark {
        val (mangaId, chapterId) = resolveCatalog(position.chapterReference())
        return ReadingBookmark(mangaId, chapterId, position)
    }

    private suspend fun resolveCatalog(position: ReadingChapter): Pair<Long, Long> = withContext(Dispatchers.IO) {
        require(!Injekt.get<GetMangaIncognitoState>().await(position.source)) {
            text(R.string.room_incognito_reading)
        }
        val sources = Injekt.get<MangaSourceManager>()
        sources.isInitialized.first { it }
        val source = sources.get(position.source) as? HttpSource
            ?: throw IllegalArgumentException(
                text(R.string.room_reading_extension_required),
            )
        require(
            WatchCatalogReference.isAllowed(position.manga, source.baseUrl) &&
                WatchCatalogReference.isAllowed(position.chapter, source.baseUrl),
        ) {
            text(R.string.room_reading_reference_error)
        }
        val manga = Injekt.get<NetworkToLocalManga>().await(
            SManga.create().apply {
                url = position.manga
                title = position.title
            }.toDomainManga(source.id),
        )
        val chapters = Injekt.get<GetChaptersByMangaId>()
        var chapter = chapters.await(manga.id).singleOrNull { it.url == position.chapter }
        if (chapter == null) {
            ReleaseUpdateGate.withEntry(ReleaseMedium.MANGA, manga.id) {
                val update = MangaSourceUpdateGate.await(
                    source,
                    manga.toSManga(),
                    emptyList(),
                    fetchDetails = false,
                    fetchChapters = true,
                )
                Injekt.get<SyncChaptersWithSource>().await(update.chapters, manga, source)
            }
            chapter = chapters.await(manga.id).singleOrNull { it.url == position.chapter }
        }
        requireNotNull(chapter) {
            text(R.string.room_chapter_edition_error)
        }
        manga.id to chapter.id
    }

    companion object {
        @Volatile private var instance: ReadingTogetherManager? = null
        fun existing(): ReadingTogetherManager? = instance
        fun get(context: Context): ReadingTogetherManager = instance ?: synchronized(this) {
            instance ?: ReadingTogetherManager(context.applicationContext).also { instance = it }
        }
    }
}
