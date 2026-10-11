package it.lorelore.nyanime.novel

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import org.readium.r2.shared.util.mediatype.MediaType
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Durable SQLite/file tests run in the self-targeted APK, never the user's library. */
internal object NovelDownloadProbe {
    suspend fun run(context: Context): List<String> {
        check(context.packageName == "it.lorelore.nyanime.novel.test")
        val root = File(context.filesDir, "durable-download-probe").canonicalFile
        check(root.parentFile == context.filesDir.canonicalFile)
        check(!root.exists() || root.deleteRecursively())
        val db = "durable_download_probe.sqlite"
        context.deleteDatabase(db)
        val origin = NovelOrigin(303, "synthetic-download-work")
        val book =
            NovelBook(
                NovelIdentity.remote(origin.sourceId, origin.reference),
                "Download fixture",
                format = NovelFormat.ONLINE,
                contentVersion = NovelOnlineIdentity.edition(origin),
                origin = origin,
                importedAt = 0,
                modifiedAt = 0,
            )
        val chapters = (1..3).map { NovelOnlineChapter("opaque-$it", "Chapter $it") }
        val index =
            NovelOnlineDocument(
                bookId = book.id,
                origin = origin,
                chapters = chapters,
                updatedAt = 0,
            )
        val inputs = File(context.cacheDir, "durable-download-inputs").apply { mkdirs() }
        val calls = AtomicInteger()
        val image = NovelOnlineIdentity.resourceHref("synthetic-illustration")
        val imageBytes =
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
        fun access(waitImage: CompletableDeferred<Unit>? = null): NovelOnlineAccess =
            object : NovelOnlineAccess {
                override val document = index
                override val chapterVersions = MutableStateFlow(emptyMap<String, String>())

                override fun contains(href: String) =
                    href == image || chapters.any { it.href == href }

                override suspend fun materialize(href: String): NovelOnlineFile {
                    calls.incrementAndGet()
                    if (href == image && waitImage != null) {
                        waitImage.complete(Unit)
                        delay(60_000)
                    }
                    val bytes =
                        if (href == image) {
                            imageBytes
                        } else {
                            "<html><body><p>Offline text $href</p><img src='../$image'></body></html>"
                                .toByteArray()
                        }
                    val file =
                        File(inputs, NovelOnlineIdentity.digest(href)).apply { writeBytes(bytes) }
                    return NovelOnlineFile(
                        file,
                        requireNotNull(MediaType(if (href == image) "image/png" else "text/html")),
                        NovelOnlineIdentity.digest(bytes),
                    )
                }

                override fun close() = Unit
            }
        var downloads = NovelDownloadStore(context, root, db)
        try {
            val first =
                downloads.enqueue(
                    NovelDownloadPlan(
                        book = book,
                        document = index,
                        selected = listOf(chapters[0].href),
                    ),
                )
            val lease = requireNotNull(downloads.claim(first.id))
            val observed = mutableListOf<NovelDownloadProgress>()
            access().use {
                NovelDownloader(downloads).run(lease, it) { value -> observed += value }
            }
            check(
                downloads.progress(book.id)?.let {
                    it.state == NovelDownloadState.READY &&
                        it.completedChapters == 1 &&
                        it.selectedChapters == 1 &&
                        it.savedBytes > 0
                } == true,
            )
            check(observed.any { it.activeResources == 1 && it.activeTotalResources == 2 })
            check(observed.map { it.completedChapters }.zipWithNext().all { (a, b) -> b >= a })
            val previousCalls = calls.get()
            downloads.close()
            check(inputs.deleteRecursively()) // Remove all source/cache materializations.
            downloads = NovelDownloadStore(context, root, db)
            downloads.open(book, index).use { offline ->
                check(
                    offline.materialize(chapters[0].href).file.readText().contains("Offline text"),
                )
                check(offline.materialize(image).file.readBytes().contentEquals(imageBytes))
                check(!offline.contains(chapters[1].href))
                offline.cachedOnly().use { check(it.materialize(chapters[0].href).file.isFile) }
            }
            check(calls.get() == previousCalls)

            inputs.mkdirs()
            val second =
                downloads.enqueue(
                    NovelDownloadPlan(
                        book = book,
                        document = index,
                        selected = listOf(chapters[1].href),
                    ),
                )
            check(second.selected == chapters.take(2).map { it.href })
            val secondLease = requireNotNull(downloads.claim(second.id))
            access().use { NovelDownloader(downloads).run(secondLease, it) }
            check(downloads.progress(book.id)?.completedChapters == 2)
            check(
                calls.get() == previousCalls + 1,
            ) // Verified chapter and shared illustration never re-fetch.
            val retry = requireNotNull(downloads.retry(book.id))
            val fresh = requireNotNull(downloads.claim(retry))
            check(runCatching { downloads.active(secondLease, chapters[1].href, 0, 1) }.isFailure)
            downloads.active(fresh, chapters[1].href, 2, 3)
            downloads.active(fresh, chapters[1].href, 1, 3)
            check(downloads.progress(book.id)?.activeResources == 2)
            downloads.release(fresh, paused = true)
            check(downloads.progress(book.id)?.state == NovelDownloadState.PAUSED)
            check(downloads.claim(retry) == null)

            val live = downloads.open(book, index)
            check(runCatching { downloads.remove(book.id) }.isFailure)
            check(live.materialize(chapters[0].href).file.isFile)
            live.close()
            val foreign = File(root, "${book.id}/keep-me.txt").apply { writeText("Unrelated") }
            downloads.remove(book.id)
            check(downloads.progress(book.id) == null && foreign.readText() == "Unrelated")

            val interrupted =
                downloads.enqueue(
                    NovelDownloadPlan(
                        book = book,
                        document = index,
                        selected = listOf(chapters[2].href),
                    ),
                )
            val interruptedLease = requireNotNull(downloads.claim(interrupted.id))
            coroutineScope {
                val waiting = CompletableDeferred<Unit>()
                val job = async {
                    access(waiting).use { NovelDownloader(downloads).run(interruptedLease, it) }
                }
                waiting.await()
                downloads.pause(book.id)
                job.cancelAndJoin()
            }
            check(
                downloads.progress(book.id)?.let {
                    it.state == NovelDownloadState.PAUSED &&
                        it.completedChapters == 0 &&
                        it.savedBytes > 0
                } == true,
            )
            val resumeId = requireNotNull(downloads.retry(book.id))
            access().use {
                NovelDownloader(downloads).run(requireNotNull(downloads.claim(resumeId)), it)
            }
            check(downloads.progress(book.id)?.state == NovelDownloadState.READY)

            downloads.open(book, index).use {
                it.materialize(chapters[2].href).file.writeText("Damaged")
            }
            val repair = requireNotNull(downloads.claim(requireNotNull(downloads.retry(book.id))))
            check(!downloads.complete(repair, chapters[2].href))
            access().use { NovelDownloader(downloads).run(repair, it) }
            downloads.open(book, index).use {
                check(it.materialize(chapters[2].href).file.readText().contains("Offline text"))
            }
            downloads.remove(book.id)
        } finally {
            downloads.close()
            context.deleteDatabase(db)
            check(root.deleteRecursively())
            if (inputs.exists()) check(inputs.deleteRecursively())
        }

        verifyLimits(context, root, db, book, index, chapters[0].href)
        return listOf(
            "novel_download_survives_process_store_reopen_and_total_cache_loss_without_network",
            "novel_download_preserves_illustrations_selected_chapters_and_cached_only_search",
            "novel_download_merges_selections_and_never_refetches_verified_completed_chapters",
            "novel_download_rejects_old_leases_and_never_regresses_resource_progress",
            "novel_download_pauses_resumes_and_preserves_verified_partial_files",
            "novel_download_recovers_corruption_and_protects_live_readers_and_foreign_files",
            "novel_download_checks_disk_headroom_and_content_limits_without_partial_publication",
        )
    }

