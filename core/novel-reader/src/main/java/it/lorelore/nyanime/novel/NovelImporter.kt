package it.lorelore.nyanime.novel

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.getOrElse
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.UUID
import kotlin.coroutines.coroutineContext

/**
 * Verifies before publishing a private copy; never changes an external document or an existing
 * book.
 */
class NovelImporter(
    private val root: File,
    private val store: NovelStore,
    private val publications: NovelPublications,
) {
    enum class Phase {
        COPYING,
        CONVERTING,
        VERIFYING,
        SAVING,
    }

    data class Progress(val phase: Phase, val bytes: Long = 0L)

    private val imports = Mutex()

    class DifferentDocumentException :
        IOException("The selected document is not this book's original")

    /**
     * Opens on IO inside the ownership scope, including cancellation before a dispatcher return.
     */
    suspend fun import(
        openInput: () -> InputStream,
        format: NovelFormat,
        displayName: String,
        encoding: Charset? = null,
        partTitle: (Int) -> String,
        expectedId: String? = null,
        onProgress: (Progress) -> Unit = {},
    ): NovelBook =
        withContext(Dispatchers.IO) {
            import(openInput(), format, displayName, encoding, partTitle, expectedId, onProgress)
        }

    suspend fun import(
        input: InputStream,
        format: NovelFormat,
        displayName: String,
        encoding: Charset? = null,
        partTitle: (Int) -> String,
        expectedId: String? = null,
        onProgress: (Progress) -> Unit = {},
    ): NovelBook = input.use { source ->
        require(format == NovelFormat.EPUB || format == NovelFormat.TXT)
        withContext(Dispatchers.IO) {
            imports.withLock {
                val job = coroutineContext
                val checkpoint = { job.ensureActive() }
                val pendingRoot = File(root, "pending").apply { check(isDirectory || mkdirs()) }
                val pending =
                    File(pendingRoot, UUID.randomUUID().toString()).apply { check(mkdir()) }
                val files = NovelImportFiles(pending)
                val owned = mutableListOf<File>()
                var destination: File? = null
                var committed = false
                try {
                    onProgress(Progress(Phase.COPYING))
                    files
                        .copy(source, checkpoint) { onProgress(Progress(Phase.COPYING, it)) }
                        .use { staged ->
                            val id = NovelIdentity.local(staged.sha256)
                            if (expectedId != null && expectedId != id) {
                                throw DifferentDocumentException()
                            }
                            val existing = store.get(id)
                            if (
                                existing != null &&
                                existing.publicationFile?.let { File(root, it).isFile } ==
                                true &&
                                (
                                    format != NovelFormat.TXT ||
                                        encoding == null ||
                                        existing.textEncoding == encoding.name()
                                    )
                            ) {
                                store.setFavorite(id, true)
                                return@withLock existing.copy(favorite = true)
                            }
                            val suffix = if (format == NovelFormat.TXT) "txt" else "epub"
                            val original = File(pending, "original.$suffix").also { owned += it }
                            check(staged.file.renameTo(original)) {
                                "Cannot stage the original document"
                            }
                            val leafName =
                                displayName.substringAfterLast('/').substringAfterLast('\\')
                            val title =
                                leafName
                                    .substringBeforeLast('.', leafName)
                                    .trim()
                                    .take(2048)
                                    .ifBlank { "Untitled" }
                            val prefix = "books/$id/${pending.name}"
                            var selectedEncoding: Charset? = null
                            val readable =
                                if (format == NovelFormat.TXT) {
                                    onProgress(Progress(Phase.CONVERTING))
                                    val guess =
                                        original.inputStream().use { stream ->
                                            val prefix = ByteArray(64 * 1024)
                                            var count = 0
                                            while (count < prefix.size) {
                                                checkpoint()
                                                val read =
                                                    stream.read(prefix, count, prefix.size - count)
                                                if (read < 0) break
                                                count += read
                                            }
                                            NovelTextEncoding.detect(
                                                prefix.copyOf(count),
                                                complete = original.length() <= count,
                                            )
                                        }
                                    selectedEncoding = encoding ?: guess.charset
                                    val decoder =
                                        selectedEncoding
                                            .newDecoder()
                                            .onMalformedInput(CodingErrorAction.REPORT)
                                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                                    val converted =
                                        File(pending, "publication.epub").also { owned += it }
                                    original.inputStream().use { stream ->
                                        // Skip only a BOM belonging to this selected encoding.
                                        if (guess.charset == selectedEncoding) {
                                            repeat(guess.bomBytes) { check(stream.read() >= 0) }
                                        }
                                        InputStreamReader(stream, decoder).use { reader ->
                                            converted.outputStream().use { output ->
                                                val loan =
                                                    object : FilterOutputStream(output) {
                                                        override fun close() = flush()
                                                    }
                                                TextPublicationCompiler.compile(
                                                    reader,
                                                    loan,
                                                    title,
                                                    id,
                                                    partTitle,
                                                    checkpoint,
                                                )
                                                output.fd.sync()
                                            }
                                        }
                                    }
                                    converted
                                } else {
                                    original
                                }
                            checkpoint()
                            onProgress(Progress(Phase.VERIFYING))
                            files.inspectEpub(readable, checkpoint)
                            val publication = publications.open(readable)
                            val book =
                                try {
                                    val cover = File(pending, "cover.png").also { owned += it }
                                    extractCover(publication, cover)
                                    val now = System.currentTimeMillis()
                                    NovelBook(
                                        id = id,
                                        title =
                                        publication.metadata.title
                                            ?.trim()
                                            ?.take(2048)
                                            ?.ifBlank { title } ?: title,
                                        authors =
                                        publication.metadata.authors.take(100).map {
                                            it.name.take(1024)
                                        },
                                        description =
                                        publication.metadata.description.orEmpty().take(65536),
                                        format = format,
                                        contentVersion =
                                        staged.sha256 +
                                            (
                                                selectedEncoding?.let { ":txt-v1:${it.name()}" }
                                                    ?: ""
                                                ),
                                        originalFile = "$prefix/${original.name}",
                                        publicationFile = "$prefix/${readable.name}",
                                        coverFile =
                                        cover.name
                                            .takeIf { cover.isFile }
                                            ?.let { "$prefix/$it" },
                                        textEncoding = selectedEncoding?.name(),
                                        importedAt = existing?.importedAt ?: now,
                                        modifiedAt = now,
                                    )
                                } finally {
                                    publication.close()
                                }
                            checkpoint()
                            onProgress(Progress(Phase.SAVING))
                            // Do not let cancellation remove files after the database has committed
                            // them.
                            withContext(NonCancellable) {
                                val books =
                                    File(root, "books").apply { check(isDirectory || mkdirs()) }
                                val editions =
                                    File(books, id).apply { check(isDirectory || mkdirs()) }
                                val target = File(editions, pending.name)
                                check(!target.exists())
                                check(pending.renameTo(target)) {
                                    "Cannot publish the verified document"
                                }
                                destination = target
                                store.put(book)
                                committed = true
                            }
                            book
                        }
                } finally {
                    if (!committed) {
                        val owner = destination ?: pending
                        // Only these generated names belong to this invocation. Never recurse
                        // through imported paths.
                        owned.forEach { File(owner, it.name).delete() }
                        pending
                            .listFiles()
                            ?.filter { it.name.endsWith(".pending") }
                            ?.forEach { it.delete() }
                        owner.delete()
                    }
                }
            }
        }
    }

    private suspend fun extractCover(publication: Publication, file: File): Boolean {
        val link =
            (publication.resources + publication.readingOrder).firstOrNull {
                "cover" in it.rels &&
                    it.mediaType?.type == "image" &&
                    it.mediaType?.subtype != "svg+xml"
            } ?: return false
        val resource = publication.get(link) ?: return false
        val bytes =
            try {
                resource.read(0L..8L * 1024 * 1024).getOrElse {
                    return false
                }
            } finally {
                resource.close()
            }
        if (bytes.size > 8 * 1024 * 1024) return false
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return false
        options.inSampleSize = 1
        while (
            options.outWidth / options.inSampleSize > 800 ||
            options.outHeight / options.inSampleSize > 1200
        ) {
            options.inSampleSize *= 2
        }
        options.inJustDecodeBounds = false
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return false
        try {
            file.outputStream().use {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) {
                    throw IOException("Cannot save book cover")
                }
                it.fd.sync()
            }
        } finally {
            bitmap.recycle()
        }
        return true
    }
}
