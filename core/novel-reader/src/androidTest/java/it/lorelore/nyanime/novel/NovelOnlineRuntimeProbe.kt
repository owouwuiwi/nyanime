@file:OptIn(org.readium.r2.shared.InternalReadiumApi::class)

package it.lorelore.nyanime.novel

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.positions
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.resource.mediaType
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

internal object NovelOnlineRuntimeProbe {
    val webRequests = java.util.concurrent.CopyOnWriteArrayList<String>()

    fun webFixture(context: Context): Publication {
        webRequests.clear()
        check(context.packageName == "it.lorelore.nyanime.novel.test")
        val origin = NovelOrigin(127, "web-fixture")
        val book =
            NovelBook(
                NovelIdentity.remote(origin.sourceId, origin.reference),
                "Online fixture",
                format = NovelFormat.ONLINE,
                contentVersion = NovelOnlineIdentity.edition(origin),
                origin = origin,
                importedAt = 0,
                modifiedAt = 0,
            )
        val chapter = NovelOnlineChapter("web-chapter", "Online chapter")
        val bytes =
            NovelOnlineHtml.prepare(
                chapter,
                NovelOnlineBody(
                    "<p>online fixture 日本語</p><img src='declared'>",
                    true,
                    mapOf("declared" to "fixture-image"),
                ),
                listOf(chapter),
            )
                .bytes
        val file = File(context.cacheDir, "online-web-probe.html").apply { writeBytes(bytes) }
        val image =
            File(context.cacheDir, "online-web-probe.png").apply {
                val bitmap =
                    android.graphics.Bitmap.createBitmap(
                        1,
                        1,
                        android.graphics.Bitmap.Config.ARGB_8888,
                    )
                try {
                    outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                } finally {
                    bitmap.recycle()
                }
            }
        val access =
            object : NovelOnlineAccess {
                override val document =
                    NovelOnlineDocument(
                        bookId = book.id,
                        origin = origin,
                        chapters = listOf(chapter),
                        updatedAt = 0,
                    )
                override val chapterVersions =
                    MutableStateFlow(mapOf(chapter.href to NovelOnlineIdentity.digest(bytes)))

                override fun contains(href: String) =
                    href == chapter.href ||
                        href == NovelOnlineIdentity.resourceHref("fixture-image")

                override suspend fun materialize(href: String): NovelOnlineFile {
                    webRequests += href
                    return if (href == chapter.href) {
                        NovelOnlineFile(file, MediaType.HTML, NovelOnlineIdentity.digest(bytes))
                    } else {
                        NovelOnlineFile(
                            image,
                            requireNotNull(MediaType("image/png")),
                            NovelOnlineIdentity.digest(image.readBytes()),
                        )
                    }
                }

                override fun close() {
                    check(file.delete() && image.delete())
                }
            }
        return NovelOnlinePublication.open(book, access)
    }