    private suspend fun verifyLimits(
        context: Context,
        root: File,
        name: String,
        book: NovelBook,
        doc: NovelOnlineDocument,
        href: String,
    ) {
        val file =
            File(context.cacheDir, "download-limit-input").apply {
                writeText("Synthetic chapter resource")
            }
        val value =
            NovelOnlineFile(file, MediaType.HTML, NovelOnlineIdentity.digest(file.readBytes()))
        try {
            for (
            (bytes, reserve, expected) in
            listOf(
                Triple(1L, 0L, NovelDownloadProblem.LIMIT),
                Triple(1024L, Long.MAX_VALUE, NovelDownloadProblem.SPACE),
            )
            ) {
                val store =
                    NovelDownloadStore(
                        context,
                        root,
                        name,
                        byteLimit = bytes,
                        diskReserve = reserve,
                    )
                try {
                    val plan =
                        store.enqueue(
                            NovelDownloadPlan(book = book, document = doc, selected = listOf(href)),
                        )
                    val lease = requireNotNull(store.claim(plan.id))
                    check(
                        (
                            runCatching { store.save(lease, href, value) }.exceptionOrNull()
                                as? NovelDownloadException
                            )
                            ?.problem == expected,
                    )
                    check(store.progress(book.id)?.savedBytes == 0L)
                    store.remove(book.id)
                } finally {
                    store.close()
                    context.deleteDatabase(name)
                    if (root.exists()) check(root.deleteRecursively())
                }
            }
        } finally {
            check(file.delete())
        }
    }
}
