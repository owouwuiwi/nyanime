package eu.kanade.tachiyomi.data.download.anime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.net.toUri
import aniyomi.core.common.torrent.TorrentPreferences
import aniyomi.core.common.torrent.TorrentServerApi
import aniyomi.core.common.torrent.TorrentServerUtils
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.Level
import com.arthenica.ffmpegkit.LogCallback
import com.arthenica.ffmpegkit.LogRedirectionStrategy
import com.arthenica.ffmpegkit.MediaInformationJsonParser
import com.arthenica.ffmpegkit.StatisticsCallback
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.animesource.model.HttpServer
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.data.download.anime.model.AnimeDownload
import eu.kanade.tachiyomi.data.download.anime.ultra.UltraDownloadWorker
import eu.kanade.tachiyomi.data.download.transfer.DownloadBandwidth
import eu.kanade.tachiyomi.data.download.transfer.TransferCoordinator
import eu.kanade.tachiyomi.data.download.transfer.TransferPhase
import eu.kanade.tachiyomi.data.download.transfer.TransferWait
import eu.kanade.tachiyomi.data.download.transfer.selectDownloads
import eu.kanade.tachiyomi.data.torrent.service.TorrentServerService
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.player.loader.EpisodeLoader
import eu.kanade.tachiyomi.ui.player.loader.HosterLoader
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.system.copyToClipboard
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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.storage.extension
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.items.episode.model.Episode
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import tachiyomi.i18n.aniyomi.AYMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * This class is the one in charge of downloading episodes.
 *
 * Its queue contains the list of episodes to download. In order to download them, the downloader
 * subscription must be running and the list of episodes must be sent to them by [downloaderJob].
 *
 * The queue manipulation must be done in one thread (currently the main thread) to avoid unexpected
 * behavior, but it's safe to read it from multiple threads.
 */
