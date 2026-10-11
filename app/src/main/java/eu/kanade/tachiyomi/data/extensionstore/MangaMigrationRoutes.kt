package eu.kanade.tachiyomi.data.extensionstore

import eu.kanade.domain.entries.manga.model.toSManga
import eu.kanade.domain.items.chapter.model.toSChapter
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tachiyomi.data.handlers.manga.MangaDatabaseHandler
import tachiyomi.domain.entries.manga.repository.MangaRepository
import tachiyomi.domain.items.chapter.repository.ChapterRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URI

/** Proves that persisted raw manga and chapter routes survive a publisher change. */
internal object MangaMigrationRoutes {
    suspend fun verify(old: List<CatalogueSource>, new: List<CatalogueSource>, expected: Set<Long>) {
        val oldById = old.associateBy { it.id }
        val newById = new.associateBy { it.id }
        require(expected.isNotEmpty() && 0L !in expected)
        require(old.size == expected.size && oldById.keys == expected)
        require(new.size == expected.size && newById.keys == expected)
        val database = Injekt.get<MangaDatabaseHandler>()
        val saved = database.awaitList { mangasQueries.getAllMangaSourceAndUrl() }
            .filter { it.source in expected }
        require(saved.distinctBy { it.source to it.url }.size == saved.size)
        val mangas = Injekt.get<MangaRepository>()
        val chapters = Injekt.get<ChapterRepository>()
        val mangaRoutes = mutableSetOf<Pair<Long, HttpUrl>>()
        val chapterRoutes = mutableSetOf<Pair<Long, HttpUrl>>()
        for (entry in saved) {
            currentCoroutineContext().ensureActive()
            val oldSource = oldById.getValue(entry.source) as? HttpSource ?: error("Unverifiable saved manga source")
            val newSource = newById.getValue(entry.source) as? HttpSource ?: error("Unverifiable replacement manga source")
            val manga = requireNotNull(mangas.getMangaByUrlAndSourceId(entry.url, entry.source))
            require(manga.source == entry.source && manga.url == entry.url)
            checkStored(manga.url)
            val route = sameRoute(
                oldSource,
                newSource,
                oldSource.getMangaUrl(manga.toSManga()),
                newSource.getMangaUrl(manga.toSManga()),
            )
            require(mangaRoutes.add(entry.source to route))
            for (chapter in chapters.getChapterByMangaId(manga.id)) {
                currentCoroutineContext().ensureActive()
                checkStored(chapter.url)
                val chapterRoute = sameRoute(
                    oldSource,
                    newSource,
                    oldSource.getChapterUrl(chapter.toSChapter()),
                    newSource.getChapterUrl(chapter.toSChapter()),
                )
                require(chapterRoutes.add(entry.source to chapterRoute))
            }
        }
    }

    private fun sameRoute(old: HttpSource, new: HttpSource, before: String, after: String): HttpUrl {
        val left = strictUrl(before)
        val right = strictUrl(after)
        require(left.encodedPath != "/" && left.encodedPath == right.encodedPath)
        require(left.encodedQuery == right.encodedQuery)
        require(!left.isHttps || right.isHttps)
        if (origin(left) != origin(right)) {
            require(origin(left) == origin(strictUrl(old.baseUrl)))
            require(origin(right) == origin(strictUrl(new.baseUrl)))
        }
        return right
    }

    private fun checkStored(value: String) {
        require(value.isNotBlank() && value == value.trim())
        require(value.none { it == '\\' || it.isISOControl() })
    }

    private fun strictUrl(value: String): HttpUrl {
        checkStored(value)
        val uri = URI(value)
        val url = requireNotNull(value.toHttpUrlOrNull())
        require(uri.isAbsolute && uri.rawAuthority != null && uri.rawUserInfo == null && uri.rawFragment == null)
        require(uri.rawPath.ifEmpty { "/" } == url.encodedPath && uri.rawQuery == url.encodedQuery)
        return url
    }

    private fun origin(url: HttpUrl) = Triple(url.scheme, url.host, url.port)
}
