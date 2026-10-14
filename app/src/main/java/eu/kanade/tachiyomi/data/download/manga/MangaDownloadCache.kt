package eu.kanade.tachiyomi.data.download.manga

import android.app.Application
import android.content.Context
import android.util.AtomicFile
import androidx.core.net.toUri
import androidx.core.util.writeBytes
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.MAX_DOWNLOAD_INDEX_BYTES
import eu.kanade.tachiyomi.data.download.listDownloadIndexFiles
import eu.kanade.tachiyomi.data.download.readDownloadIndex
import eu.kanade.tachiyomi.extension.manga.MangaExtensionManager
import eu.kanade.tachiyomi.source.MangaSource
import eu.kanade.tachiyomi.util.size
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.protobuf.ProtoBuf
import logcat.LogPriority
import tachiyomi.core.common.storage.extension
import tachiyomi.core.common.storage.nameWithoutExtension
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.items.chapter.model.Chapter
import tachiyomi.domain.source.manga.service.MangaSourceManager
import tachiyomi.domain.storage.service.StorageManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.Locale
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * Cache where we dump the downloads directory from the filesystem. This class is needed because
 * directory checking is expensive and it slows down the app. The cache is invalidated by the time
 * defined in [renewInterval] as we don't have any control over the filesystem and the user can
 * delete the folders at any time without the app noticing.
 */
