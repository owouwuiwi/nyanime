package eu.kanade.tachiyomi.data.tv

import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import nyanime.tv.api.TvCacheEntry
import nyanime.tv.api.TvSourceCache
import java.io.File
import java.security.MessageDigest

/** Each extension receives its own bounded namespace. All IO runs away from rendering. */
class TvDiskCache(private val directory: File) : TvSourceCache {
    private val mutex = locks.computeIfAbsent(directory.canonicalPath) { Mutex() }
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun get(key: String): TvCacheEntry? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = file(key)
            if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@withLock null
            runCatching {
                file.openRead().use { input ->
                    require(file.baseFile.length() <= ENTRY_LIMIT)
                    input.bufferedReader().use { json.decodeFromString<TvCacheEntry>(it.readText()) }
                }
            }.getOrNull()?.also { file.baseFile.setLastModified(System.currentTimeMillis()) }
        }
    }

    override suspend fun put(key: String, entry: TvCacheEntry) = withContext(Dispatchers.IO) {
        require(entry.content.length <= ENTRY_LIMIT)
        val bytes = json.encodeToString(entry).toByteArray()
        require(bytes.size <= ENTRY_LIMIT)
        mutex.withLock {
            check(directory.isDirectory || directory.mkdirs())
            val file = file(key)
            val output = file.startWrite()
            try {
                output.write(bytes)
                file.finishWrite(output)
            } catch (failure: Throwable) {
                file.failWrite(output)
                throw failure
            }
            val entries = directory.listFiles().orEmpty().filter { it.isFile && it.extension == "json" }
                .sortedBy { it.lastModified() }
            var bytesOnDisk = entries.sumOf { it.length() }
            for (old in entries) {
                if (bytesOnDisk <= TOTAL_LIMIT) break
                if (old == file.baseFile) continue
                val size = old.length()
                if (old.delete()) bytesOnDisk -= size
            }
        }
    }

    private fun file(key: String): AtomicFile {
        require(key.isNotEmpty() && key.length <= 2048)
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return AtomicFile(File(directory, "$digest.json"))
    }

    companion object {
        private val locks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
        private const val ENTRY_LIMIT = 16 * 1024 * 1024L
        private const val TOTAL_LIMIT = 64 * 1024 * 1024L
    }
}
