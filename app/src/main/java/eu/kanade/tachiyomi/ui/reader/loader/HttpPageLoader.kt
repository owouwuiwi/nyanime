package eu.kanade.tachiyomi.ui.reader.loader

import aniyomi.util.DataSaver
import aniyomi.util.DataSaver.Companion.getImage
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.database.models.manga.toDomainChapter
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import okhttp3.Response
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Bounded parallel loading adapted from Mihon v0.21.2 (Apache-2.0).
 * Keep the data saver, a small prefetch window and one budget across adjacent chapters.
 */
internal class HttpPageLoader(
    private val chapter: ReaderChapter,
    private val source: HttpSource,
    private val chapterCache: ChapterCache = Injekt.get(),
    private val sourcePreferences: SourcePreferences = Injekt.get(),
    private val budget: ReaderLoadBudget = ReaderLoadBudget(),
    private val canLoadAhead: () -> Boolean = { true },
) : PageLoader() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobsLock = Any()
    private val downloads = mutableMapOf<Int, Load>()
    private var activePage = chapter.requestedPage
    private val visible = mutableMapOf<Int, Int>()
    private val dataSaver = DataSaver(source, sourcePreferences)

    override var isLocal = false

    override suspend fun getPages(): List<ReaderPage> {
        val pages = try {
            chapterCache.getPageListFromCache(chapter.chapter.toDomainChapter()!!)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            source.getPageList(chapter.chapter)
        }
        return pages.mapIndexed { index, page ->
            ReaderPage(index, page.url, page.imageUrl).also { it.chapter = chapter }
        }
    }

    override suspend fun loadPage(page: ReaderPage): Unit = withIOContext {
        if (isRecycled) return@withIOContext
        if (page.status == Page.State.READY && page.imageUrl?.let(chapterCache::isImageInCache) == false) {
            page.status = Page.State.QUEUE
        }
        synchronized(jobsLock) { visible[page.index] = (visible[page.index] ?: 0) + 1 }
        try {
            if (page.status == Page.State.ERROR) page.status = Page.State.QUEUE
            schedule(page, foreground = true)
            preloadWindow()
            awaitCancellation()
        } finally {
            synchronized(jobsLock) {
                val count = (visible[page.index] ?: 1) - 1
                if (count <= 0) visible.remove(page.index) else visible[page.index] = count
                cancelObsolete()
            }
        }
    }

    override fun setActivePage(index: Int) {
        synchronized(jobsLock) {
            activePage = index
            cancelObsolete()
            chapter.pages?.getOrNull(index)?.let { schedule(it, foreground = true) }
            preloadWindow()
        }
    }

    override fun preload() {
        if (canLoadAhead()) {
            chapter.pages?.getOrNull(chapter.requestedPage)?.let {
                schedule(it, foreground = false)
            }
        }
    }

    override fun retryPage(page: ReaderPage) {
        synchronized(jobsLock) {
            page.status = Page.State.QUEUE
            schedule(page, foreground = true, force = true)
        }
    }

    private fun schedule(page: ReaderPage, foreground: Boolean, force: Boolean = false): Job? =
        synchronized(jobsLock) {
            if (isRecycled || (!force && page.status == Page.State.READY)) return@synchronized null
            val running = downloads[page.index]
            if (running != null && !running.job.isCompleted) {
                if (!force && (!foreground || running.foreground || running.started.get())) {
                    return@synchronized running.job
                }
                // A visible page must not wait behind the background-only semaphore.
                downloads.remove(page.index)
                running.job.cancel()
                page.status = Page.State.QUEUE
            }
            if (!foreground && page.status != Page.State.QUEUE) return@synchronized null
            val started = AtomicBoolean(false)
            val job = scope.launch(start = CoroutineStart.LAZY) {
                running?.job?.cancelAndJoin()
                budget.load(foreground) {
                    started.set(true)
                    currentCoroutineContext().ensureActive()
                    internalLoadPage(page, force)
                }
            }
            val load = Load(job, foreground, started)
            downloads[page.index] = load
            job.invokeOnCompletion {
                synchronized(jobsLock) {
                    if (downloads[page.index] === load) {
                        downloads.remove(page.index)
                        if (job.isCancelled && page.status != Page.State.READY) page.status = Page.State.QUEUE
                    }
                }
            }
            job.start()
            job
        }

    private fun preloadWindow() = synchronized(jobsLock) {
        if (!canLoadAhead() || isRecycled) return@synchronized
        val pages = chapter.pages ?: return@synchronized
        ((activePage - 1).coerceAtLeast(0)..(activePage + PAGES_AHEAD).coerceAtMost(pages.lastIndex))
            .filter { it != activePage }
            .forEach { index ->
                pages.getOrNull(index)?.takeIf { it.status == Page.State.QUEUE }?.let {
                    schedule(it, foreground = false)
                }
            }
    }

    private fun cancelObsolete() {
        val window = if (canLoadAhead()) (activePage - 1)..(activePage + PAGES_AHEAD) else activePage..activePage
        val obsolete = downloads.filterKeys { it !in window && it !in visible }.toList()
        obsolete.forEach { (index, load) ->
            downloads.remove(index)
            chapter.pages?.getOrNull(index)?.takeIf { it.status != Page.State.READY }?.status = Page.State.QUEUE
            load.job.cancel()
        }
    }

    private suspend fun internalLoadPage(page: ReaderPage, force: Boolean) {
        try {
            if (page.imageUrl.isNullOrEmpty()) {
                page.status = Page.State.LOAD_PAGE
                page.imageUrl = source.getImageUrl(page)
            }
            val imageUrl = requireNotNull(page.imageUrl)
            budget.image(imageUrl) {
                if (force || !chapterCache.isImageInCache(imageUrl)) {
                    page.status = Page.State.DOWNLOAD_IMAGE
                    source.getImage(page, dataSaver).use { response ->
                        cacheImage(imageUrl, response)
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            page.stream = { chapterCache.getImageFile(imageUrl).inputStream() }
            page.status = Page.State.READY
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            page.status = Page.State.ERROR
        }
    }

    @OptIn(InternalCoroutinesApi::class)
    private suspend fun cacheImage(imageUrl: String, response: Response) {
        val cancellation = currentCoroutineContext().job.invokeOnCompletion(
            onCancelling = true,
            invokeImmediately = true,
        ) { cause ->
            if (cause != null) {
                // Interrupting a plain socket read alone is insufficient. Close it off the UI thread,
                // even when the loader's scope has already been cancelled.
                Dispatchers.IO.dispatch(EmptyCoroutineContext) {
                    try {
                        response.close()
                    } catch (_: Exception) {
                        // The transfer's worker reports its original error; cleanup must not crash the app.
                    }
                }
            }
        }
        try {
            runInterruptible { chapterCache.putImageToCache(imageUrl, response) }
        } finally {
            cancellation.dispose()
        }
    }

    override fun recycle() {
        super.recycle()
        synchronized(jobsLock) {
            scope.cancel()
            downloads.clear()
            visible.clear()
        }
        chapter.pages?.let { pages ->
            launchIO {
                try {
                    chapterCache.putPageListToCache(
                        chapter.chapter.toDomainChapter()!!,
                        pages.map { Page(it.index, it.url, it.imageUrl) },
                    )
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                }
            }
        }
    }

    private data class Load(val job: Job, val foreground: Boolean, val started: AtomicBoolean)

    private companion object {
        const val PAGES_AHEAD = 4
    }
}
