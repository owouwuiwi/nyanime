package eu.kanade.tachiyomi.data.search

import android.content.Context
import android.net.ConnectivityManager
import android.util.AtomicFile
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.anime.interactor.GetAnimeIncognitoState
import eu.kanade.domain.source.manga.interactor.GetMangaIncognitoState
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import tachiyomi.domain.entries.anime.interactor.GetLibraryAnime
import tachiyomi.domain.entries.manga.interactor.GetLibraryManga
import tachiyomi.domain.search.LexicalTitleMatcher
import tachiyomi.domain.search.SearchCandidateFailure
import tachiyomi.domain.search.SearchCandidateProvider
import tachiyomi.domain.search.SearchMedium
import tachiyomi.domain.search.SearchRequestBudget
import tachiyomi.domain.search.SearchSession
import tachiyomi.domain.search.SearchTitle
import tachiyomi.domain.search.SymSpellTitleIndex
import tachiyomi.domain.search.TitleCacheCodec
import tachiyomi.domain.search.TitleSearch
import tachiyomi.domain.search.mergeSearchAliases
import tachiyomi.domain.search.runtimeSearchTitle
import tachiyomi.domain.search.searchAliases
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/** Disposable runtime dictionary. It is unrelated to tracking, playback and source linking. */
class SmartTitleSearch(context: Context) : TitleSearch {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val libraryAliases =
        java.util.concurrent.ConcurrentHashMap<Pair<Long, String>, List<String>>()
    private val preferences: SourcePreferences = Injekt.get()
    private val base: BasePreferences = Injekt.get()
    private val animePrivacy: GetAnimeIncognitoState = Injekt.get()
    private val mangaPrivacy: GetMangaIncognitoState = Injekt.get()
    private val animeLibrary: GetLibraryAnime = Injekt.get()
    private val mangaLibrary: GetLibraryManga = Injekt.get()
    private val json: Json = Injekt.get()
    private val catalog = CatalogSearchCandidates(Injekt.get<NetworkHelper>().apiClient, json)
    private val matcher = LexicalTitleMatcher()
    private val codec = TitleCacheCodec(json)
    private val index = SymSpellTitleIndex()
    private val lock = Mutex()
    private val file = AtomicFile(File(context.cacheDir, "runtime-search-v1.json"))
    private val stored = LinkedHashMap<String, SearchTitle>()
    private var loaded = false
    private var libraryAt = 0L

    override fun session(
        query: String,
        medium: SearchMedium,
        exact: Boolean,
        sourceId: Long?,
        catalogBudget: SearchRequestBudget?,
    ): SearchSession {
        val private =
            when (medium) {
                SearchMedium.VIDEO -> animePrivacy.await(sourceId)
                SearchMedium.MANGA -> mangaPrivacy.await(sourceId)
                SearchMedium.NOVEL -> base.incognitoMode().get()
            }
        val provider =
            object : SearchCandidateProvider {
                private val budget = catalogBudget ?: SearchRequestBudget(3)

                override suspend fun candidates(
                    query: String,
                    medium: SearchMedium,
                    online: Boolean,
                ): List<SearchTitle> =
                    withContext(Dispatchers.IO) {
                        val local = lock.withLock {
                            load()
                            if (medium == SearchMedium.NOVEL) {
                                refreshNovelLibrary()
                            } else {
                                refreshLibrary()
                            }
                            index.candidates(query, medium)
                        }
                        if (
                            !online ||
                            medium == SearchMedium.NOVEL ||
                            matcher.rank(query, local).firstOrNull()?.score?.let { it >= 85 } ==
                            true
                        ) {
                            return@withContext local
                        }
                        val remote =
                            try {
                                withTimeout(12_000) { catalog.fetch(query, medium, budget) }
                            } catch (timeout: TimeoutCancellationException) {
                                currentCoroutineContext().ensureActive()
                                throw SearchCandidateFailure(local, timeout)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                throw SearchCandidateFailure(local, error)
                            }
                        if (!private) remember(remote)
                        local + remote
                    }

                override suspend fun remember(items: List<SearchTitle>) {
                    if (private) return
                    withContext(Dispatchers.IO) {
                        lock.withLock {
                            load()
                            val safe =
                                items.mapNotNull(codec::sanitize).map(::withKnownAliases).filter { item ->
                                    item.title.isNotBlank() &&
                                        item.title.length <= 256 &&
                                        !isPrivate(item)
                                }
                            safe.forEach {
                                stored.remove(it.key)
                                stored[it.key] = it
                                rememberAliases(it)
                            }
                            while (stored.size > 5_000) stored.remove(stored.keys.first())
                            index.add(safe)
                            if (safe.isNotEmpty()) save()
                        }
                    }
                }
            }
        return SearchSession(
            query,
            medium,
            provider,
            matcher,
            enabled = preferences.tolerantSearch().get(),
            online =
            medium != SearchMedium.NOVEL &&
                preferences.onlineSearchAssistance().get() &&
                !base.downloadedOnly().get() &&
                connectivity?.activeNetwork != null,
            exact = exact,
            allowNetwork = !base.downloadedOnly().get(),
        )
    }