class MangaDownloadCache(
    private val context: Context,
    private val provider: MangaDownloadProvider = Injekt.get(),
    private val sourceManager: MangaSourceManager = Injekt.get(),
    private val extensionManager: MangaExtensionManager = Injekt.get(),
    private val storageManager: StorageManager = Injekt.get(),
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val indexDispatcher = Dispatchers.IO.limitedParallelism(4)

    private val _changes: Channel<Unit> = Channel(Channel.CONFLATED)
    val changes = _changes.receiveAsFlow()
        .onStart { emit(Unit) }
        .shareIn(scope, SharingStarted.Lazily, 1)

    /**
     * The interval after which this cache should be invalidated. 1 hour shouldn't cause major
     * issues, as the cache is only used for UI feedback.
     */
    private val renewInterval = 1.hours.inWholeMilliseconds

    /**
     * The last time the cache was refreshed.
     */
    @Volatile
    private var lastRenew = 0L

    @Volatile
    private var retryAfter = 0L
    private val initJob: Job
    private var renewalJob: Job? = null

    private val _isInitializing = MutableStateFlow(false)
    val isInitializing = _isInitializing
        .debounce(1000L) // Don't notify if it finishes quickly enough
        .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    private val diskCacheFile: AtomicFile
        get() = AtomicFile(File(context.cacheDir, "dl_index_cache_v3"))

    private val rootDownloadsDirMutex = Mutex()

    @Volatile
    private var rootDownloadsDir = RootDirectory(storageManager.getDownloadsDirectory())

    init {
        // Read the last complete snapshot immediately; reconcile real files asynchronously afterward.
        initJob = scope.launch {
            rootDownloadsDirMutex.withLock {
                try {
                    val diskCache = diskCacheFile.openRead().use {
                        ProtoBuf.decodeFromByteArray<RootDirectory>(readDownloadIndex(it))
                    }
                    if (diskCache.dir?.uri == storageManager.getDownloadsDirectory()?.uri) {
                        rootDownloadsDir = diskCache
                    } else {
                        diskCacheFile.delete()
                    }
                } catch (_: FileNotFoundException) {
                    // A fresh installation has no index.
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    logcat(LogPriority.WARN, e) { "Could not load the download index; rebuilding it" }
                    diskCacheFile.delete()
                }
            }
            notifyChanges(persist = false)
        }

        storageManager.changes
            .onEach { invalidateCache() }
            .launchIn(scope)
    }

    /**
     * Returns true if the chapter is downloaded.
     *
     * @param chapterName the name of the chapter to query.
     * @param chapterScanlator scanlator of the chapter to query
     * @param mangaTitle the title of the manga to query.
     * @param sourceId the id of the source of the chapter.
     * @param skipCache whether to skip the directory cache and check in the filesystem.
     */
    fun isChapterDownloaded(
        chapterName: String,
        chapterScanlator: String?,
        mangaTitle: String,
        sourceId: Long,
        skipCache: Boolean,
    ): Boolean {
        if (skipCache) {
            val source = sourceManager.getOrStub(sourceId)
            return provider.findChapterDir(
                chapterName,
                chapterScanlator,
                mangaTitle,
                source,
            ) != null
        }

        renewCache()

        val sourceDir = rootDownloadsDir.sourceDirs[sourceId]
        if (sourceDir != null) {
            val mangaDir = sourceDir.mangaDirs[provider.getMangaDirName(mangaTitle)]
            if (mangaDir != null) {
                return provider.getValidChapterDirNames(chapterName, chapterScanlator)
                    .any { it in mangaDir.chapterDirs }
            }
        }
        return false
    }

    /**
     * Returns the amount of downloaded chapters.
     */
    fun getTotalDownloadCount(): Int {
        renewCache()

        return rootDownloadsDir.sourceDirs.values.sumOf { sourceDir ->
            sourceDir.mangaDirs.values.sumOf { mangaDir ->
                mangaDir.chapterDirs.size
            }
        }
    }

    /**
     * Returns the amount of downloaded chapters for a manga.
     *
     * @param manga the manga to check.
     */
    fun getDownloadCount(manga: Manga): Int {
        renewCache()

        val sourceDir = rootDownloadsDir.sourceDirs[manga.source]
        if (sourceDir != null) {
            val mangaDir = sourceDir.mangaDirs[provider.getMangaDirName(manga.title)]
            if (mangaDir != null) {
                return mangaDir.chapterDirs.size
            }
        }
        return 0
    }

    /**
     * Returns the total size of downloaded chapters.
     */
    fun getTotalDownloadSize(): Long {
        renewCache()

        return rootDownloadsDir.sourceDirs.values.sumOf { sourceDir ->
            sourceDir.dir?.size() ?: 0L
        }
    }

    /**
     * Returns the total size of downloaded chapters for a manga.
     *
     * @param manga the manga to check.
     */
    fun getDownloadSize(manga: Manga): Long {
        renewCache()

        return rootDownloadsDir.sourceDirs[manga.source]?.mangaDirs?.get(
            provider.getMangaDirName(
                manga.title,
            ),
        )?.dir?.size() ?: 0
    }

    /**
     * Adds a chapter that has just been download to this cache.
     *
     * @param chapterDirName the downloaded chapter's directory name.
     * @param mangaUniFile the directory of the manga.
     * @param manga the manga of the chapter.
     */
    suspend fun addChapter(chapterDirName: String, mangaUniFile: UniFile, manga: Manga) {
        rootDownloadsDirMutex.withLock {
            // Retrieve the cached source directory or cache a new one
            var sourceDir = rootDownloadsDir.sourceDirs[manga.source]
            if (sourceDir == null) {
                val source = sourceManager.get(manga.source) ?: return
                val sourceUniFile = provider.findSourceDir(source) ?: return
                sourceDir = SourceDirectory(sourceUniFile)
                rootDownloadsDir.sourceDirs += manga.source to sourceDir
            }

            // Retrieve the cached manga directory or cache a new one
            val mangaDirName = provider.getMangaDirName(manga.title)
            var mangaDir = sourceDir.mangaDirs[mangaDirName]
            if (mangaDir == null) {
                mangaDir = MangaDirectory(mangaUniFile)
                sourceDir.mangaDirs += mangaDirName to mangaDir
            }

            // Save the chapter directory
            mangaDir.chapterDirs += chapterDirName
        }

        notifyChanges()
    }

    /**
     * Removes a chapter that has been deleted from this cache.
     *
     * @param chapter the chapter to remove.
     * @param manga the manga of the chapter.
     */
    suspend fun removeChapter(chapter: Chapter, manga: Manga) {
        rootDownloadsDirMutex.withLock {
            val sourceDir = rootDownloadsDir.sourceDirs[manga.source] ?: return
            val mangaDir = sourceDir.mangaDirs[provider.getMangaDirName(manga.title)] ?: return
            provider.getValidChapterDirNames(chapter.name, chapter.scanlator).forEach {
                if (it in mangaDir.chapterDirs) {
                    mangaDir.chapterDirs -= it
                }
            }
        }

        notifyChanges()
    }

    /**
     * Removes a list of chapters that have been deleted from this cache.
     *
     * @param chapters the list of chapter to remove.
     * @param manga the manga of the chapter.
     */
    suspend fun removeChapters(chapters: List<Chapter>, manga: Manga) {
        rootDownloadsDirMutex.withLock {
            val sourceDir = rootDownloadsDir.sourceDirs[manga.source] ?: return
            val mangaDir = sourceDir.mangaDirs[provider.getMangaDirName(manga.title)] ?: return
            chapters.forEach { chapter ->
                provider.getValidChapterDirNames(chapter.name, chapter.scanlator).forEach {
                    if (it in mangaDir.chapterDirs) {
                        mangaDir.chapterDirs -= it
                    }
                }
            }
        }

        notifyChanges()
    }

    /**
     * Removes a manga that has been deleted from this cache.
     *
     * @param manga the manga to remove.
     */
    suspend fun removeManga(manga: Manga) {
        rootDownloadsDirMutex.withLock {
            val sourceDir = rootDownloadsDir.sourceDirs[manga.source] ?: return
            val mangaDirName = provider.getMangaDirName(manga.title)
            if (sourceDir.mangaDirs.containsKey(mangaDirName)) {
                sourceDir.mangaDirs -= mangaDirName
            }
        }

        notifyChanges()
    }

    suspend fun removeSource(source: MangaSource) {
        rootDownloadsDirMutex.withLock {
            rootDownloadsDir.sourceDirs -= source.id
        }

        notifyChanges()
    }

    fun invalidateCache() {
        renewalJob?.cancel()
        updateDiskCacheJob?.cancel()
        lastRenew = 0L
        retryAfter = 0L
        scope.launch {
            initJob.join()
            rootDownloadsDirMutex.withLock {
                diskCacheFile.delete()
                val directory = storageManager.getDownloadsDirectory()
                if (rootDownloadsDir.dir?.uri != directory?.uri) {
                    rootDownloadsDir = RootDirectory(directory)
                }
            }
            renewCache()
        }
    }

    /** Publish a complete scan only after success; an unavailable source list must not erase the old index. */
    @Synchronized
    private fun renewCache() {
        val now = System.currentTimeMillis()
        if (lastRenew + renewInterval >= now || renewalJob?.isActive == true || now < retryAfter) return
        renewalJob = scope.launchIO {
            initJob.join()
            if (lastRenew == 0L) _isInitializing.emit(true)
            try {
                val ready = withTimeoutOrNull(30.seconds) {
                    extensionManager.isInitialized.first { it }
                    sourceManager.isInitialized.first { it }
                    true
                } == true
                if (!ready) throw IOException("Sources are not initialized")
                val sourceMap = getSources().associate {
                    provider.getSourceDirName(it).lowercase(Locale.ROOT) to it.id
                }
                rootDownloadsDirMutex.withLock {
                    val updatedRootDir = RootDirectory(storageManager.getDownloadsDirectory())
                    if (updatedRootDir.dir != null && !updatedRootDir.dir.canRead()) {
                        throw IOException("Download directory is unavailable")
                    }
                    val previous = rootDownloadsDir.takeIf { it.dir?.uri == updatedRootDir.dir?.uri }
                    val knownDirectories = previous?.sourceDirs.orEmpty()
                        .entries.associate { it.value.dir?.uri to it.key }
                    val directories = updatedRootDir.dir.listDownloadIndexFiles()
                        .filter { it.isDirectory && !it.name.isNullOrBlank() }
                    updatedRootDir.sourceDirs = directories.mapNotNull { dir ->
                        val sourceId = sourceMap[dir.name!!.lowercase(Locale.ROOT)] ?: knownDirectories[dir.uri]
                        sourceId?.let { it to SourceDirectory(dir) }
                    }.toMap()
                    supervisorScope {
                        updatedRootDir.sourceDirs.values.map { sourceDir ->
                            async(indexDispatcher) {
                                sourceDir.mangaDirs = sourceDir.dir.listDownloadIndexFiles()
                                    .filter { it.isDirectory && !it.name.isNullOrBlank() }
                                    .associate { it.name!! to MangaDirectory(it) }
                                sourceDir.mangaDirs.values.forEach { mangaDir ->
                                    val chapterDirs = mangaDir.dir.listDownloadIndexFiles()
                                        .mapNotNull {
                                            when {
                                                // Ignore incomplete downloads
                                                it.name?.endsWith(MangaDownloader.TMP_DIR_SUFFIX) == true -> null
                                                // Folder of images
                                                it.isDirectory -> it.name
                                                // CBZ files
                                                it.isFile && it.extension == "cbz" -> it.nameWithoutExtension
                                                // Anything else is irrelevant
                                                else -> null
                                            }
                                        }
                                        .toSet()

                                    mangaDir.chapterDirs = chapterDirs
                                }
                            }
                        }.awaitAll()
                    }
                    ensureActive()
                    rootDownloadsDir = updatedRootDir
                    lastRenew = System.currentTimeMillis()
                    retryAfter = 0L
                }
                notifyChanges()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                retryAfter = System.currentTimeMillis() + 60_000
                logcat(LogPriority.WARN, e) { "Download index reconciliation deferred" }
            } finally {
                _isInitializing.emit(false)
            }
        }
    }

    private fun getSources(): List<MangaSource> {
        return sourceManager.getOnlineSources() + sourceManager.getStubSources()
    }

    private fun notifyChanges(persist: Boolean = true) {
        scope.launchNonCancellable {
            _changes.send(Unit)
        }
        if (persist) updateDiskCache()
    }

    private var updateDiskCacheJob: Job? = null
    private fun updateDiskCache() {
        updateDiskCacheJob?.cancel()
        updateDiskCacheJob = scope.launchIO {
            delay(1000)
            ensureActive()
            rootDownloadsDirMutex.withLock {
                try {
                    val bytes = ProtoBuf.encodeToByteArray(rootDownloadsDir)
                    ensureActive()
                    if (bytes.size > MAX_DOWNLOAD_INDEX_BYTES) throw IOException("Download index is too large")
                    diskCacheFile.writeBytes(bytes)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    logcat(LogPriority.WARN, e) { "Could not persist the complete download index" }
                }
            }
        }
    }
}

