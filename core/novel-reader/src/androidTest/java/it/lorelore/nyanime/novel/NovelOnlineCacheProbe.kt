@file:OptIn(org.readium.r2.shared.InternalReadiumApi::class)

package it.lorelore.nyanime.novel

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.getOrElse
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

internal object NovelOnlineCacheProbe {
    suspend fun run(context: Context): List<String> {
        check(context.packageName == "it.lorelore.nyanime.novel.test")
        val root = File(context.cacheDir, "online-cache-probe")
        check(root.canonicalFile.parentFile == context.cacheDir.canonicalFile)
        root.deleteRecursively()
        val name = "online_cache_probe.sqlite"
        context.deleteDatabase(name)
        val origin = NovelOrigin(211, "cache-book")
        val book =
            NovelBook(
                NovelIdentity.remote(origin.sourceId, origin.reference),
                "Cache book",
                format = NovelFormat.ONLINE,
                contentVersion = NovelOnlineIdentity.edition(origin),
                origin = origin,
                importedAt = 0,
                modifiedAt = 0,
            )
        val chapters = (1..3).map { NovelOnlineChapter("chapter-$it", "Chapter $it") }
        val doc =
            NovelOnlineDocument(
                bookId = book.id,
                origin = origin,
                chapters = chapters,
                updatedAt = 0,
            )
        val calls = AtomicInteger(0)
        val fetcher =
            object : NovelOnlineFetcher {
                override suspend fun chapter(reference: String): NovelOnlineBody {
                    calls.incrementAndGet()
                    delay(15)
                    return NovelOnlineBody("Cached text $reference", false)
                }

                override suspend fun image(reference: String): NovelOnlineImage =
                    throw IOException("No image fixture")
            }
        var cache = NovelOnlineCache(context, root, name, byteLimit = 4096, entryLimit = 2)
        var access = cache.open(book, doc, fetcher)
        var publication = NovelOnlinePublication.open(book, access)
        try {
            check(calls.get() == 0 && cache.usage().entries == 0)
            coroutineScope {
                List(2) {
                    async {
                        val resource =
                            requireNotNull(
                                publication.get(requireNotNull(Url(chapters.first().href))),
                            )
                        try {
                            check(
                                resource
                                    .read()
                                    .getOrElse { error(it) }
                                    .toString(Charsets.UTF_8)
                                    .contains("Cached text"),
                            )
                        } finally {
                            resource.close()
                        }
                    }
                }
                    .awaitAll()
            }
            check(calls.get() == 1)
            val held = requireNotNull(publication.get(requireNotNull(Url(chapters.first().href))))
            val heldBytes = held.read().getOrElse { error(it) }
            try {
                for (chapter in chapters.drop(1)) {
                    val resource =
                        requireNotNull(publication.get(requireNotNull(Url(chapter.href))))
                    try {
                        resource.read().getOrElse { error(it) }
                    } finally {
                        resource.close()
                    }
                }
                check(cache.usage().let { it.bytes <= it.limitBytes && it.entries == 2 })
                check(held.read().getOrElse { error(it) }.contentEquals(heldBytes))
            } finally {
                held.close()
            }
            check(calls.get() == 3)
            val cachedAccess = access.cachedOnly()
            val cached = NovelOnlinePublication.open(book, cachedAccess)
            try {
                val missing = requireNotNull(cached.get(requireNotNull(Url(chapters[1].href))))
                try {
                    check(missing.read().getOrNull() == null)
                } finally {
                    missing.close()
                }
                val available =
                    requireNotNull(cached.get(requireNotNull(Url(chapters.last().href))))
                try {
                    check(available.read().getOrElse { error(it) }.isNotEmpty())
                } finally {
                    available.close()
                }
                check(calls.get() == 3)
            } finally {
                cached.close()
            }
        } finally {
            publication.close()
            cache.close()
        }
        val orphan =
            File(root, "objects/${"e".repeat(64)}-${"f".repeat(64)}.bin").apply {
                writeText("orphan")
            }
        val unknown = File(root, "objects/leave-me.txt").apply { writeText("not a cache object") }
        cache = NovelOnlineCache(context, root, name, byteLimit = 4096, entryLimit = 2)
        access = cache.open(book, doc, fetcher)
        publication = NovelOnlinePublication.open(book, access)
        try {
            check(!orphan.exists() && unknown.readText() == "not a cache object")
            val file = access.materialize(chapters.last().href).file
            file.writeText("corrupted object")
            val beforeRecovery = calls.get()
            val damaged = requireNotNull(publication.get(requireNotNull(Url(chapters.last().href))))
            try {
                check(
                    damaged
                        .read()
                        .getOrElse { error(it) }
                        .toString(Charsets.UTF_8)
                        .contains("Cached text"),
                )
            } finally {
                damaged.close()
            }
            check(calls.get() == beforeRecovery + 1)
            for (chapter in chapters) {
                val resource = requireNotNull(publication.get(requireNotNull(Url(chapter.href))))
                try {
                    check(
                        resource
                            .read()
                            .getOrElse { error(it) }
                            .toString(Charsets.UTF_8)
                            .contains("Cached text"),
                    )
                } finally {
                    resource.close()
                }
            }
            check(cache.usage().let { it.bytes <= it.limitBytes && it.entries == 2 })
        } finally {
            publication.close()
            cache.close()
        }
        context.deleteDatabase(name)
        check(root.deleteRecursively())
        verifyImages(context, root, name, book, doc)
        return listOf(
            "online_cache_coalesces_source_reads_and_pins_live_streams_during_lru_eviction",
            "online_cache_only_view_never_fetches_missing_chapters",
            "online_cache_recovers_owned_orphans_and_corruption_without_touching_other_files",
            "online_image_cache_checks_real_bitmap_bytes_and_reopens_without_network",
        )
    }

