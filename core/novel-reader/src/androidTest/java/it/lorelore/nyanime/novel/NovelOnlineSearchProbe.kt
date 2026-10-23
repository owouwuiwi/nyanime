package it.lorelore.nyanime.novel

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeout
import java.io.File

/** Search may inspect saved bytes, but it must never turn into an implicit full-book download. */
internal object NovelOnlineSearchProbe {
    suspend fun run(context: Context): List<String> {
        check(context.packageName == "it.lorelore.nyanime.novel.test")
        val root = File(context.cacheDir, "novel-online-search-probe")
        check(!root.exists() || root.deleteRecursively())
        val database = "novel_online_search_probe.sqlite"
        context.deleteDatabase(database)
        val origin = NovelOrigin(173, "search-fixture")
        val book =
            NovelBook(
                NovelIdentity.remote(origin.sourceId, origin.reference),
                "Search fixture",
                format = NovelFormat.ONLINE,
                contentVersion = NovelOnlineIdentity.edition(origin),
                origin = origin,
                importedAt = 0,
                modifiedAt = 0,
            )
        val chapters = listOf(NovelOnlineChapter("one", "One"), NovelOnlineChapter("two", "Two"))
        val document =
            NovelOnlineDocument(
                bookId = book.id,
                origin = origin,
                chapters = chapters,
                updatedAt = 0,
            )
        val search = NovelSearch(context)
        var calls = 0
        var body = "Alpha cached chapter"
        val fetcher =
            object : NovelOnlineFetcher {
                override suspend fun chapter(reference: String): NovelOnlineBody {
                    calls++
                    return NovelOnlineBody(body, false)
                }

                override suspend fun image(reference: String): NovelOnlineImage =
                    error("No image requested")
            }
        try {
            NovelOnlineCache(context, root, database).use { cache ->
                val access = cache.open(book, document, fetcher)
                val first = access.materialize(chapters[0].href)
                val publication = NovelOnlinePublication.open(book, access)
                val opened = NovelLibrary.Opened(book, publication, null, access)
                try {
                    val scope = search.prepare(opened)
                    val version = scope.book.contentVersion
                    scope.use {
                        check(it.hrefs == setOf(chapters[0].href))
                        search.index(it) {}
                        check(search.find(it.book, "Alpha").size == 1)
                        check(search.find(it.book, "Beta").isEmpty())
                    }
                    check(calls == 1 && book.contentVersion == NovelOnlineIdentity.edition(origin))
                    val constrained =
                        NovelSearch(
                            context,
                            NovelSearch.Limits(
                                bookBytes = 1,
                                totalBytes = 1024,
                                databaseBytes = 4L * 1024 * 1024,
                            ),
                        )
                    constrained.prepare(opened).use { check(it.limited && it.hrefs!!.isEmpty()) }
                    check(calls == 1)
                    // Simulate an OS-evicted body; normal reading can refetch, cache-only search
                    // cannot.
                    check(first.file.delete())
                    body = "Gamma updated chapter"
                    val second = access.materialize(chapters[0].href)
                    search.prepare(opened).use {
                        check(it.book.contentVersion != version)
                        search.index(it) {}
                        check(search.find(it.book, "Alpha").isEmpty())
                        check(search.find(it.book, "Gamma").size == 1)
                    }
                    check(calls == 2)
                    check(second.file.delete())
                    search.prepare(opened).use {
                        check(it.hrefs!!.isEmpty())
                        search.index(it) {}
                        check(search.find(it.book, "Gamma").isEmpty())
                    }
                    check(calls == 2)
                } finally {
                    opened.close()
                }
            }
            var closed = 0
            var pins = 0
            val entered = CompletableDeferred<Unit>()
            val cacheOnly =
                object : NovelOnlineAccess {
                    override val document = document
                    override val chapterVersions =
                        MutableStateFlow(mapOf(chapters[0].href to "a".repeat(64)))

                    override fun contains(href: String) = href == chapters[0].href

                    override fun retain(href: String): AutoCloseable {
                        pins++
                        return AutoCloseable { pins-- }
                    }

                    override suspend fun materialize(href: String): NovelOnlineFile {
                        entered.complete(Unit)
                        awaitCancellation()
                    }

                    override fun close() {
                        closed++
                    }
                }
            val lazy =
                object : NovelOnlineAccess by cacheOnly {
                    override suspend fun cachedOnly(): NovelOnlineAccess = cacheOnly

                    override fun close() = Unit
                }
            val opened =
                NovelLibrary.Opened(book, NovelOnlinePublication.open(book, lazy), null, lazy)
            try {
                coroutineScope {
                    val pending = async { search.prepare(opened) }
                    withTimeout(10_000) { entered.await() }
                    pending.cancelAndJoin()
                }
                check(closed == 1 && pins == 0)
            } finally {
                opened.close()
            }
        } finally {
            search.clear(book.id)
            context.deleteDatabase(database)
            check(!root.exists() || root.deleteRecursively())
        }
        return listOf(
            "online_search_indexes_only_verified_saved_chapters_without_fetching",
            "online_search_snapshot_invalidates_changed_and_evicted_chapter_text",
            "online_search_budget_and_cancellation_release_all_cache_pins",
        )
    }
}
