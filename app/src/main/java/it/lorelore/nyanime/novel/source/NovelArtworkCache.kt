package it.lorelore.nyanime.novel.source

import it.lorelore.nyanime.novel.NovelOnlineIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** Disposable search artwork, independent of Coil memory and of durable library ownership. */
internal class NovelArtworkCache(
    directory: File,
    private val byteLimit: Long = 32L * 1024 * 1024,
    private val entryLimit: Int = 128,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val root = directory.canonicalFile
    private val writes = Mutex()

    init {
        require(byteLimit in 1..128L * 1024 * 1024 && entryLimit in 1..256)
    }

    suspend fun read(key: String): ByteArray? =
        withContext(Dispatchers.IO) {
            require(key.matches(KEY))
            writes.withLock {
                val matches =
                    files()
                        .filter { it.name.startsWith("$key-") }
                        .sortedByDescending { it.lastModified() }
                for (file in matches) {
                    coroutineContext.ensureActive()
                    val bytes =
                        if (file.length() in 1..itemLimit()) {
                            file.inputStream().use { input ->
                                ByteArrayOutputStream().use { output ->
                                    val buffer = ByteArray(16 * 1024)
                                    while (true) {
                                        coroutineContext.ensureActive()
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        if (output.size().toLong() + count > itemLimit()) break
                                        output.write(buffer, 0, count)
                                    }
                                    output.toByteArray()
                                }
                            }
                        } else {
                            null
                        }
                    if (
                        bytes != null &&
                        bytes.size.toLong() == file.length() &&
                        file.name == "$key-${NovelOnlineIdentity.digest(bytes)}.image"
                    ) {
                        file.setLastModified(clock())
                        return@withLock bytes
                    }
                    check(file.delete())
                }
                null
            }
        }

    suspend fun write(key: String, bytes: ByteArray) =
        withContext(Dispatchers.IO) {
            require(key.matches(KEY) && bytes.size.toLong() in 1..itemLimit())
            writes.withLock {
                check(root.isDirectory || root.mkdirs())
                val destination = File(root, "$key-${NovelOnlineIdentity.digest(bytes)}.image")
                val pending = File(root, "pending-${UUID.randomUUID()}")
                try {
                    pending.outputStream().use { output ->
                        output.write(bytes)
                        output.fd.sync()
                    }
                    coroutineContext.ensureActive()
                    Files.move(
                        pending.toPath(),
                        destination.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                    destination.setLastModified(clock())
                    val entries = files().sortedByDescending { it.lastModified() }
                    var keptBytes = destination.length()
                    var keptCount = 1
                    for (file in entries) {
                        if (file == destination) continue
                        if (
                            file.name.startsWith("$key-") ||
                            keptCount >= entryLimit ||
                            keptBytes + file.length() > byteLimit
                        ) {
                            check(file.delete())
                        } else {
                            keptBytes += file.length()
                            keptCount++
                        }
                    }
                } finally {
                    if (pending.exists()) check(pending.delete())
                }
            }
        }

    private fun files(): List<File> =
        root.listFiles().orEmpty().filter {
            val owned = it.isFile && it.canonicalFile.parentFile == root
            if (owned && it.name.matches(PENDING)) check(it.delete())
            owned && it.name.matches(FILE)
        }

    private fun itemLimit() = minOf(byteLimit, 8L * 1024 * 1024)

    companion object {
        private val KEY = Regex("[a-f0-9]{64}")
        private val FILE = Regex("[a-f0-9]{64}-[a-f0-9]{64}\\.image")
        private val PENDING =
            Regex("pending-[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")

        fun key(sourceId: Long, bookReference: String, coverReference: String): String =
            NovelOnlineIdentity.digest(
                "$sourceId:" +
                    NovelOnlineIdentity.digest(bookReference) +
                    ":" +
                    NovelOnlineIdentity.digest(coverReference),
            )
    }
}
