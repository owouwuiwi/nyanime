package eu.kanade.tachiyomi.data.download.manga

import android.content.Context
import aniyomi.util.DataSaver
import aniyomi.util.DataSaver.Companion.getImage
import com.hippo.unifile.UniFile
import eu.kanade.domain.entries.manga.model.getComicInfo
import eu.kanade.domain.items.chapter.model.toSChapter
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.download.anime.VideoStorageException
import eu.kanade.tachiyomi.data.download.manga.model.MangaDownload
import eu.kanade.tachiyomi.data.download.transfer.DownloadBandwidth
import eu.kanade.tachiyomi.data.download.transfer.MangaImageTransfer
import eu.kanade.tachiyomi.data.download.transfer.TransferCoordinator
import eu.kanade.tachiyomi.data.download.transfer.TransferPhase
import eu.kanade.tachiyomi.data.download.transfer.TransferWait
import eu.kanade.tachiyomi.data.download.transfer.selectDownloads
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.storage.DiskUtil.NOMEDIA_FILE
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.withPermit
import logcat.LogPriority
import mihon.core.archive.ZipWriter
import nl.adaptivity.xmlutil.serialization.XML
import okhttp3.Response
import okio.Throttler
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.storage.extension
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.core.metadata.comicinfo.COMIC_INFO_FILE
import tachiyomi.core.metadata.comicinfo.ComicInfo
import tachiyomi.domain.category.manga.interactor.GetMangaCategories
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.items.chapter.model.Chapter
import tachiyomi.domain.source.manga.service.MangaSourceManager
import tachiyomi.domain.track.manga.interactor.GetMangaTracks
import tachiyomi.i18n.MR
import tachiyomi.i18n.aniyomi.AYMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * This class is the one in charge of downloading chapters.
 *
 * Its queue contains the list of chapters to download. In order to download them, the downloader
 * subscription must be running and the list of chapters must be sent to them by [downloadsRelay].
 *
 * The queue manipulation must be done in one thread (currently the main thread) to avoid unexpected
 * behavior, but it's safe to read it from multiple threads.
 */
