package it.lorelore.nyanime.novel

import kotlinx.coroutines.ensureActive
import org.readium.r2.shared.publication.Publication
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/** A search snapshot has its own index edition; it never changes the book's reading identity. */
class NovelSearchScope
internal constructor(
    val book: NovelBook,
    val publication: Publication,
    val hrefs: Set<String>?,
    val limited: Boolean = false,
    val unavailable: Int = 0,
    private val owned: Boolean = false,
    private val pins: List<AutoCloseable> = emptyList(),
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.getAndSet(true)) {
            try {
                if (owned) publication.close()
            } finally {
                pins.asReversed().forEach { it.close() }
            }
        }
    }
}

internal suspend fun prepareNovelSearchScope(
    opened: NovelLibrary.Opened,
    byteBudget: Long,
    onProgress: suspend (NovelSearch.Progress) -> Unit,
): NovelSearchScope =
    acquireNovelResource(
        acquire = {
            val online = opened.online
            if (online == null) {
                return@acquireNovelResource NovelSearchScope(opened.book, opened.publication, null)
            }
            val cached = online.cachedOnly()
            val pins = mutableListOf<AutoCloseable>()
            var publication: Publication? = null
            var delegated = false
            try {
                val candidates =
                    cached.document.chapters.filter { it.href in cached.chapterVersions.value }
                val verified = linkedMapOf<String, String>()
                var bytes = 0L
                var failed = 0
                var limited = false
                onProgress(
                    NovelSearch.Progress(0, candidates.size, 0, phase = NovelSearch.Phase.PREPARING),
                )
                for ((index, chapter) in candidates.withIndex()) {
                    coroutineContext.ensureActive()
                    val pin = cached.retain(chapter.href)
                    var retained = false
                    try {
                        val resource = cached.materialize(chapter.href)
                        val size = resource.file.length()
                        if (size !in 1..byteBudget || bytes + size > byteBudget) {
                            limited = true
                            break
                        }
                        bytes += size
                        verified[chapter.href] = resource.sha256
                        pins += pin
                        retained = true
                    } catch (_: IOException) {
                        failed++
                    } finally {
                        if (!retained) pin.close()
                    }
                    onProgress(
                        NovelSearch.Progress(
                            index + 1,
                            candidates.size,
                            failed,
                            phase = NovelSearch.Phase.PREPARING,
                        ),
                    )
                }
                val fingerprint = verified.entries.joinToString("\n") { it.key + ":" + it.value }
                val searchBook =
                    opened.book.copy(
                        contentVersion =
                        "search-online-v1:" + NovelOnlineIdentity.digest(fingerprint),
                    )
                delegated = true
                val result = NovelOnlinePublication.open(searchBook, cached)
                publication = result
                NovelSearchScope(
                    searchBook,
                    result,
                    verified.keys.toSet(),
                    limited,
                    unavailable = failed,
                    owned = true,
                    pins = pins,
                )
            } catch (error: Throwable) {
                try {
                    if (publication != null) {
                        publication.close()
                    } else if (!delegated) {
                        cached.close()
                    }
                } finally {
                    pins.asReversed().forEach { it.close() }
                }
                throw error
            }
        },
        release = NovelSearchScope::close,
    )