    suspend fun run(context: Context): List<String> {
        check(context.packageName == "it.lorelore.nyanime.novel.test")
        val origin = NovelOrigin(127, "fixture-book")
        val book =
            NovelBook(
                NovelIdentity.remote(origin.sourceId, origin.reference),
                "Online fixture",
                format = NovelFormat.ONLINE,
                contentVersion = NovelOnlineIdentity.edition(origin),
                origin = origin,
                importedAt = 0,
                modifiedAt = 0,
            )
        val chapters = (1..6).map { NovelOnlineChapter("chapter-$it", "Chapter $it") }
        val document =
            NovelOnlineDocument(
                bookId = book.id,
                origin = origin,
                chapters = chapters,
                updatedAt = 0,
            )
        val bytes =
            NovelOnlineHtml.prepare(
                chapters.first(),
                NovelOnlineBody("Online text 日本語", false),
                chapters,
            )
                .bytes
        val file =
            File(context.cacheDir, "online-publication-probe.html").apply { writeBytes(bytes) }
        val calls = AtomicInteger(0)
        val active = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val closes = AtomicInteger(0)
        val leases = AtomicInteger(0)
        fun access(load: suspend (String) -> NovelOnlineFile) =
            object : NovelOnlineAccess {
                override val document = document
                override val chapterVersions = MutableStateFlow(emptyMap<String, String>())

                override fun contains(href: String) = chapters.any { it.href == href }

                override fun retain(href: String): AutoCloseable {
                    leases.incrementAndGet()
                    return AutoCloseable { check(leases.decrementAndGet() >= 0) }
                }

                override suspend fun materialize(href: String) = load(href)

                override fun close() {
                    closes.incrementAndGet()
                }
            }
        val publication =
            NovelOnlinePublication.open(
                book,
                access {
                    calls.incrementAndGet()
                    val now = active.incrementAndGet()
                    peak.updateAndGet { maxOf(it, now) }
                    try {
                        delay(30)
                        NovelOnlineFile(file, MediaType.HTML, NovelOnlineIdentity.digest(bytes))
                    } finally {
                        active.decrementAndGet()
                    }
                },
            )
        try {
            check(publication.conformsTo(Publication.Profile.EPUB))
            check(publication.positions().size == 6 && calls.get() == 0)
            for (
            href in
            listOf(
                "https://outside.invalid/chapter",
                "https://readium_package/unknown",
                "../outside",
                "chapters/missing.xhtml",
                "chapters/missing.xhtml?external=1",
            )
            ) {
                check(publication.get(requireNotNull(Url(href))) == null)
            }
            check(calls.get() == 0)
            requireNotNull(
                publication.get(
                    requireNotNull(Url("https://readium_package/${chapters.first().href}")),
                ),
            )
                .close()
            coroutineScope {
                chapters
                    .map { chapter ->
                        async {
                            val resource =
                                requireNotNull(publication.get(requireNotNull(Url(chapter.href))))
                            try {
                                check(resource.sourceUrl == null)
                                check(
                                    resource.properties().getOrElse { error(it) }.mediaType ==
                                        MediaType.HTML,
                                )
                                check(
                                    resource
                                        .read(0L..9L)
                                        .getOrElse { error(it) }
                                        .contentEquals(bytes.copyOfRange(0, 10)),
                                )
                            } finally {
                                resource.close()
                            }
                        }
                    }
                    .awaitAll()
            }
            check(calls.get() == 6 && peak.get() in 1..2)
        } finally {
            publication.close()
            publication.close()
        }
        check(closes.get() == 1 && leases.get() == 0)

        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val waiting =
            NovelOnlinePublication.open(
                book,
                access {
                    started.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        stopped.complete(Unit)
                    }
                },
            )
        coroutineScope {
            val resource = requireNotNull(waiting.get(requireNotNull(Url(chapters.first().href))))
            val read = async { resource.read() }
            started.await()
            waiting.close()
            check(read.await().getOrNull() == null && !read.isCancelled)
            stopped.await()
            resource.close()
        }
        var attempt = 0
        val retry =
            NovelOnlinePublication.open(
                book,
                access {
                    if (++attempt == 1) throw IOException("temporary fixture failure")
                    NovelOnlineFile(file, MediaType.HTML, NovelOnlineIdentity.digest(bytes))
                },
            )
        try {
            val resource = requireNotNull(retry.get(requireNotNull(Url(chapters.first().href))))
            try {
                check(resource.read().getOrNull() == null)
                check(resource.read().getOrElse { error(it) }.contentEquals(bytes))
            } finally {
                resource.close()
            }
        } finally {
            retry.close()
        }
        check(attempt == 2 && file.delete() && leases.get() == 0)
        context.deleteDatabase("novel_online_library_probe.sqlite")
        NovelStore(context, "novel_online_library_probe.sqlite").use { store ->
            store.put(book)
            val point =
                NovelPosition(
                    book.id,
                    book.contentVersion,
                    "{\"href\":\"${chapters[1].href}\",\"type\":\"text/html\"}",
                    200,
                    .25,
                    chapters[1].href,
                )
            store.savePosition(point)
            val library =
                NovelLibrary(
                    context,
                    store,
                    NovelOnlineOpener { current ->
                        check(current.id == book.id)
                        store.put(current.copy(title = "Refreshed online title", modifiedAt = 1))
                        access { throw IOException("No chapter read expected while opening") }
                    },
                )
            library.open(book.id).use { opened ->
                check(
                    opened.book.title == "Refreshed online title" &&
                        opened.position == point &&
                        opened.online != null,
                )
                check(opened.publication.readingOrder.size == 6)
            }
        }
        context.deleteDatabase("novel_online_library_probe.sqlite")
        return listOf(
            "online_publication_opens_without_prefetch_or_arbitrary_urls",
            "online_resource_reads_keep_ranges_mime_and_two_request_limit",
            "online_close_cancels_pending_source_work_and_releases_once",
            "online_transient_failure_can_retry_without_replacing_publication",
            "library_online_open_keeps_refreshed_metadata_and_structural_position_without_local_relink",
        )
    }
}
