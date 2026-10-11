package it.lorelore.nyanime.novel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Durable copies win; missing chapters may use the existing source-mediated reader cache. */
class NovelDownloadAccess(
    private val durable: NovelOnlineAccess,
    private val fallback: NovelOnlineAccess,
) : NovelOnlineAccess {
    init {
        require(durable.document == fallback.document)
    }

    override val document = durable.document
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val closed = AtomicBoolean(false)
    private val imageChapters = linkedMapOf<String, String>()
    override val chapterVersions =
        combine(durable.chapterVersions, fallback.chapterVersions) { saved, temporary ->
            temporary + saved
        }
            .stateIn(
                scope,
                SharingStarted.Eagerly,
                fallback.chapterVersions.value + durable.chapterVersions.value,
            )

    override fun contains(href: String) =
        !closed.get() && (durable.contains(href) || fallback.contains(href))

    override fun retain(href: String): AutoCloseable {
        check(!closed.get())
        val saved = if (durable.contains(href)) durable.retain(href) else null
        val temporary =
            try {
                fallback.retain(href)
            } catch (error: Throwable) {
                saved?.close()
                throw error
            }
        val released = AtomicBoolean(false)
        return AutoCloseable {
            if (!released.getAndSet(true)) {
                try {
                    saved?.close()
                } finally {
                    temporary.close()
                }
            }
        }
    }

    override suspend fun cachedOnly(): NovelOnlineAccess {
        val saved = durable.cachedOnly()
        return try {
            NovelDownloadAccess(saved, fallback.cachedOnly())
        } catch (error: Throwable) {
            saved.close()
            throw error
        }
    }

    override suspend fun materialize(href: String): NovelOnlineFile {
        check(!closed.get())
        val file =
            if (durable.contains(href)) {
                try {
                    durable.materialize(href)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: IOException) {
                    recover(href)
                }
            } else {
                fallback.materialize(href)
            }
        if (NovelDownloadPolicy.chapterHref(href)) {
            val images = NovelDownloadPolicy.images(file.file.readBytes())
            synchronized(imageChapters) {
                images.forEach { image ->
                    imageChapters.remove(image)
                    imageChapters[image] = href
                }
                while (imageChapters.size > 2048) imageChapters.remove(imageChapters.keys.first())
            }
        }
        return file
    }

    private suspend fun recover(href: String): NovelOnlineFile {
        if (!fallback.contains(href)) {
            val chapter =
                synchronized(imageChapters) { imageChapters[href] }
                    ?: throw IOException("Downloaded illustration is damaged")
            fallback.retain(chapter).use { fallback.materialize(chapter) }
        }
        return fallback.materialize(href)
    }

    override fun close() {
        if (closed.getAndSet(true)) return
        scope.cancel()
        try {
            durable.close()
        } finally {
            fallback.close()
        }
    }
}