    suspend fun clear() =
        withContext(Dispatchers.IO) {
            lock.withLock {
                file.delete()
                stored.clear()
                libraryAliases.clear()
                index.clear()
                loaded = true
                libraryAt = 0
                novelLibraryAt = 0
            }
        }

    suspend fun prepareLibrary() = withContext(Dispatchers.IO) { lock.withLock { load() } }

    override suspend fun withKnownAliases(titles: List<SearchTitle>): List<SearchTitle> =
        withContext(Dispatchers.IO) {
            lock.withLock {
                load()
                titles.map { if (isPrivate(it)) it else withKnownAliases(it) }
            }
        }

    fun aliases(sourceId: Long, title: String): List<String> =
        libraryAliases[sourceId to title].orEmpty()

    private fun withKnownAliases(item: SearchTitle): SearchTitle =
        item.copy(
            aliases =
            mergeSearchAliases(
                item.aliases,
                stored[item.key]
                    ?.takeIf { it.medium == item.medium && it.sourceId == item.sourceId }
                    ?.aliases
                    .orEmpty(),
            ),
        )

    private fun rememberAliases(item: SearchTitle) {
        val source = item.sourceId ?: return
        if (item.aliases.isEmpty()) return
        libraryAliases[source to item.title] = item.aliases
        while (libraryAliases.size > TitleCacheCodec.CAPACITY) {
            libraryAliases.keys.firstOrNull()?.let(libraryAliases::remove) ?: break
        }
    }

    private fun isPrivate(item: SearchTitle) =
        when (item.medium) {
            SearchMedium.VIDEO -> animePrivacy.await(item.sourceId)
            SearchMedium.MANGA -> mangaPrivacy.await(item.sourceId)
            SearchMedium.NOVEL -> base.incognitoMode().get()
        }

    private var novelLibraryAt = 0L

    private suspend fun refreshNovelLibrary() {
        if (base.incognitoMode().get()) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (novelLibraryAt != 0L && now - novelLibraryAt < 30_000) return
        val store = Injekt.get<it.lorelore.nyanime.novel.NovelLibrary>().store
        var cursor: it.lorelore.nyanime.novel.NovelStore.Cursor? = null
        var count = 0
        do {
            val page = store.page(after = cursor, size = 200)
            index.add(
                page.items
                    .map {
                        val book = it.book
                        book.origin?.let { origin ->
                            runtimeSearchTitle(
                                origin.sourceId,
                                origin.reference,
                                book.title,
                                SearchMedium.NOVEL,
                            )
                        }
                            ?: SearchTitle(
                                "novel-local:" + book.id,
                                book.title,
                                SearchMedium.NOVEL,
                                origin = "library",
                            )
                    }
                    .map(::withKnownAliases),
            )
            count += page.items.size
            cursor = page.next
        } while (cursor != null && count < 2000)
        novelLibraryAt = now
    }

    private fun load() {
        if (loaded) return
        loaded = true
        try {
            if (file.baseFile.length() > 3_000_000) error("Oversized disposable cache")
            val titles = file.openRead().use { codec.decode(it.reader().readText()) }
            stored.putAll(titles.associateBy { it.key })
            index.replace(titles)
            titles.forEach(::rememberAliases)
        } catch (_: Exception) {
            file.delete()
        }
    }

    private suspend fun refreshLibrary() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (libraryAt != 0L && now - libraryAt < 30_000) return
        val titles =
            animeLibrary
                .await()
                .filterNot { animePrivacy.await(it.anime.source) }
                .map {
                    runtimeSearchTitle(
                        it.anime.source,
                        it.anime.url,
                        it.anime.title,
                        SearchMedium.VIDEO,
                        searchAliases(it.anime.memo),
                    )
                } +
                mangaLibrary
                    .await()
                    .filterNot { mangaPrivacy.await(it.manga.source) }
                    .map {
                        runtimeSearchTitle(
                            it.manga.source,
                            it.manga.url,
                            it.manga.title,
                            SearchMedium.MANGA,
                        )
                    }
        index.add(titles.map(::withKnownAliases))
        libraryAt = now
    }

    private fun save() {
        var output: java.io.FileOutputStream? = null
        try {
            output = file.startWrite()
            output.write(codec.encode(stored.values).toByteArray())
            file.finishWrite(output)
        } catch (cancelled: CancellationException) {
            output?.let(file::failWrite)
            throw cancelled
        } catch (_: Exception) {
            output?.let(file::failWrite)
        }
    }
}
