package it.lorelore.nyanime.novel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** Only the chapter index is durable here. Chapter bodies and sessions have different owners. */
class NovelOnlineIndexes(private val root: File) {
    private val writes = Mutex()

    suspend fun read(origin: NovelOrigin): NovelOnlineDocument? =
        withContext(Dispatchers.IO) {
            writes.withLock {
                val file = target(origin)
                if (!file.isFile) return@withLock null
                if (file.length() !in 1..MAX_BYTES) throw IOException("Invalid novel chapter index")
                val bytes =
                    file.inputStream().use { input ->
                        ByteArrayOutputStream().use { output ->
                            val buffer = ByteArray(16 * 1024)
                            while (true) {
                                coroutineContext.ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (output.size().toLong() + count > MAX_BYTES) {
                                    throw IOException("Invalid novel chapter index")
                                }
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        }
                    }
                val document =
                    NovelJson.decodeFromString<NovelOnlineDocument>(bytes.toString(Charsets.UTF_8))
                require(document.origin == origin)
                document
            }
        }

    suspend fun write(document: NovelOnlineDocument): String =
        withContext(Dispatchers.IO) {
            writes.withLock {
                val bytes = NovelJson.encodeToString(document).toByteArray(Charsets.UTF_8)
                require(bytes.size <= MAX_BYTES)
                val destination = target(document.origin)
                check(destination.parentFile!!.isDirectory || destination.parentFile!!.mkdirs())
                val pending =
                    File(destination.parentFile, UUID.randomUUID().toString() + ".pending")
                try {
                    pending.outputStream().use {
                        it.write(bytes)
                        it.fd.sync()
                    }
                    coroutineContext.ensureActive()
                    // Same directory/filesystem: readers see either the previous or the complete
                    // index.
                    Files.move(
                        pending.toPath(),
                        destination.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } finally {
                    if (pending.exists()) check(pending.delete())
                }
                destination.relativeTo(root.canonicalFile).invariantSeparatorsPath
            }
        }

    private fun target(origin: NovelOrigin): File {
        val owner = NovelIdentity.remote(origin.sourceId, origin.reference)
        val parent = root.canonicalFile
        return File(parent, "books/$owner/online/index.json").canonicalFile.also {
            require(it.toPath().startsWith(parent.toPath()))
        }
    }

    private companion object {
        const val MAX_BYTES = 16L * 1024 * 1024
    }
}