/**
 * Class to store the files under the root downloads directory.
 */
@Serializable
internal class RootDirectory(
    @Serializable(with = UniFileAsStringSerializer::class)
    val dir: UniFile?,
    @Volatile var sourceDirs: Map<Long, SourceDirectory> = mapOf(),
)

/**
 * Class to store the files under a source directory.
 */
@Serializable
internal class SourceDirectory(
    @Serializable(with = UniFileAsStringSerializer::class)
    val dir: UniFile?,
    @Volatile var mangaDirs: Map<String, MangaDirectory> = mapOf(),
)

/**
 * Class to store the files under a manga directory.
 */
@Serializable
internal class MangaDirectory(
    @Serializable(with = UniFileAsStringSerializer::class)
    val dir: UniFile?,
    @Volatile var chapterDirs: Set<String> = emptySet(),
)

private object UniFileAsStringSerializer : KSerializer<UniFile?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("UniFile", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: UniFile?) {
        return if (value == null) {
            encoder.encodeNull()
        } else {
            encoder.encodeString(value.uri.toString())
        }
    }

    override fun deserialize(decoder: Decoder): UniFile? {
        return if (decoder.decodeNotNullMark()) {
            UniFile.fromUri(Injekt.get<Application>(), decoder.decodeString().toUri())
        } else {
            decoder.decodeNull()
        }
    }
}