class AnimeDownloader(
    private val context: Context,
    private val provider: AnimeDownloadProvider,
    private val cache: AnimeDownloadCache,
    private val sourceManager: AnimeSourceManager = Injekt.get(),
    private val torrentServerApi: TorrentServerApi = Injekt.get(),
    private val torrentServerUtils: TorrentServerUtils = Injekt.get(),
    private val torrentPreferences: TorrentPreferences = Injekt.get(),
) {
    /**
     * Store for persisting downloads across restarts.
     */
    private val store = AnimeDownloadStore(context)

    /**
     * Queue where active downloads are kept.
     */
    private val _queueState = MutableStateFlow<List<AnimeDownload>>(emptyList())
    val queueState = _queueState.asStateFlow()

    /**
     * Notifier for the downloader state and progress.
     */
    private val notifier by lazy { AnimeDownloadNotifier(context) }

    /**
     * Coroutine scope used for download job scheduling
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Job object for download queue management
     */
    private var downloaderJob: Job? = null
    private var stoppingJob: Job? = null
    private val queueRevision = MutableStateFlow(0L)
    val ready = CompletableDeferred<Unit>()
    suspend fun awaitStopped() {
        stoppingJob?.join()
    }

    /**
     * Preference for user's choice of external downloader
     */
    private val preferences: DownloadPreferences by injectLazy()

    /**
     * Whether the downloader is running.
     */
    val isRunning: Boolean
        get() = downloaderJob?.isActive ?: false

    init {
        scope.launch {
            try {
                sourceManager.sources.collect {
                    val candidates = store.restore()
                    val restored = withUIContext {
                        candidates.filter { saved ->
                            store.isQueued(saved) &&
                                queueState.value.none { it.episode.id == saved.episode.id }
                        }
                            .also { addAllToQueue(it) }
                    }
                    ready.complete(Unit)
                    if (restored.isNotEmpty() && !preferences.downloadManuallyPaused(true).get()) {
                        AnimeDownloadJob.start(context, userInitiated = false)
                    }
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                logcat(LogPriority.ERROR, error) { "Could not restore the anime download queue" }
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

        val pending = queueState.value.filter { it.status.value <= AnimeDownload.State.DOWNLOADING.value }
        if (pending.isEmpty()) return false
        pending.forEach {
            if (it.status != AnimeDownload.State.QUEUE) it.status = AnimeDownload.State.QUEUE
            it.transfer.phase(TransferPhase.QUEUED)
        }

        launchDownloaderJob()

        return pending.isNotEmpty()
    }

    /**
     * Stops the downloader.
     */
    fun stop(reason: String? = null) {
        cancelDownloaderJob()
        queueState.value
            .filter { it.status == AnimeDownload.State.DOWNLOADING }
            .forEach { it.status = AnimeDownload.State.ERROR }

        if (reason != null) {
            notifier.onWarning(reason)
            return
        }

        if (queueState.value.isNotEmpty()) {
            notifier.onPaused()
        } else {
            notifier.onComplete()
        }

        AnimeDownloadJob.stop(context)
    }

    /**
     * Pauses the downloader
     */
    fun pause() {
        cancelDownloaderJob()
        queueState.value
            .filter { it.status == AnimeDownload.State.DOWNLOADING }
            .forEach { it.status = AnimeDownload.State.QUEUE }
    }

    fun retryErrors() {
        queueState.value.filter { it.status == AnimeDownload.State.ERROR }.forEach {
            it.status =
                AnimeDownload.State.QUEUE
        }
        queueRevision.value++
    }

    fun waitFor(reason: TransferWait) {
        val pending = queueState.value.filter { it.status.value <= AnimeDownload.State.DOWNLOADING.value }
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
     * Prepares the jobs to start downloading.
     */
    private fun launchDownloaderJob() {
        if (isRunning) return

        downloaderJob = scope.launch {
            val activeDownloadsFlow = queueState.combine(queueRevision) { queue, _ -> queue }.transformLatest { queue ->
                while (true) {
                    val activeDownloads = selectDownloads(
                        queue.filter { it.status.value <= AnimeDownload.State.DOWNLOADING.value },
                        preferences.numberOfDownloads().get(),
                        { it.source.id },
                        { it.status == AnimeDownload.State.DOWNLOADING },
                    )
                    emit(activeDownloads)

                    if (activeDownloads.isEmpty()) break

                    // Suspend until a download enters the ERROR state
                    val activeDownloadsErroredFlow =
                        combine(activeDownloads.map(AnimeDownload::statusFlow)) { states ->
                            states.contains(AnimeDownload.State.ERROR)
                        }.filter { it }
                    activeDownloadsErroredFlow.first()
                }

                if (areAllAnimeDownloadsFinished()) stop()
            }.distinctUntilChanged()

            // Use supervisorScope to cancel child jobs when the downloader job is cancelled
            supervisorScope {
                val downloadJobs = mutableMapOf<Long, Job>()

                activeDownloadsFlow.collectLatest { activeDownloads ->
                    val activeIds = activeDownloads.map { it.episode.id }.toSet()
                    val downloadJobsToStop = downloadJobs.filter { it.key !in activeIds }
                    downloadJobsToStop.forEach { (download, job) ->
                        job.cancel()
                        downloadJobs.remove(download)
                    }

                    val downloadsToStart = activeDownloads.filter { it.episode.id !in downloadJobs }
                    downloadsToStart.forEach { download ->
                        downloadJobs[download.episode.id] = launchDownloadJob(download)
                    }
                }
            }
        }
    }

    /**
     * Launch the job responsible for download a single video
     */
    private fun CoroutineScope.launchDownloadJob(download: AnimeDownload) = launchIO {
        download.status = AnimeDownload.State.DOWNLOADING
        download.transfer.phase(TransferPhase.PREPARING)
        // This try-catch manages the job cancellation
        try {
            downloadEpisode(download)

            // Remove successful download from queue
            if (download.status == AnimeDownload.State.DOWNLOADED) {
                removeFromQueue(download)
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
     * Creates a download object for every episode and adds them to the downloads queue.
     *
     * @param anime the anime of the episodes to download.
     * @param episodes the list of episodes to download.
     * @param autoStart whether to start the downloader after enqueing the episodes.
     */
    fun queueEpisodes(
        anime: Anime,
        episodes: List<Episode>,
        autoStart: Boolean,
        changeDownloader: Boolean = false,
        video: Video? = null,
    ) {
        if (episodes.isEmpty()) return

        val source = sourceManager.get(anime.source) as? AnimeHttpSource ?: return

        // Inspect the title directory once for the entire batch, keeping all existing filename variants.
        val files = provider.findAnimeDir(anime.title, source)?.listFiles()
            .orEmpty().mapNotNull { it.name }.toHashSet()
        val queuedIds = queueState.value.map { it.episode.id }.toHashSet()
        val episodesToQueue = episodes.asSequence()
            // Filter out those already downloaded.
            .filter { provider.getValidEpisodeDirNames(it.name, it.scanlator).none(files::contains) }
            // Add episodes to queue from the start.
            .sortedByDescending { it.sourceOrder }
            // Filter out those already enqueued.
            .filter { it.id !in queuedIds }
            .distinctBy { it.id }
            // Create a download for each one.
            .map { AnimeDownload(source, anime, it, changeDownloader, video) }
            .toList()

        if (episodesToQueue.isNotEmpty()) {
            addAllToQueue(episodesToQueue)

            // Start downloader if needed
            if (autoStart && !preferences.downloadManuallyPaused(true).get()) {
                AnimeDownloadJob.start(context)
            }
        }
    }

    /**
     * Download the video associated with download object
     *
     * @param download the episode to be downloaded.
     */
    private suspend fun downloadEpisode(download: AnimeDownload) {
        val animeDir = provider.getAnimeDir(download.anime.title, download.source)

        val availSpace = DiskUtil.getAvailableStorageSpace(animeDir)
        if (availSpace != -1L && availSpace < MIN_DISK_SPACE) {
            download.status = AnimeDownload.State.ERROR
            notifier.onError(
                context.stringResource(AYMR.strings.download_insufficient_space),
                download.episode.name,
                download.anime.title,
                download.anime.id,
            )
            return
        }

        val episodeDirname = provider.getEpisodeDirName(download.episode.name, download.episode.scanlator)
        val tmpDir = animeDir.createDirectory(episodeDirname + TMP_DIR_SUFFIX)!!

        try {
            if (download.video == null) {
                // Pull video from network and add them to download object
                val hosters = TransferCoordinator.transfer(download.source.id) {
                    EpisodeLoader.getHosters(download.episode, download.anime, download.source)
                }
                if (hosters.isEmpty()) {
                    throw Exception(context.stringResource(AYMR.strings.video_list_empty_error))
                }
                val bestVideo =
                    TransferCoordinator.transfer(download.source.id) {
                        HosterLoader.getBestVideo(download.source, hosters)
                    }
                        ?: throw Exception(context.stringResource(AYMR.strings.video_list_empty_error))
                download.video = bestVideo
            }

            withIOContext {
                getOrDownloadVideoFile(download, tmpDir)
            }

            if (!isDownloadSuccessful(download, tmpDir)) {
                download.status = AnimeDownload.State.ERROR
                return
            }

            val filename = DiskUtil.buildValidFilename("${download.anime.title} - ${download.episode.name}")
            tmpDir.findFile("${filename}_tmp.mkv")?.delete()
            if (!tmpDir.renameTo(
                    episodeDirname,
                )
            ) {
                throw VideoStorageException("Impossibile salvare la cartella del video.")
            }

            cache.addEpisode(episodeDirname, animeDir, download.anime)

            DiskUtil.createNoMediaFile(tmpDir, context)

            download.status = AnimeDownload.State.DOWNLOADED
            download.transfer.phase(TransferPhase.COMPLETE)
            if (preferences.ultraAfterDownload().get() &&
                preferences.useExternalDownloader().get() == download.changeDownloader
            ) {
                // Scheduling Ultra must never turn a successful download into a download error.
                runCatching {
                    animeDir.findFile(episodeDirname)?.let { folder ->
                        UltraDownloadWorker.enqueue(
                            context,
                            folder.uri,
                            "${download.anime.title} · ${download.episode.name}",
                            download.anime.id,
                            download.episode.id,
                        )
                    }
                }.onFailure { logcat(LogPriority.ERROR, it) }
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            currentCoroutineContext().ensureActive()
            // If the video threw, it will resume here
            logcat(LogPriority.ERROR, error)
            download.status = AnimeDownload.State.ERROR
            notifier.onError(
                error.message,
                download.episode.name,
                download.anime.title,
                download.anime.id,
            )
        }
    }

    /**
     * Gets the video file if already downloaded, otherwise downloads it
     *
     * @param download the download of the video.
     * @param tmpDir the temporary directory of the download.
     */
    private suspend fun getOrDownloadVideoFile(
        download: AnimeDownload,
        tmpDir: UniFile,
    ) {
        val video = download.video!!

        video.status = Video.State.LOAD_VIDEO

        var progressJob: Job? = null
        var httpServer: HttpServer? = null

        // Get filename from download info
        val filename = DiskUtil.buildValidFilename(download.episode.name)

        // Try to find the video file
        val videoFile = tmpDir.listFiles()?.firstOrNull {
            it.name?.substringBeforeLast('.') == filename && isFinishedVideo(it)
        }

        try {
            // If the video is already downloaded, do nothing. Otherwise download from network
            val file = when {
                videoFile != null -> videoFile
                else -> {
                    notifier.onProgressChange(download)

                    download.status = AnimeDownload.State.DOWNLOADING
                    download.progress = 0

                    // If videoFile is not existing then download it
                    if (preferences.useExternalDownloader().get() == download.changeDownloader) {
                        progressJob = scope.launch {
                            while (download.status == AnimeDownload.State.DOWNLOADING) {
                                delay(1000)
                                notifier.onProgressChange(download)
                            }
                        }

                        // Start and set http server if needed
                        if (video.usesHttpServer()) {
                            httpServer = download.source.createHttpServer()
                            httpServer?.start()
                            download.video = download.video?.copyHttpServer(httpServer?.listeningPort ?: 0)
                        }

                        downloadVideo(download, tmpDir, filename)
                    } else {
                        if (download.video!!.usesHttpServer()) {
                            val (success, port) = MainActivity.startHttpServerService(context, download.source.id)
                            if (!success) throw Exception("Failed to start server")
                            download.video = download.video!!.copyHttpServer(port)
                        }

                        val betterFileName = DiskUtil.buildValidFilename(
                            "${download.anime.title} - ${download.episode.name}",
                        )
                        downloadVideoExternal(
                            video = download.video!!,
                            source = download.source,
                            tmpDir = tmpDir,
                            filename = betterFileName,
                        )
                    }
                }
            }

            download.video?.videoUrl = file.uri.toString()
            download.progress = 100
            download.video?.status = Video.State.READY
            httpServer?.stop()
            progressJob?.cancel()
        } catch (e: Exception) {
            httpServer?.stop()
            progressJob?.cancel()
            if (e is CancellationException) throw e
            currentCoroutineContext().ensureActive()
            download.video?.status = Video.State.ERROR
            notifier.onError(e.message, download.episode.name, download.anime.title, download.anime.id)
        }
    }

    /**
     * Define a retry routine in order to accommodate some errors that can be raised
     *
     * @param download the download reference
     * @param tmpDir the directory where placing the file
     * @param filename the name to give to download file
     */
    private suspend fun downloadVideo(
        download: AnimeDownload,
        tmpDir: UniFile,
        filename: String,
    ): UniFile {
        var refreshedLink = false
        var directDisabled = false
        for (attempt in 0..3) {
            try {
                val video = download.video!!
                val client = download.source.client.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS)
                    .readTimeout(60, TimeUnit.SECONDS).build()
                val headers = video.headers ?: download.source.headers
                val compatible = video.ffmpegStreamArgs.isEmpty() &&
                    video.ffmpegVideoArgs.isEmpty() &&
                    !video.usesHttpServer() &&
                    video.videoUrl.toHttpUrlOrNull() != null
                var transport = VideoTransferFormat.fromUrl(video.videoUrl)
                if (compatible && transport == VideoTransferFormat.OTHER && !directDisabled) {
                    transport = probeVideoTransferFormat(client, download.source.id, video.videoUrl, headers)
                }
                val format = transport.extension
                val direct = !directDisabled &&
                    format != null &&
                    compatible &&
                    video.subtitleTracks.isEmpty() &&
                    video.audioTracks.isEmpty()
                if (direct) {
                    val partName = "$filename.http-part.tmp"
                    val stateName = "$filename.http-state.tmp"
                    val part = tmpDir.findFile(partName) ?: tmpDir.createFile(partName)!!
                    val state = tmpDir.findFile(stateName) ?: tmpDir.createFile(stateName)!!
                    try {
                        val partial = UriPartialVideoStore(context, part, state)
                        val progress = { bytes: Long, total: Long -> download.update(bytes, total, false) }
                        if (!ParallelVideoTransfer(client, partial, download.source.id, {
                                DiskUtil.getAvailableStorageSpace(tmpDir)
                            }, throttler = DownloadBandwidth.throttler)
                                .tryDownload(video.videoUrl, headers, progress)
                        ) {
                            TransferCoordinator.transfer(download.source.id) {
                                ResumableVideoTransfer(client, partial, {
                                    DiskUtil.getAvailableStorageSpace(tmpDir)
                                }, DownloadBandwidth.throttler)
                                    .download(video.videoUrl, headers, progress)
                            }
                        }
                        download.transfer.phase(TransferPhase.SAVING)
                        if (!part.renameTo(
                                "$filename.$format",
                            )
                        ) {
                            throw VideoStorageException("Impossibile finalizzare il video scaricato.")
                        }
                        state.delete()
                        return part
                    } catch (e: UnsupportedDirectVideo) {
                        directDisabled = true
                        part.delete()
                        state.delete()
                        // Preserve the existing FFmpeg route for incompatible servers/containers.
                    }
                }
                tmpDir.findFile("$filename.http-part.tmp")?.delete()
                tmpDir.findFile("$filename.http-state.tmp")?.delete()
                tmpDir.findFile("$filename.tmp")?.delete()
                val file = tmpDir.createFile("$filename.tmp")!!
                try {
                    if (torrentPreferences.torrServerEnable().get() && isTorrent(download.video)) {
                        torrentDownload(download, tmpDir, file, filename)
                    } else {
                        ffmpegDownload(download, tmpDir, file, filename, transport == VideoTransferFormat.HLS)
                    }
                    return file
                } catch (e: Exception) {
                    file.delete()
                    throw e
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                currentCoroutineContext().ensureActive()
                if (e is VideoStorageException || attempt == 3) throw e
                if (e is VideoHttpException) {
                    if (e.status in setOf(401, 403, 404, 410) && !refreshedLink) {
                        refreshedLink = true
                        download.video = TransferCoordinator.transfer(download.source.id) {
                            HosterLoader.getBestVideo(
                                download.source,
                                EpisodeLoader.getHosters(download.episode, download.anime, download.source),
                            )
                        } ?: throw IOException("Video non disponibile. Aggiorna gli episodi e riprova.")
                    } else if (e.status !in setOf(408, 416, 429) && e.status !in 500..599) {
                        throw IOException("Video non disponibile (HTTP ${e.status}). Aggiorna la fonte e riprova.", e)
                    }
                }
                if (e is VideoHttpException && (e.status == 429 || e.status in 500..599)) {
                    TransferCoordinator.backoff(download.source.id, e.retryAfter ?: 30_000)
                }
                delay((e as? VideoHttpException)?.retryAfter ?: ((2L shl attempt) * 1000))
            }
        }
        error("Download attempts exhausted")
    }

    private fun isTorrent(video: Video?): Boolean {
        val url = video?.videoUrl ?: return false
        return url.startsWith("magnet") || url.endsWith(".torrent") || url.startsWith(torrentServerApi.hostUrl)
    }

    private suspend fun torrentDownload(
        download: AnimeDownload,
        tmpDir: UniFile,
        videoFile: UniFile,
        filename: String,
    ) {
        val video = download.video!!
        TorrentServerService.start()
        if (video.videoUrl.startsWith(torrentServerApi.hostUrl)) {
            val hash = video.videoUrl.substringAfter("link=").substringBefore("&")
            val index = video.videoUrl.substringAfter("index=").substringBefore("&").toInt()
            val magnet = "magnet:?xt=urn:btih:$hash&index=$index"
            video.videoUrl = magnet
        }
        val currentTorrent = torrentServerApi.addTorrent(video.videoUrl, video.videoTitle, "", "", false)
        var index = 0
        if (video.videoUrl.contains("index=")) {
            index = try {
                video.videoUrl.substringAfter("index=")
                    .substringBefore("&").toInt()
            } catch (_: Exception) {
                0
            }
        }
        val torrentUrl = torrentServerUtils.getTorrentPlayLink(currentTorrent, index)
        video.videoUrl = torrentUrl
        ffmpegDownload(download, tmpDir, videoFile, filename)
    }

    // ffmpeg is always on safe mode
    private suspend fun ffmpegDownload(
        download: AnimeDownload,
        tmpDir: UniFile,
        videoFile: UniFile,
        filename: String,
        hls: Boolean = VideoTransferFormat.fromUrl(download.video!!.videoUrl) == VideoTransferFormat.HLS,
    ) {
        val video = download.video!!

        val headers = video.headers ?: download.source.headers
        val headerOptions = headers.joinToString("", "-headers '", "'") {
            "${it.first}: ${it.second}\r\n"
        }

        FFmpegKitConfig.setLogRedirectionStrategy(LogRedirectionStrategy.ALWAYS_PRINT_LOGS)
        var prepared: PreparedHls? = null
        if (hls &&
            video.ffmpegStreamArgs.isEmpty() &&
            video.ffmpegVideoArgs.isEmpty() &&
            !video.usesHttpServer()
        ) {
            try {
                prepared = HlsVideoTransfer(
                    download.source.client.newBuilder().callTimeout(
                        0,
                        TimeUnit.MILLISECONDS,
                    ).readTimeout(60, TimeUnit.SECONDS).build(),
                    java.io.File(context.cacheDir, "video-transfers"),
                    download.source.id,
                    DownloadBandwidth.throttler,
                ).prepare(
                    video.videoUrl,
                    headers,
                    video.audioTracks.map {
                        it.url to it.lang
                    },
                    video.subtitleTracks.map {
                        it.url to
                            it.lang
                    },
                    video.resolution,
                    video.bitrate,
                ) { bytes, finished, total ->
                    download.transfer.progress(bytes, -1)
                    download.progress = (finished * 90 / total.coerceAtLeast(1)).coerceIn(0, 90)
                }
            } catch (_: UnsupportedHls) {
                // Unsupported formats retain the extension's existing FFmpeg options.
            }
        }
        val ffmpegOptions: (String) -> Array<String> = if (prepared != null) {
            val local = prepared
            val inspected = withTimeoutOrNull(15_000) {
                suspendCancellableCoroutine { continuation ->
                    val session = FFprobeKit.executeWithArgumentsAsync(local.inspectionArguments()) {
                        if (continuation.isActive) continuation.resume(it)
                    }
                    continuation.invokeOnCancellation { session.cancel() }
                }
            }
            currentCoroutineContext().ensureActive()
            val streams = inspected?.takeIf { it.returnCode.isValueSuccess }?.output
                ?.let { MediaInformationJsonParser.from(it)?.streams }.orEmpty()
            val bytes = prepared.directory.listFiles().orEmpty().sumOf { it.length() }
            val free = DiskUtil.getAvailableStorageSpace(tmpDir)
            if (free >= 0 && bytes > free - MIN_DISK_SPACE) {
                throw VideoStorageException("Spazio insufficiente per salvare il video.")
            }
            val arguments: (String) -> Array<String> = { output ->
                local.arguments(
                    output,
                    streams.count { it.type == "audio" },
                    streams.count {
                        it.type ==
                            "subtitle"
                    },
                )
            }
            arguments
        } else {
            { output -> getFFmpegOptions(video, headerOptions, output) }
        }
        val ffprobeCommand = { file: String, ffprobeHeaders: String? ->
            FFmpegKitConfig.parseArguments(
                "${ffprobeHeaders?.plus(" ") ?: ""}-v quiet -show_entries " +
                    "format=duration -of default=noprint_wrappers=1:nokey=1 \"$file\"",
            )
        }

        var duration = 0L

        var httpFailure: Int? = null
        val logCallback = LogCallback { log ->
            if (log.level <= Level.AV_LOG_WARNING) {
                log.message?.let {
                    Regex("HTTP (?:error |Error )?(401|403|404|410|429|5[0-9]{2})")
                        .find(it)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { status -> httpFailure = status }
                    logcat(LogPriority.ERROR) { it }
                }
            }
        }

        val statCallback = StatisticsCallback { s ->
            val outTime = (s.time / 1000.0).toLong()

            if (duration != 0L && outTime > 0) {
                download.progress =
                    if (prepared ==
                        null
                    ) {
                        (100 * outTime / duration).toInt().coerceIn(0, 99)
                    } else {
                        (90 + 10 * outTime / duration).toInt().coerceIn(90, 99)
                    }
            }
        }

        duration = prepared?.durationUs?.div(1_000_000) ?: withTimeoutOrNull(15_000) {
            TransferCoordinator.transfer(download.source.id) {
                getDuration(ffprobeCommand(video.videoUrl, headerOptions))?.toLong()
            }
        } ?: 0L
        download.transfer.phase(if (prepared != null) TransferPhase.SAVING else TransferPhase.TRANSFERRING)

        suspend fun remux() = suspendCancellableCoroutine { continuation ->
            val output = NativeVideoOutput(context, videoFile.uri)
            val session = try {
                FFmpegKit.executeWithArgumentsAsync(
                    output.arguments(ffmpegOptions(output.path)),
                    {
                        val closeFailure = runCatching { output.close() }.exceptionOrNull()
                        // A paused/cancelled task must not rename files after its queue job has ended.
                        if (!continuation.isActive) return@executeWithArgumentsAsync
                        if (closeFailure != null) {
                            continuation.resumeWithException(
                                VideoStorageException("Impossibile chiudere il video salvato."),
                            )
                            return@executeWithArgumentsAsync
                        }
                        if (it.returnCode.isValueSuccess) {
                            if (videoFile.length() <= 0 || !videoFile.renameTo("$filename.mkv")) {
                                continuation.resumeWithException(
                                    VideoStorageException("Impossibile finalizzare il video scaricato."),
                                )
                            } else {
                                continuation.resume(it)
                            }
                        } else {
                            continuation.resumeWithException(
                                httpFailure?.let(::VideoHttpException)
                                    ?: IOException("Download interrotto. Controlla la connessione e riprova."),
                            )
                        }
                    },
                    logCallback,
                    statCallback,
                )
            } catch (error: Throwable) {
                runCatching { output.close() }
                continuation.resumeWithException(error)
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation {
                session.cancel()
            }
        }
        if (prepared == null) {
            TransferCoordinator.transfer(download.source.id) { remux() }
        } else {
            TransferCoordinator.finalization.withPermit { remux() }
        }
        prepared?.directory?.deleteRecursively()
    }

    private fun getFFmpegOptions(video: Video, headerOptions: String, ffmpegFilename: String): Array<String> {
        fun formatInputs(tracks: List<Track>) = tracks.joinToString(" ", postfix = " ") {
            buildList {
                if (it.url.startsWith("http")) {
                    add(headerOptions)
                }
                add("-i")
                add("\"${it.url}\"")
            }.joinToString(" ")
        }

        fun formatMaps(tracks: List<Track>, type: String, offset: Int = 0) = tracks.indices.joinToString(" ") {
            "-map ${it + 1 + offset}:$type"
        }

        fun formatMetadata(tracks: List<Track>, type: String) = tracks.mapIndexed { i, track ->
            "-metadata:s:$type:$i \"title=${track.lang}\""
        }.joinToString(" ")

        val subtitleInputs = formatInputs(video.subtitleTracks)
        val subtitleMaps = formatMaps(video.subtitleTracks, "s")
        val subtitleMetadata = formatMetadata(video.subtitleTracks, "s")

        val audioInputs = formatInputs(video.audioTracks)
        val audioMaps = formatMaps(video.audioTracks, "a", video.subtitleTracks.size)
        val audioMetadata = formatMetadata(video.audioTracks, "a")

        val sourceStreamOptions = video.ffmpegStreamArgs.joinToString(" ") { (key, value) ->
            "-$key \"$value\""
        }
        val sourceVideoOptions = video.ffmpegVideoArgs.joinToString(" ") { (key, value) ->
            "-$key \"$value\""
        }

        val videoInput = buildList {
            if (video.videoUrl.startsWith("http")) {
                add(headerOptions)
            }
            add(sourceStreamOptions)
            add("-i")
            add("\"${video.videoUrl}\"")
        }.joinToString(" ")

        val command = listOf(
            videoInput, subtitleInputs, audioInputs,
            "-map 0:v", audioMaps, "-map 0:a?", subtitleMaps, "-map 0:s? -map 0:t?",
            "-f matroska -c:a copy -c:v copy -c:s copy",
            subtitleMetadata, audioMetadata, sourceVideoOptions,
            "\"$ffmpegFilename\" -y",
        )
            .filter(String::isNotBlank)
            .joinToString(" ")

        return FFmpegKitConfig.parseArguments(command)
    }

    private suspend fun getDuration(ffprobeCommand: Array<String>): Float? {
        return suspendCancellableCoroutine { continuation ->
            val session = FFprobeKit.executeWithArgumentsAsync(ffprobeCommand) {
                if (it.returnCode.isValueSuccess) {
                    continuation.resume(it)
                } else {
                    continuation.resumeWithException(Exception(it.output))
                }
            }
            continuation.invokeOnCancellation { session.cancel() }
        }.output.toFloatOrNull()
    }

    /**
     * Returns the observable which downloads the video with an external downloader.
     *
     * @param video the video to download.
     * @param source the source of the video.
     * @param tmpDir the temporary directory of the download.
     * @param filename the filename of the video.
     */
    private suspend fun downloadVideoExternal(
        video: Video,
        source: AnimeHttpSource,
        tmpDir: UniFile,
        filename: String,
    ): UniFile {
        try {
            val file = tmpDir.createFile("${filename}_tmp.mkv")!!
            withUIContext {
                context.copyToClipboard("Episode download location", tmpDir.filePath!!.substringBeforeLast("_tmp"))
            }

            // TODO: support other file formats!!
            // start download with intent
            val pm = context.packageManager
            val pkgName = preferences.externalDownloaderSelection().get()
            val intent: Intent
            if (pkgName.isNotEmpty()) {
                intent = pm.getLaunchIntentForPackage(pkgName) ?: throw Exception(
                    "Launch intent not found",
                )
                when {
                    // 1DM
                    pkgName.startsWith("idm.internet.download.manager") -> {
                        val headers = (video.headers ?: source.headers).toMap()
                        val bundle = Bundle()
                        for ((key, value) in headers) {
                            bundle.putString(key, value)
                        }

                        intent.apply {
                            component = ComponentName(
                                pkgName,
                                "idm.internet.download.manager.Downloader",
                            )
                            action = Intent.ACTION_VIEW
                            data = video.videoUrl.toUri()

                            putExtra("extra_filename", "$filename.mkv")
                            putExtra("extra_headers", bundle)
                        }
                    }
                    // ADM
                    pkgName.startsWith("com.dv.adm") -> {
                        val headers = (video.headers ?: source.headers).toList()
                        val bundle = Bundle()
                        headers.forEach { a ->
                            bundle.putString(
                                a.first,
                                a.second.replace("http", "h_ttp"),
                            )
                        }

                        intent.apply {
                            component = ComponentName(pkgName, "$pkgName.AEditor")
                            action = Intent.ACTION_VIEW
                            putExtra(
                                "com.dv.get.ACTION_LIST_ADD",
                                "${video.videoUrl.toUri()}<info>$filename.mkv",
                            )
                            putExtra(
                                "com.dv.get.ACTION_LIST_PATH",
                                tmpDir.filePath!!.substringBeforeLast("_"),
                            )
                            putExtra("android.media.intent.extra.HTTP_HEADERS", bundle)
                        }
                        file.delete()
                        tmpDir.delete()
                        queueState.value.find { anime -> anime.video == video }?.let { download ->
                            download.status = AnimeDownload.State.DOWNLOADED
                            // Delete successful downloads from queue
                            if (download.status == AnimeDownload.State.DOWNLOADED) {
                                // Remove downloaded episode from queue
                                removeFromQueue(download)
                            }
                            if (areAllAnimeDownloadsFinished()) {
                                stop()
                            }
                        }
                    }
                }
            } else {
                intent = Intent(Intent.ACTION_VIEW).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    setDataAndType(video.videoUrl.toUri(), "video/*")
                    putExtra("extra_filename", filename)
                }
            }
            context.startActivity(intent)
            return file
        } catch (e: Exception) {
            tmpDir.findFile("${filename}_tmp.mkv")?.delete()
            throw e
        }
    }

    /**
     * Checks if the download was successful.
     *
     * @param download the download to check.
     * @param tmpDir the directory where the download is currently stored.
     */
    private fun isFinishedVideo(file: UniFile): Boolean = file.isFile &&
        file.length() > 0 &&
        file.name?.substringAfterLast('.')?.lowercase() in setOf("mkv", "mp4", "m4v", "webm") &&
        file.name?.endsWith("_tmp.mkv") != true

    private fun isDownloadSuccessful(
        download: AnimeDownload,
        tmpDir: UniFile,
    ): Boolean {
        val files = tmpDir.listFiles().orEmpty()
        // External downloaders own completion; retain their established handoff behavior.
        val downloadedVideo = if (preferences.useExternalDownloader().get() != download.changeDownloader) {
            files.filterNot { it.extension == ".tmp" }
        } else {
            files.filter(::isFinishedVideo)
        }
        return downloadedVideo.size == 1
    }

    /**
     * Checks if the download was successful.
     *
     * @param download the download to check.
     * @param animeDir the anime directory of the download.
     * @param tmpDir the directory where the download is currently stored.
     * @param dirname the real (non temporary) directory name of the download.
     */
    private suspend fun ensureSuccessfulAnimeDownload(
        download: AnimeDownload,
        animeDir: UniFile,
        tmpDir: UniFile,
        dirname: String,
    ) {
        // Ensure that the episode folder has the full video
        val downloadedVideo = tmpDir.listFiles().orEmpty().filter(::isFinishedVideo)

        download.status = if (downloadedVideo.size == 1) {
            // Only rename the directory if it's downloaded
            val filename = DiskUtil.buildValidFilename("${download.anime.title} - ${download.episode.name}")
            tmpDir.findFile("${filename}_tmp.mkv")?.delete()
            tmpDir.renameTo(dirname)

            cache.addEpisode(dirname, animeDir, download.anime)

            DiskUtil.createNoMediaFile(tmpDir, context)
            AnimeDownload.State.DOWNLOADED
        } else {
            throw Exception("Unable to finalize download")
        }
    }

    /**
     * Returns true if all the queued downloads are in DOWNLOADED or ERROR state.
     */
    private fun areAllAnimeDownloadsFinished(): Boolean {
        return queueState.value.none { it.status.value <= AnimeDownload.State.DOWNLOADING.value }
    }

    private fun addAllToQueue(downloads: List<AnimeDownload>) {
        _queueState.update {
            downloads.forEach { download ->
                download.status = AnimeDownload.State.QUEUE
                if (preferences.downloadManuallyPaused(true).get()) {
                    download.transfer.phase(TransferPhase.WAITING, TransferWait.MANUAL)
                } else {
                    download.transfer.phase(TransferPhase.QUEUED)
                }
            }
            store.addAll(downloads)
            it + downloads
        }
    }

    private fun removeFromQueue(download: AnimeDownload) {
        _queueState.update {
            store.remove(download)
            if (download.status == AnimeDownload.State.DOWNLOADING || download.status == AnimeDownload.State.QUEUE) {
                download.status = AnimeDownload.State.NOT_DOWNLOADED
            }
            it - download
        }
    }

    private inline fun removeFromQueueIf(predicate: (AnimeDownload) -> Boolean) {
        _queueState.update { queue ->
            val downloads = queue.filter { predicate(it) }
            store.removeAll(downloads)
            downloads.forEach { download ->
                if (download.status == AnimeDownload.State.DOWNLOADING ||
                    download.status == AnimeDownload.State.QUEUE
                ) {
                    download.status = AnimeDownload.State.NOT_DOWNLOADED
                }
            }
            queue - downloads.toSet()
        }
    }

    fun removeFromQueue(episodes: List<Episode>) {
        val episodeIds = episodes.map { it.id }
        removeFromQueueIf { it.episode.id in episodeIds }
    }

    fun removeFromQueue(anime: Anime) {
        removeFromQueueIf { it.anime.id == anime.id }
    }

    private fun internalClearQueue() {
        _queueState.update {
            it.forEach { download ->
                if (download.status == AnimeDownload.State.DOWNLOADING ||
                    download.status == AnimeDownload.State.QUEUE
                ) {
                    download.status = AnimeDownload.State.NOT_DOWNLOADED
                }
            }
            store.clear()
            emptyList()
        }
    }

    fun updateQueue(downloads: List<AnimeDownload>) {
        if (queueState == downloads) return

        if (downloads.isEmpty()) {
            clearQueue()
            stop()
            return
        }

        val wasRunning = isRunning

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
        const val EPISODES_PER_SOURCE_QUEUE_WARNING_THRESHOLD = 10
    }
}

// Arbitrary minimum required space to start a download: 200 MB
private const val MIN_DISK_SPACE = 200L * 1024 * 1024
