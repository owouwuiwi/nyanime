package eu.kanade.tachiyomi.data.download.anime

import android.app.Application
import android.content.Context
import android.util.AtomicFile
import androidx.core.net.toUri
import androidx.core.util.writeBytes
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.data.download.MAX_DOWNLOAD_INDEX_BYTES
import eu.kanade.tachiyomi.data.download.listDownloadIndexFiles
import eu.kanade.tachiyomi.data.download.readDownloadIndex
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
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
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.items.episode.model.Episode
import tachiyomi.domain.source.anime.service.AnimeSourceManager
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
 * directory checking is expensive and it slows downs the app. The cache is invalidated by the time
 * defined in [renewInterval] as we don't have any control over the filesystem and the user can
 * delete the folders at any time without the app noticing.
 */
class AnimeDownloadCache(
    private val context: Context,
    private val provider: AnimeDownloadProvider = Injekt.get(),
    private val sourceManager: AnimeSourceManager = Injekt.get(),
    private val extensionManager: AnimeExtensionManager = Injekt.get(),
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
        get() = AtomicFile(File(context.cacheDir, "dl_anime_index_cache_v3"))

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
     * Returns true if the episode is downloaded.
     *
     * @param episodeName the name of the episode to query.
     * @param episodeScanlator scanlator of the episode to query
     * @param animeTitle the title of the anime to query.
     * @param sourceId the id of the source of the episode.
     */
    fun isEpisodeDownloaded(
        episodeName: String,
        episodeScanlator: String?,
        animeTitle: String,
        sourceId: Long,
        skipCache: Boolean,
    ): Boolean {
        if (skipCache) {
            val source = sourceManager.getOrStub(sourceId)
            return provider.findEpisodeDir(episodeName, episodeScanlator, animeTitle, source) != null
        }

        renewCache()

        val sourceDir = rootDownloadsDir.sourceDirs[sourceId]
        if (sourceDir != null) {
            val animeDir = sourceDir.animeDirs[provider.getAnimeDirName(animeTitle)]
            if (animeDir != null) {
                return provider.getValidEpisodeDirNames(
                    episodeName,
                    episodeScanlator,
                ).any { it in animeDir.episodeDirs }
            }
        }
        return false
    }

    /**
     * Returns the amount of downloaded episodes.
     */
    fun getTotalDownloadCount(): Int {
        renewCache()

        return rootDownloadsDir.sourceDirs.values.sumOf { sourceDir ->
            sourceDir.animeDirs.values.sumOf { animeDir ->
                animeDir.episodeDirs.size
            }
        }
    }

    /**
     * Returns the amount of downloaded episodes for an anime.
     *
     * @param anime the anime to check.
     */
    fun getDownloadCount(anime: Anime): Int {
        renewCache()

        val sourceDir = rootDownloadsDir.sourceDirs[anime.source]
        if (sourceDir != null) {
            val animeDir = sourceDir.animeDirs[provider.getAnimeDirName(anime.title)]
            if (animeDir != null) {
                return animeDir.episodeDirs.size
            }
        }
        return 0
    }

    /**
     * Returns the total size of downloaded episodes.
     */
    fun getTotalDownloadSize(): Long {
        renewCache()

        return rootDownloadsDir.sourceDirs.values.sumOf { sourceDir ->
            sourceDir.dir?.size() ?: 0L
        }
    }

    /**
     * Returns the total size of downloaded chapters for an anime.
     *
     * @param anime the anime to check.
     */
    fun getDownloadSize(anime: Anime): Long {
        renewCache()

        return rootDownloadsDir.sourceDirs[anime.source]?.animeDirs?.get(
            provider.getAnimeDirName(
                anime.title,
            ),
        )?.dir?.size() ?: 0
    }

    /**
     * Adds an episode that has just been download to this cache.
     *
     * @param episodeDirName the downloaded episode's directory name.
     * @param animeUniFile the directory of the anime.
     * @param anime the anime of the episode.
     */
    suspend fun addEpisode(episodeDirName: String, animeUniFile: UniFile, anime: Anime) {
        rootDownloadsDirMutex.withLock {
            // Retrieve the cached source directory or cache a new one
            var sourceDir = rootDownloadsDir.sourceDirs[anime.source]
            if (sourceDir == null) {
                val source = sourceManager.get(anime.source) ?: return
                val sourceUniFile = provider.findSourceDir(source) ?: return
                sourceDir = SourceDirectory(sourceUniFile)
                rootDownloadsDir.sourceDirs += anime.source to sourceDir
            }

            // Retrieve the cached anime directory or cache a new one
            val animeDirName = provider.getAnimeDirName(anime.title)
            var animeDir = sourceDir.animeDirs[animeDirName]
            if (animeDir == null) {
                animeDir = AnimeDirectory(animeUniFile)
                sourceDir.animeDirs += animeDirName to animeDir
            }

            // Save the chapter directory
            animeDir.episodeDirs += episodeDirName
        }

        notifyChanges()
    }

    /**
     * Removes an episode that has been deleted from this cache.
     *
     * @param episode the episode to remove.
     * @param anime the anime of the episode.
     */
    suspend fun removeEpisode(episode: Episode, anime: Anime) {
        rootDownloadsDirMutex.withLock {
            val sourceDir = rootDownloadsDir.sourceDirs[anime.source] ?: return
            val animeDir = sourceDir.animeDirs[provider.getAnimeDirName(anime.title)] ?: return
            provider.getValidEpisodeDirNames(episode.name, episode.scanlator).forEach {
                if (it in animeDir.episodeDirs) {
                    animeDir.episodeDirs -= it
                }
            }
        }

        notifyChanges()
    }

    /**
     * Removes a list of episodes that have been deleted from this cache.
     *
     * @param episodes the list of episode to remove.
     * @param anime the anime of the episode.
     */
    suspend fun removeEpisodes(episodes: List<Episode>, anime: Anime) {
        rootDownloadsDirMutex.withLock {
            val sourceDir = rootDownloadsDir.sourceDirs[anime.source] ?: return
            val animeDir = sourceDir.animeDirs[provider.getAnimeDirName(anime.title)] ?: return
            episodes.forEach { episode ->
                provider.getValidEpisodeDirNames(episode.name, episode.scanlator).forEach {
                    if (it in animeDir.episodeDirs) {
                        animeDir.episodeDirs -= it
                    }
                }
            }
        }

        notifyChanges()
    }

    /**
     * Removes an anime that has been deleted from this cache.
     *
     * @param anime the anime to remove.
     */
    suspend fun removeAnime(anime: Anime) {
        rootDownloadsDirMutex.withLock {
            val sourceDir = rootDownloadsDir.sourceDirs[anime.source] ?: return
            val animeDirName = provider.getAnimeDirName(anime.title)
            if (sourceDir.animeDirs.containsKey(animeDirName)) {
                sourceDir.animeDirs -= animeDirName
            }
        }

        notifyChanges()
    }

    suspend fun removeSource(source: AnimeSource) {
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
                                sourceDir.animeDirs = sourceDir.dir.listDownloadIndexFiles()
                                    .filter { it.isDirectory && !it.name.isNullOrBlank() }
                                    .associate { it.name!! to AnimeDirectory(it) }
                                sourceDir.animeDirs.values.forEach { animeDir ->
                                    val episodeDirs = animeDir.dir.listDownloadIndexFiles()
                                        .mapNotNull {
                                            when {
                                                // Ignore incomplete downloads
                                                it.name?.endsWith(AnimeDownloader.TMP_DIR_SUFFIX) == true -> null
                                                // Folder of videos
                                                it.isDirectory -> it.name
                                                // MP4 files
                                                it.isFile && it.extension == "mp4" -> it.nameWithoutExtension
                                                // MKV files
                                                it.isFile && it.extension == "mkv" -> it.nameWithoutExtension
                                                // Anything else is irrelevant
                                                else -> null
                                            }
                                        }
                                        .toSet()

                                    animeDir.episodeDirs = episodeDirs
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

    private fun getSources(): List<AnimeSource> {
        return sourceManager.getAll() + sourceManager.getStubSources()
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
    @Volatile var animeDirs: Map<String, AnimeDirectory> = mapOf(),
)

/**
 * Class to store the files under a manga directory.
 */
@Serializable
internal class AnimeDirectory(
    @Serializable(with = UniFileAsStringSerializer::class)
    val dir: UniFile?,
    @Volatile var episodeDirs: Set<String> = emptySet(),
)

private object UniFileAsStringSerializer : KSerializer<UniFile?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("UniFile", PrimitiveKind.STRING)

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
