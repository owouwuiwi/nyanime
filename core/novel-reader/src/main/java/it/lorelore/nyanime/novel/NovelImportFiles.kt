package it.lorelore.nyanime.novel

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipFile

/** Owns only newly staged files. Existing books and provider permissions are never modified. */
class NovelImportFiles(private val directory: File) {
    companion object {
        const val MAX_INPUT_BYTES = 1024L * 1024 * 1024
        private const val MAX_ENTRIES = 20_000
        private const val MAX_EXPANDED_BYTES = 2L * 1024 * 1024 * 1024
        private const val MAX_ENTRY_BYTES = 32L * 1024 * 1024
    }

    class Staged internal constructor(val file: File, val sha256: String, val bytes: Long) :
        AutoCloseable {
        override fun close() {
            if (file.exists() && !file.delete()) throw IOException("Cannot remove pending book")
        }
    }

    data class EpubInspection(
        val entries: Set<String>,
        val expandedBytes: Long,
        val hasEncryption: Boolean,
    )

    fun copy(
        input: InputStream,
        checkpoint: () -> Unit = {},
        onProgress: (Long) -> Unit = {},
    ): Staged = input.use { source ->
        check(directory.isDirectory || directory.mkdirs()) {
            "Cannot create the novel import directory"
        }
        val pending = File(directory, "${UUID.randomUUID()}.pending")
        check(pending.createNewFile())
        val fingerprint = MessageDigest.getInstance("SHA-256")
        var count = 0L
        try {
            pending.outputStream().use { destination ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    checkpoint()
                    val read = source.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    count += read
                    if (count > MAX_INPUT_BYTES) {
                        throw IOException("Book exceeds the import size limit")
                    }
                    destination.write(buffer, 0, read)
                    fingerprint.update(buffer, 0, read)
                    onProgress(count)
                }
                destination.fd.sync()
            }
            checkpoint()
            require(count > 0) { "Empty document" }
            check(pending.length() == count)
            Staged(pending, fingerprint.digest().joinToString("") { "%02x".format(it) }, count)
        } catch (error: Throwable) {
            pending.delete()
            throw error
        }
    }

    /** Reads at most one bounded buffer, even for large or deceptive archives. */
    fun inspectEpub(
        file: File,
        checkpoint: () -> Unit = {},
        onProgress: (Long) -> Unit = {},
    ): EpubInspection {
        require(file.isFile && file.length() in 1..MAX_INPUT_BYTES)
        val names = linkedSetOf<String>()
        var total = 0L
        ZipFile(file).use { zip ->
            val entries = zip.entries()
            val buffer = ByteArray(64 * 1024)
            while (entries.hasMoreElements()) {
                checkpoint()
                val entry = entries.nextElement()
                if (names.size >= MAX_ENTRIES) throw IOException("EPUB contains too many resources")
                val name = entry.name
                require(safeArchivePath(name)) { "Unsafe EPUB resource path" }
                require(names.add(name)) { "Duplicate EPUB resource" }
                if (entry.isDirectory) continue
                require(entry.size <= MAX_ENTRY_BYTES) { "EPUB resource exceeds its size limit" }
                var actual = 0L
                val crc = CRC32()
                zip.getInputStream(entry).use { stream ->
                    while (true) {
                        checkpoint()
                        val read = stream.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        actual += read
                        total += read
                        crc.update(buffer, 0, read)
                        if (actual > MAX_ENTRY_BYTES || total > MAX_EXPANDED_BYTES) {
                            throw IOException("EPUB expands beyond the resource budget")
                        }
                        onProgress(total)
                    }
                }
                require(entry.size < 0 || actual == entry.size) {
                    "EPUB resource length differs from its declaration"
                }
                require(entry.crc == crc.value) {
                    "EPUB resource checksum differs from its declaration"
                }
            }
            require("META-INF/container.xml" in names && "mimetype" in names) {
                "Missing EPUB container"
            }
            val mimeEntry = zip.getEntry("mimetype")
            require(mimeEntry.size in 1..64)
            val mime =
                zip.getInputStream(mimeEntry).use {
                    it.readBytes().toString(Charsets.US_ASCII).trim()
                }
            require(mime == "application/epub+zip") { "Unsupported publication format" }
        }
        return EpubInspection(names, total, "META-INF/encryption.xml" in names)
    }

    internal fun safeArchivePath(value: String): Boolean {
        if (
            value.isBlank() ||
            value.length > 2048 ||
            value.any { it == '\\' || it == ':' || it.code < 32 }
        ) {
            return false
        }
        val decoded =
            runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrNull()
                ?: return false
        return listOf(value, decoded).all { path ->
            !path.startsWith('/') &&
                path.none { it == '\\' || it == ':' || it.code < 32 } &&
                path.trimEnd('/').split('/').none { it.isEmpty() || it == "." || it == ".." }
        }
    }
}