class MangaDownloader(
    private val context: Context,
    private val provider: MangaDownloadProvider,
    private val cache: MangaDownloadCache,
    private val sourceManager: MangaSourceManager = Injekt.get(),
    private val chapterCache: ChapterCache = Injekt.get(),
    private val downloadPreferences: DownloadPreferences = Injekt.get(),
    private val xml: XML = Injekt.get(),
    private val getCategories: GetMangaCategories = Injekt.get(),
    private val getMangaTracks: GetMangaTracks = Injekt.get(),
    // SY -->
    private val sourcePreferences: SourcePreferences = Injekt.get(),
    // SY <--
) {

    /**
     * Store for persisting downloads across restarts.
     */
    private val store = MangaDownloadStore(context)

    /**
     * Queue where active downloads are kept.
     */
    private val _queueState = MutableStateFlow<List<MangaDownload>>(emptyList())
    val queueState = _queueState.asStateFlow()

    /**
     * Notifier for the downloader state and progress.
     */
    private val notifier by lazy { MangaDownloadNotifier(context) }

    /**
     * The throttler used to control the download speed.
     */
    private val throttler get() = DownloadBandwidth.throttler

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloaderJob: Job? = null
    private var stoppingJob: Job? = null
    private val queueRevision = MutableStateFlow(0L)
    val ready = CompletableDeferred<Unit>()
    suspend fun awaitStopped() {
        stoppingJob?.join()
    }

    /**
     * Whether the downloader is running.
     */
    val isRunning: Boolean
        get() = downloaderJob?.isActive ?: false

    /**
     * Whether the downloader is paused
     */
    @Volatile
    var isPaused: Boolean = false

    init {
        scope.launch {
            try {
                sourceManager.catalogueSources.collect {
                    val candidates = store.restore()
                    val restored = withUIContext {
                        candidates.filter { saved ->
                            store.isQueued(saved) &&
                                queueState.value.none { it.chapter.id == saved.chapter.id }
                        }
                            .also { addAllToQueue(it) }
                    }
                    ready.complete(Unit)
                    if (restored.isNotEmpty() && !downloadPreferences.downloadManuallyPaused(false).get()) {
                        MangaDownloadJob.start(context, userInitiated = false)
                    }
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                logcat(LogPriority.ERROR, error) { "Could not restore the manga download queue" }
                withUIContext { notifier.onError(error.message) }
                ready.complete(Unit)
            }
        }
    }

    /**
     * Starts the downloader. It doesn't do anything if it's already running or there isn't anything
     * to download.
     *
     * @return true if the downloader is started, false otherwise.
     */
    fun start(): Boolean {
        if (isRunning || queueState.value.isEmpty()) {
            return false
        }

        val pending = queueState.value.filter { it.status.value <= MangaDownload.State.DOWNLOADING.value }
        if (pending.isEmpty()) return false
        pending.forEach {
            if (it.status != MangaDownload.State.QUEUE) it.status = MangaDownload.State.QUEUE
            it.transfer.phase(TransferPhase.QUEUED)
        }

        isPaused = false

        launchDownloaderJob()

        return pending.isNotEmpty()
    }

    /**
     * Stops the downloader.
     */
    fun stop(reason: String? = null) {
        cancelDownloaderJob()
        queueState.value
            .filter { it.status == MangaDownload.State.DOWNLOADING }
            .forEach { it.status = MangaDownload.State.ERROR }

        if (reason != null) {
            notifier.onWarning(reason)
            return
        }

        if (isPaused && queueState.value.isNotEmpty()) {
            notifier.onPaused()
        } else {
            notifier.onComplete()
        }

        isPaused = false

        MangaDownloadJob.stop(context)
    }

    /**
     * Pauses the downloader
     */
    fun pause() {
        cancelDownloaderJob()
        queueState.value
            .filter { it.status == MangaDownload.State.DOWNLOADING }
            .forEach { it.status = MangaDownload.State.QUEUE }
        isPaused = true
    }

    fun retryErrors() {
        queueState.value.filter { it.status == MangaDownload.State.ERROR }.forEach {
            it.status =
                MangaDownload.State.QUEUE
        }
        queueRevision.value++
    }

    fun waitFor(reason: TransferWait) {
        val pending = queueState.value.filter { it.status.value <= MangaDownload.State.DOWNLOADING.value }
        if (pending.isEmpty()) return
        pause()
        pending.forEach { it.transfer.phase(TransferPhase.WAITING, reason) }
        notifier.onWaiting(reason)
    }

    /**
     * Removes everything from the queue.
     */
    fun clearQueue() {
        cancelDownloaderJob()

        internalClearQueue()
        notifier.dismissProgress()
    }

    /**
     * Prepares the subscriptions to start downloading.
     */
    private fun launchDownloaderJob() {
        if (isRunning) return

        downloaderJob = scope.launch {
            val activeDownloadsFlow = queueState.combine(queueRevision) { queue, _ -> queue }.transformLatest { queue ->
                while (true) {
                    val activeDownloads = selectDownloads(
                        queue.filter { it.status.value <= MangaDownload.State.DOWNLOADING.value },
                        downloadPreferences.numberOfDownloads().get(),
                        { it.source.id },
                        { it.status == MangaDownload.State.DOWNLOADING },
                    )
                    emit(activeDownloads)

                    if (activeDownloads.isEmpty()) break
                    // Suspend until a download enters the ERROR state
                    val activeDownloadsErroredFlow =
                        combine(activeDownloads.map(MangaDownload::statusFlow)) { states ->
                            states.contains(MangaDownload.State.ERROR)
                        }.filter { it }
                    activeDownloadsErroredFlow.first()
                }
            }.distinctUntilChanged()

            // Use supervisorScope to cancel child jobs when the downloader job is cancelled
            supervisorScope {
                val downloadJobs = mutableMapOf<Long, Job>()

                activeDownloadsFlow.collectLatest { activeDownloads ->
                    val activeIds = activeDownloads.map { it.chapter.id }.toSet()
                    val downloadJobsToStop = downloadJobs.filter { it.key !in activeIds }
                    downloadJobsToStop.forEach { (download, job) ->
                        job.cancel()
                        downloadJobs.remove(download)
                    }

                    val downloadsToStart = activeDownloads.filter { it.chapter.id !in downloadJobs }
                    downloadsToStart.forEach { download ->
                        downloadJobs[download.chapter.id] = launchDownloadJob(download)
                    }
                }
            }
        }
    }

    private fun CoroutineScope.launchDownloadJob(download: MangaDownload) = launchIO {
        download.status = MangaDownload.State.DOWNLOADING
        download.transfer.phase(TransferPhase.PREPARING)
        try {
            downloadChapter(download)

            // Remove successful download from queue
            if (download.status == MangaDownload.State.DOWNLOADED) {
                removeFromQueue(download)
            }
            if (areAllDownloadsFinished()) {
                stop()
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            currentCoroutineContext().ensureActive()
            logcat(LogPriority.ERROR, e)
            notifier.onError(e.message)
            stop()
        }
    }

    /**
     * Destroys the downloader subscriptions.
     */
    private fun cancelDownloaderJob() {
        stoppingJob = downloaderJob ?: stoppingJob
        downloaderJob?.cancel()
        downloaderJob = null
    }

    /**
     * Creates a download object for every chapter and adds them to the downloads queue.
     *
     * @param manga the manga of the chapters to download.
     * @param chapters the list of chapters to download.
     * @param autoStart whether to start the downloader after enqueing the chapters.
     */
    fun queueChapters(manga: Manga, chapters: List<Chapter>, autoStart: Boolean) {
        if (chapters.isEmpty()) return

        val source = sourceManager.get(manga.source) as? HttpSource ?: return
        val chaptersToQueue = chapters.asSequence()
            // Filter out those already downloaded.
            .filter { provider.findChapterDir(it.name, it.scanlator, manga.title, source) == null }
            // Add chapters to queue from the start.
            .sortedByDescending { it.sourceOrder }
            // Filter out those already enqueued.
            .filter { chapter -> queueState.value.none { it.chapter.id == chapter.id } }
            // Create a download for each one.
            .map { MangaDownload(source, manga, it) }
            .toList()

        if (chaptersToQueue.isNotEmpty()) {
            addAllToQueue(chaptersToQueue)

            // Start downloader if needed
            if (autoStart && !downloadPreferences.downloadManuallyPaused(false).get()) {
                MangaDownloadJob.start(context)
            }
        }
    }

    /**
     * Downloads a chapter.
     *
     * @param download the chapter to be downloaded.
     */
    private suspend fun downloadChapter(download: MangaDownload) {
        val mangaDir = provider.getMangaDir(download.manga.title, download.source)

        val availSpace = DiskUtil.getAvailableStorageSpace(mangaDir)
        if (availSpace != -1L && availSpace < MIN_DISK_SPACE) {
            download.status = MangaDownload.State.ERROR
            notifier.onError(
                context.stringResource(AYMR.strings.download_insufficient_space),
                download.chapter.name,
                download.manga.title,
                download.manga.id,
            )
            return
        }

        val chapterDirname = provider.getChapterDirName(download.chapter.name, download.chapter.scanlator)
        val tmpDir = mangaDir.createDirectory(chapterDirname + TMP_DIR_SUFFIX)!!

        try {
            // If the page list already exists, start from the file
            val pageList = download.pages ?: run {
                // Otherwise, pull page list from network and add them to download object
                val pages = TransferCoordinator.transfer(download.source.id) {
                    download.source.getPageList(download.chapter.toSChapter())
                }

                if (pages.isEmpty()) {
                    throw Exception(context.stringResource(MR.strings.page_list_empty_error))
                }
                // Don't trust index from source
                val reIndexedPages = pages.mapIndexed { index, page ->
                    Page(
                        index,
                        page.url,
                        page.imageUrl,
                        page.uri,
                    )
                }
                download.pages = reIndexedPages
                reIndexedPages
            }

            val dataSaver = if (sourcePreferences.dataSaverDownloader().get()) {
                DataSaver(download.source, sourcePreferences)
            } else {
                DataSaver.NoOp
            }

            // Delete all temporary (unfinished) files
            val existing = tmpDir.listFiles().orEmpty()
            existing.filter { it.extension == "tmp" }
                .forEach { it.delete() }
            val images = existing.filter { it.extension != "tmp" && it.length() > 0 }
                .groupBy { it.name.orEmpty().substringBefore('.').substringBefore("__") }
                .mapValues { (_, files) -> files.minBy { it.name.orEmpty() } }

            download.status = MangaDownload.State.DOWNLOADING

            // Start downloading images, consider we can have downloaded images already
            // The shared source budget grows only when measured throughput benefits.
            pageList.asFlow()
                .flatMapMerge(concurrency = 4) { page ->
                    flow {
                        // Fetch image URL if necessary
                        if (page.imageUrl.isNullOrEmpty()) {
                            page.status = Page.State.LOAD_PAGE
                            try {
                                page.imageUrl =
                                    TransferCoordinator.transfer(download.source.id) {
                                        download.source.getImageUrl(page)
                                    }
                            } catch (e: Throwable) {
                                if (e is CancellationException) throw e
                                currentCoroutineContext().ensureActive()
                                page.status = Page.State.ERROR
                            }
                        }

                        withIOContext { getOrDownloadImage(page, download, tmpDir, dataSaver, images) }
                        emit(page)
                    }.flowOn(Dispatchers.IO)
                }
                .collect {
                    // Do when page is downloaded.
                    notifier.onProgressChange(download)
                }

            // Do after download completes
            if (!isDownloadSuccessful(download, tmpDir)) {
                download.status = MangaDownload.State.ERROR
                return
            }

            download.transfer.phase(TransferPhase.SAVING)
            TransferCoordinator.finalization.withPermit {
                createComicInfoFile(
                    tmpDir,
                    download.manga,
                    download.chapter,
                    download.source,
                )

                // Only rename the directory if it's downloaded
                if (downloadPreferences.saveChaptersAsCBZ().get()) {
                    archiveChapter(mangaDir, chapterDirname, tmpDir)
                } else {
                    if (!tmpDir.renameTo(chapterDirname)) throw IOException("Impossibile salvare il capitolo.")
                }
            }
            cache.addChapter(chapterDirname, mangaDir, download.manga)

            DiskUtil.createNoMediaFile(tmpDir, context)

            download.status = MangaDownload.State.DOWNLOADED
            download.transfer.phase(TransferPhase.COMPLETE)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            currentCoroutineContext().ensureActive()
            // If the page list threw, it will resume here
            logcat(LogPriority.ERROR, error)
            download.status = MangaDownload.State.ERROR
            notifier.onError(error.message, download.chapter.name, download.manga.title, download.manga.id)
        }
    }

    /**
     * Gets the image from the filesystem if it exists or downloads it otherwise.
     *
     * @param page the page to download.
     * @param download the download of the page.
     * @param tmpDir the temporary directory of the download.
     */
    private suspend fun getOrDownloadImage(
        page: Page,
        download: MangaDownload,
        tmpDir: UniFile,
        dataSaver: DataSaver,
        images: Map<String, UniFile>,
    ) {
        // If the image URL is empty, do nothing
        if (page.imageUrl == null) {
            return
        }

        val digitCount = (download.pages?.size ?: 0).toString().length.coerceAtLeast(3)
        val filename = "%0${digitCount}d".format(Locale.ENGLISH, page.number)
        val imageFile = images[filename]

        try {
            // If the image is already downloaded, do nothing. Otherwise download from network
            val file = when {
                imageFile != null -> imageFile
                chapterCache.isImageInCache(page.imageUrl!!) -> copyImageFromCache(
                    chapterCache.getImageFile(page.imageUrl!!),
                    tmpDir,
                    filename,
                )
                else -> downloadImage(page, download, tmpDir, filename, dataSaver)
            }

            // When the page is ready, set page path, progress (just in case) and status
            page.uri = TransferCoordinator.imageProcessing.withPermit {
                splitTallImageIfNeeded(file, filename, tmpDir)
                if (file.exists()) {
                    file.uri
                } else {
                    tmpDir.findFile("${filename}__001.jpg")?.uri
                        ?: throw IOException("Pagina elaborata non disponibile.")
                }
            }
            page.progress = 100
            page.status = Page.State.READY
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            currentCoroutineContext().ensureActive()
            // Mark this page as error and allow to download the remaining
            page.progress = 0
            page.status = Page.State.ERROR
            notifier.onError(e.message, download.chapter.name, download.manga.title, download.manga.id)
        }
    }

    /**
     * Downloads the image from network to a file in tmpDir.
     *
     * @param page the page to download.
     * @param source the source of the page.
     * @param tmpDir the temporary directory of the download.
     * @param filename the filename of the image.
     */
    private suspend fun downloadImage(
        page: Page,
        download: MangaDownload,
        tmpDir: UniFile,
        filename: String,
        dataSaver: DataSaver,
    ): UniFile {
        page.status = Page.State.DOWNLOAD_IMAGE
        page.progress = 0
        return flow {
            val source = download.source
            val file =
                tmpDir.createFile("$filename.tmp") ?: throw VideoStorageException("Cartella download non accessibile.")
            try {
                MangaImageTransfer(source.id, throttler).download(
                    response = { source.getImage(page, dataSaver) },
                    output = { file.openOutputStream() },
                    availableBytes = { DiskUtil.getAvailableStorageSpace(tmpDir) },
                    bytesReceived = { download.transfer.addBytes(it) },
                    finalized = { response ->
                        val extension = getImageExtension(response, file)
                        if (!file.renameTo(
                                "$filename.$extension",
                            )
                        ) {
                            throw VideoStorageException("Impossibile salvare l'immagine.")
                        }
                    },
                )
            } catch (e: Exception) {
                file.delete()
                throw e
            }
            emit(file)
        }
            // Retry 3 times, waiting 2, 4 and 8 seconds between attempts.
            .retryWhen { error, attempt ->
                val recoverable =
                    (error is IOException && error !is VideoStorageException) ||
                        (error is HttpException && (error.code in setOf(408, 429) || error.code in 500..599))
                if (error !is CancellationException && recoverable && attempt < 3) {
                    if (error is HttpException) {
                        TransferCoordinator.backoff(
                            download.source.id,
                            error.retryAfterMillis ?: 30_000,
                        )
                    }
                    delay((error as? HttpException)?.retryAfterMillis ?: ((2L shl attempt.toInt()) * 1000))
                    true
                } else {
                    false
                }
            }
            .flowOn(Dispatchers.IO)
            .first()
    }

    /**
     * Copies the image from cache to file in tmpDir.
     *
     * @param cacheFile the file from cache.
     * @param tmpDir the temporary directory of the download.
     * @param filename the filename of the image.
     */
    private fun copyImageFromCache(cacheFile: File, tmpDir: UniFile, filename: String): UniFile {
        val tmpFile = tmpDir.createFile("$filename.tmp")!!
        cacheFile.inputStream().use { input ->
            tmpFile.openOutputStream().use { output ->
                input.copyTo(output, 64 * 1024)
            }
        }
        val extension =
            cacheFile.inputStream().use { ImageUtil.findImageType(it) }
                ?: throw IOException("Immagine in cache non valida.")
        if (!tmpFile.renameTo("$filename.${extension.extension}")) throw IOException("Impossibile salvare l'immagine.")
        cacheFile.delete()
        return tmpFile
    }

    /**
     * Returns the extension of the downloaded image from the network response, or if it's null,
     * analyze the file. If everything fails, assume it's a jpg.
     *
     * @param response the network response of the image.
     * @param file the file where the image is already downloaded.
     */
    private fun getImageExtension(response: Response, file: UniFile): String {
        // A successful HTTP status or a claimed image MIME alone cannot validate an image.
        val detected = file.openInputStream().use { ImageUtil.findImageType(it) }
            ?: throw IOException("La risposta non contiene un'immagine valida.")
        return detected.extension
    }

    private fun splitTallImageIfNeeded(imageFile: UniFile, filenamePrefix: String, tmpDir: UniFile) {
        if (!downloadPreferences.splitTallImages().get()) return

        try {
            // If the original page was previously split, then skip
            if (imageFile.name.orEmpty().startsWith("${filenamePrefix}__")) return

            ImageUtil.splitTallImage(tmpDir, imageFile, filenamePrefix)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to split downloaded image" }
        }
    }

    /**
     * Checks if the download was successful.
     *
     * @param download the download to check.
     * @param tmpDir the directory where the download is currently stored.
     */
    private fun isDownloadSuccessful(
        download: MangaDownload,
        tmpDir: UniFile,
    ): Boolean {
        // Page list hasn't been initialized
        val downloadPageCount = download.pages?.size ?: return false
        // Ensure that all pages have been downloaded
        if (download.downloadedImages != downloadPageCount) {
            return false
        }
        // Ensure that the chapter folder has all the pages
        val downloadedImagesCount = tmpDir.listFiles().orEmpty().count {
            val fileName = it.name.orEmpty()
            when {
                fileName in listOf(COMIC_INFO_FILE, NOMEDIA_FILE) -> false
                fileName.endsWith(".tmp") -> false
                // Only count the first split page and not the others
                fileName.contains("__") && !fileName.endsWith("__001.jpg") -> false
                else -> true
            }
        }
        return downloadedImagesCount == downloadPageCount
    }

    /**
     * Archive the chapter pages as a CBZ.
     */
    private fun archiveChapter(
        mangaDir: UniFile,
        dirname: String,
        tmpDir: UniFile,
    ) {
        val zip = mangaDir.createFile("$dirname.cbz$TMP_DIR_SUFFIX")!!
        try {
            ZipWriter(context, zip).use { writer ->
                tmpDir.listFiles()?.sortedBy { it.name }?.forEach { file -> writer.write(file) }
            }
            if (zip.length() <= 0 || !zip.renameTo("$dirname.cbz")) throw IOException("Impossibile salvare il CBZ.")
            tmpDir.delete()
        } catch (error: Exception) {
            zip.delete()
            throw error
        }
    }

    /**
     * Creates a ComicInfo.xml file inside the given directory.
     */
    private suspend fun createComicInfoFile(
        dir: UniFile,
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
    ) {
        val categories = getCategories.await(manga.id).map { it.name.trim() }.takeUnless { it.isEmpty() }
        val urls = getMangaTracks.await(manga.id)
            .mapNotNull { track ->
                track.remoteUrl.takeUnless { url -> url.isBlank() }?.trim()
            }
            .plus(source.getChapterUrl(chapter.toSChapter()).trim())
            .distinct()

        val comicInfo = getComicInfo(
            manga,
            chapter,
            urls,
            categories,
            source.name,
        )

        // Remove the old file
        dir.findFile(COMIC_INFO_FILE)?.delete()
        dir.createFile(COMIC_INFO_FILE)!!.openOutputStream().use {
            val comicInfoString = xml.encodeToString(ComicInfo.serializer(), comicInfo)
            it.write(comicInfoString.toByteArray())
        }
    }

    /**
     * Returns true if all the queued downloads are in DOWNLOADED or ERROR state.
     */
    private fun areAllDownloadsFinished(): Boolean {
        return queueState.value.none { it.status.value <= MangaDownload.State.DOWNLOADING.value }
    }

    private fun addAllToQueue(downloads: List<MangaDownload>) {
        _queueState.update {
            downloads.forEach { download ->
                download.status = MangaDownload.State.QUEUE
                if (downloadPreferences.downloadManuallyPaused(false).get()) {
                    download.transfer.phase(TransferPhase.WAITING, TransferWait.MANUAL)
                } else {
                    download.transfer.phase(TransferPhase.QUEUED)
                }
            }
            store.addAll(downloads)
            it + downloads
        }
    }

    private fun removeFromQueue(download: MangaDownload) {
        _queueState.update {
            store.remove(download)
            if (download.status == MangaDownload.State.DOWNLOADING || download.status == MangaDownload.State.QUEUE) {
                download.status = MangaDownload.State.NOT_DOWNLOADED
            }
            it - download
        }
    }

    private inline fun removeFromQueueIf(predicate: (MangaDownload) -> Boolean) {
        _queueState.update { queue ->
            val downloads = queue.filter { predicate(it) }
            store.removeAll(downloads)
            downloads.forEach { download ->
                if (download.status == MangaDownload.State.DOWNLOADING ||
                    download.status == MangaDownload.State.QUEUE
                ) {
                    download.status = MangaDownload.State.NOT_DOWNLOADED
                }
            }
            queue - downloads
        }
    }

    fun removeFromQueue(chapters: List<Chapter>) {
        val chapterIds = chapters.map { it.id }
        removeFromQueueIf { it.chapter.id in chapterIds }
    }

    fun removeFromQueue(manga: Manga) {
        removeFromQueueIf { it.manga.id == manga.id }
    }

    private fun internalClearQueue() {
        _queueState.update {
            it.forEach { download ->
                if (download.status == MangaDownload.State.DOWNLOADING ||
                    download.status == MangaDownload.State.QUEUE
                ) {
                    download.status = MangaDownload.State.NOT_DOWNLOADED
                }
            }
            store.clear()
            emptyList()
        }
    }

    fun updateQueue(downloads: List<MangaDownload>) {
        val wasRunning = isRunning

        if (downloads.isEmpty()) {
            clearQueue()
            stop()
            return
        }

        pause()
        internalClearQueue()
        addAllToQueue(downloads)

        if (wasRunning) {
            start()
        }
    }

    companion object {
        const val TMP_DIR_SUFFIX = "_tmp"
        const val WARNING_NOTIF_TIMEOUT_MS = 30_000L
        const val CHAPTERS_PER_SOURCE_QUEUE_WARNING_THRESHOLD = 15
    }
}

// Arbitrary minimum required space to start a download: 200 MB
private const val MIN_DISK_SPACE = 200L * 1024 * 1024
