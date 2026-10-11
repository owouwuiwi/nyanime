package it.lorelore.nyanime.novel

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

/**
 * No HTTP, provider names or executable rules: all content goes through the existing access port.
 */
class NovelDownloader(private val downloads: NovelDownloadStore) {
    suspend fun run(
        lease: NovelDownloadStore.Lease,
        access: NovelOnlineAccess,
        onProgress: suspend (NovelDownloadProgress) -> Unit = {},
    ) {
        require(access.document == lease.plan.document)
        val callbacks = Mutex()
        suspend fun report() = callbacks.withLock {
            downloads.progress(lease.plan.book.id)?.let { onProgress(it) }
        }
        val chapters = lease.plan.document.chapters.associateBy { it.href }
        for (href in lease.plan.selected) {
            coroutineContext.ensureActive()
            if (downloads.complete(lease, href)) {
                report()
                continue
            }
            downloads.active(lease, href, 0, 1)
            report()
            require(href in chapters && access.contains(href))
            val images =
                access.retain(href).use {
                    val chapter = withTimeout(30_000) { access.materialize(href) }
                    val references = NovelDownloadPolicy.images(chapter.file.readBytes())
                    require(references.all(access::contains))
                    downloads.save(lease, href, chapter)
                    references
                }
            val completed = AtomicInteger(1)
            downloads.active(lease, href, 1, 1 + images.size)
            report()
            // Bound in-flight work and decoded buffers even for heavily illustrated chapters.
            for (pair in images.chunked(2)) {
                coroutineScope {
                    pair
                        .map { image ->
                            async {
                                if (!downloads.containsVerified(lease, image)) {
                                    access.retain(image).use {
                                        downloads.save(
                                            lease,
                                            image,
                                            withTimeout(30_000) { access.materialize(image) },
                                        )
                                    }
                                }
                                downloads.active(
                                    lease,
                                    href,
                                    completed.incrementAndGet(),
                                    1 + images.size,
                                )
                                report()
                            }
                        }
                        .awaitAll()
                }
            }
            downloads.complete(lease, href, images)
            report()
        }
        downloads.finish(lease)
        downloads.collect(lease.plan.book.id)
        report()
    }
}
