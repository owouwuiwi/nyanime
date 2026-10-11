package it.lorelore.nyanime.novel

import kotlinx.serialization.Serializable
import org.jsoup.Jsoup
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/** Installation-only state. Neither resource paths nor work leases belong to metadata sync. */
@Serializable
enum class NovelDownloadState {
    QUEUED,
    RUNNING,
    PAUSED,
    FAILED,
    READY,
}

@Serializable
data class NovelDownloadPlan(
    val id: String = UUID.randomUUID().toString(),
    val book: NovelBook,
    val document: NovelOnlineDocument,
    val selected: List<String>,
) {
    init {
        require(UUID.fromString(id).toString() == id)
        require(book.format == NovelFormat.ONLINE && book.origin == document.origin)
        require(book.id == document.bookId)
        require(book.contentVersion == NovelOnlineIdentity.edition(document.origin))
        require(selected.isNotEmpty() && selected.size <= document.chapters.size)
        require(selected.distinct().size == selected.size)
        val declared = document.chapters.mapTo(hashSetOf()) { it.href }
        require(selected.all { it in declared })
    }
}

data class NovelDownloadProgress(
    val id: String,
    val bookId: String,
    val state: NovelDownloadState,
    val selectedChapters: Int,
    val completedChapters: Int,
    val savedBytes: Long,
    val activeChapter: String? = null,
    val activeResources: Int = 0,
    val activeTotalResources: Int = 0,
    val problem: NovelDownloadProblem? = null,
) {
    init {
        require(completedChapters in 0..selectedChapters && savedBytes >= 0)
        require(activeResources in 0..activeTotalResources)
    }
}

enum class NovelDownloadProblem {
    SPACE,
    LIMIT,
    SOURCE,
    STORAGE,
    DAMAGED,
}

data class NovelDownloadSummary(val title: String, val progress: NovelDownloadProgress)

class NovelDownloadException(val problem: NovelDownloadProblem) : IOException(problem.name)

/** Structural checks only: there are no website URLs, selectors or provider rules here. */
object NovelDownloadPolicy {
    const val MAX_RESOURCE_BYTES = 8L * 1024 * 1024
    const val MAX_BOOK_BYTES = 2L * 1024 * 1024 * 1024
    const val MAX_RESOURCES = 20_000
    const val DISK_RESERVE = 32L * 1024 * 1024
    private val chapter = Regex("chapters/[a-f0-9]{64}\\.xhtml")
    private val image = Regex("assets/[a-f0-9]{64}\\.image")

    fun validHref(href: String): Boolean = chapter.matches(href) || image.matches(href)

    fun chapterHref(href: String): Boolean = chapter.matches(href)

    fun images(bytes: ByteArray): List<String> {
        require(bytes.size in 1..NovelHtmlPolicy.MAX_HTML_BYTES)
        val document = Jsoup.parse(bytes.toString(Charsets.UTF_8))
        val result = linkedSetOf<String>()
        document.select("img[src]").forEach {
            val src = it.attr("src")
            require(src.startsWith("../") && image.matches(src.removePrefix("../"))) {
                "Undeclared download illustration"
            }
            result += src.removePrefix("../")
        }
        require(result.size <= 200)
        return result.toList()
    }

    fun objectName(href: String, hash: String): String {
        require(validHref(href) && hash.matches(Regex("[a-f0-9]{64}")))
        return NovelOnlineIdentity.digest(href) + "-" + hash + ".bin"
    }

    fun owned(root: File, bookId: String, name: String): File {
        require(bookId.matches(Regex("b_[a-f0-9]{64}")))
        require(name.matches(Regex("[a-f0-9]{64}-[a-f0-9]{64}\\.bin|pending-[a-f0-9-]{36}")))
        val base = root.canonicalFile
        val file = File(base, "$bookId/$name").canonicalFile
        require(file.toPath().startsWith(base.toPath()) && file.parentFile?.name == bookId)
        return file
    }

    fun digest(file: File, interrupted: () -> Unit = {}): String {
        require(file.length() in 1..MAX_RESOURCE_BYTES)
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                interrupted()
                val count = input.read(buffer)
                if (count < 0) break
                hash.update(buffer, 0, count)
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
}
