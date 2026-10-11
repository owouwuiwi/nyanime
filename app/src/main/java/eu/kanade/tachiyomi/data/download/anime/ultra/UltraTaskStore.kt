package eu.kanade.tachiyomi.data.download.anime.ultra

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

@Serializable
enum class UltraPhase { AVAILABLE, QUEUED, RUNNING, WAITING, PAUSED, READY, FAILED, CANCELLED }

@Serializable
data class UltraTask(
    val folder: String,
    val title: String,
    val animeId: Long = -1,
    val episodeId: Long = -1,
    val phase: UltraPhase = UltraPhase.AVAILABLE,
    val progress: Int = 0,
    val message: String = "Originale scaricato",
    val workId: String = "",
    val updatedAt: Long = 0,
    val coolingUntil: Long = 0,
    val coolingRequired: Boolean = false,
) {
    val key: String get() = key(folder)
    val active: Boolean get() = phase in setOf(UltraPhase.QUEUED, UltraPhase.RUNNING, UltraPhase.WAITING)

    companion object {
        fun key(folder: String): String = MessageDigest.getInstance("SHA-256")
            .digest(folder.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

/** Durable UI state; WorkManager history can be pruned, the downloaded file cannot. IO only. */
internal class UltraTaskStore(private val file: File) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private var loaded = false
    private val mutable = MutableStateFlow<Map<String, UltraTask>>(emptyMap())
    val tasks = mutable.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) { mutex.withLock { read() } }

    suspend fun change(key: String, transform: (UltraTask?) -> UltraTask?) = withContext(Dispatchers.IO) {
        mutex.withLock {
            read()
            val old = mutable.value[key]
            val next = transform(old)
            if (next == old) return@withLock
            val values = mutable.value.toMutableMap()
            if (next == null) values.remove(key) else values[key] = next
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, "${file.name}.new")
            FileOutputStream(temporary).use {
                it.write(json.encodeToString(values.values.toList()).toByteArray())
                it.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            mutable.value = values
        }
    }

    suspend fun updateWorker(key: String, workId: String, transform: (UltraTask) -> UltraTask) = change(key) {
        if (it?.workId == workId && it.active) transform(it) else it
    }

    /** Call only after the completed file and marker are verified, including a late pause/cancel. */
    suspend fun recordPublished(key: String, workId: String) = change(key) {
        if (it?.workId == workId && it.phase != UltraPhase.READY) {
            it.copy(phase = UltraPhase.READY, progress = 100, message = "Ultra pronto")
        } else {
            it
        }
    }

    private fun read() {
        if (loaded) return
        // Do not silently overwrite a damaged journal with an empty queue.
        val saved = if (file.exists()) json.decodeFromString<List<UltraTask>>(file.readText()) else emptyList()
        mutable.value = saved.associateBy { it.key }
        loaded = true
    }

    companion object {
        @Volatile private var instance: UltraTaskStore? = null
        fun get(context: Context): UltraTaskStore = instance ?: synchronized(this) {
            instance ?: UltraTaskStore(File(context.noBackupFilesDir, "ultra/tasks.json")).also { instance = it }
        }
    }
}