    private suspend fun verifyImages(
        context: Context,
        root: File,
        name: String,
        book: NovelBook,
        document: NovelOnlineDocument,
    ) {
        val png =
            java.io.ByteArrayOutputStream().use { output ->
                val bitmap =
                    android.graphics.Bitmap.createBitmap(
                        1,
                        1,
                        android.graphics.Bitmap.Config.ARGB_8888,
                    )
                try {
                    check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output))
                } finally {
                    bitmap.recycle()
                }
                output.toByteArray()
            }
        val requests = AtomicInteger(0)
        val source =
            object : NovelOnlineFetcher {
                override suspend fun chapter(reference: String) =
                    NovelOnlineBody(
                        "<p>Illustrated text</p><img src='good'><img src='bad'>",
                        true,
                        mapOf("good" to "image-good", "bad" to "image-bad"),
                    )

                override suspend fun image(reference: String): NovelOnlineImage {
                    requests.incrementAndGet()
                    return NovelOnlineImage(
                        if (reference == "image-good") {
                            png
                        } else {
                            "<script>not an image</script>".toByteArray()
                        },
                        "image/png",
                    )
                }
            }
        NovelOnlineCache(context, root, name, byteLimit = 1024 * 1024).use { cache ->
            val access = cache.open(book, document, source)
            val publication = NovelOnlinePublication.open(book, access)
            try {
                val body =
                    requireNotNull(
                        publication.get(requireNotNull(Url(document.chapters.first().href))),
                    )
                try {
                    body.read().getOrElse { error(it) }
                } finally {
                    body.close()
                }
                for (reference in listOf("image-good", "image-bad")) {
                    val image =
                        requireNotNull(
                            publication.get(
                                requireNotNull(Url(NovelOnlineIdentity.resourceHref(reference))),
                            ),
                        )
                    try {
                        val result = image.read()
                        if (reference == "image-good") {
                            check(result.getOrElse { error(it) }.contentEquals(png))
                        } else {
                            check(result.getOrNull() == null)
                        }
                    } finally {
                        image.close()
                    }
                }
                check(requests.get() == 2 && cache.usage().entries == 2)
                val cached = NovelOnlinePublication.open(book, access.cachedOnly())
                try {
                    val bodyAgain =
                        requireNotNull(
                            cached.get(requireNotNull(Url(document.chapters.first().href))),
                        )
                    try {
                        bodyAgain.read().getOrElse { error(it) }
                    } finally {
                        bodyAgain.close()
                    }
                    val imageAgain =
                        requireNotNull(
                            cached.get(
                                requireNotNull(Url(NovelOnlineIdentity.resourceHref("image-good"))),
                            ),
                        )
                    try {
                        check(imageAgain.read().getOrElse { error(it) }.contentEquals(png))
                    } finally {
                        imageAgain.close()
                    }
                    check(requests.get() == 2)
                } finally {
                    cached.close()
                }
            } finally {
                publication.close()
            }
        }
        context.deleteDatabase(name)
        check(root.deleteRecursively())
    }
}
