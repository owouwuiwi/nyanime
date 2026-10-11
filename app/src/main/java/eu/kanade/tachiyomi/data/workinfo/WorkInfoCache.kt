package eu.kanade.tachiyomi.data.workinfo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class WorkCacheEntry(val savedAt: Long, val payload: String) {
    fun fresh(now: Long) = now - savedAt in 0 until 24 * 60 * 60 * 1000L
}

interface WorkInfoCache {
    suspend fun read(key: String): WorkCacheEntry?
    suspend fun write(key: String, entry: WorkCacheEntry)
}

/** Rebuildable public data only. Neither source URLs nor manual associations are stored here. */
class FileWorkInfoCache(private val directory: File, private val json: Json) : WorkInfoCache {
    override suspend fun read(key: String): WorkCacheEntry? = withContext(Dispatchers.IO) {
        runCatching {
            val file = file(key)
            if (!file.isFile || file.length() > MAX_ENTRY_BYTES) return@runCatching null
            json.decodeFromString<WorkCacheEntry>(file.readText())
        }.getOrNull()
    }

    override suspend fun write(key: String, entry: WorkCacheEntry) = withContext(Dispatchers.IO) {
        runCatching {
            directory.mkdirs()
            val encoded = json.encodeToString(entry)
            if (encoded.toByteArray().size > MAX_ENTRY_BYTES) return@runCatching
            val file = file(key)
            val temporary = File.createTempFile("entry-", ".tmp", directory)
            try {
                temporary.writeText(encoded)
                java.nio.file.Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                )
            } finally {
                temporary.delete()
            }
            // Small bounded cache; oldest entries are expendable, settings and source data are not.
            var bytes = 0L
            directory.listFiles { candidate -> candidate.extension == "json" }.orEmpty()
                .sortedByDescending(File::lastModified).forEachIndexed { index, candidate ->
                    bytes += candidate.length()
                    if (index >= 256 || bytes > 32 * 1024 * 1024L) candidate.delete()
                }
        }
        Unit
    }

    private fun file(key: String) = File(directory, "${WorkInfoPreferences.digest(key)}.json")

    companion object {
        private const val MAX_ENTRY_BYTES = 2 * 1024 * 1024
    }
}
