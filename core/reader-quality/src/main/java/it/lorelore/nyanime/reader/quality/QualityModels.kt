package it.lorelore.nyanime.reader.quality

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext

data class QualityModelFile(
    val name: String,
    val size: Long,
    val sha256: String,
    val url: String? = null,
    val archiveEntry: String? = null,
) {
    init {
        require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")))
        require(size in 1..64L * 1024 * 1024 && sha256.matches(Regex("[a-f0-9]{64}")))
    }
}
data class QualityModel(
    val engine: QualityEngineId,
    val revision: String,
    val license: String,
    val files: List<QualityModelFile>,
    val archiveUrl: String? = null,
    val archiveSize: Long = 0,
    val archiveSha256: String? = null,
) {
    init {
        require(revision.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")))
        require(files.size in 1..4 && files.map { it.name }.distinct().size == files.size)
        require(archiveUrl == null || (archiveSize in 1..128L * 1024 * 1024 && archiveSha256 != null))
    }
    val downloadBytes get() = if (archiveUrl == null) files.sumOf { it.size } else archiveSize
    val fingerprint get() = files.joinToString(":") { it.sha256 }
}

/** Fixed, independently licensed models. This is not a content source or a source catalogue. */
object QualityModels {
    private const val CUGAN = "https://raw.githubusercontent.com/nihui/realcugan-ncnn-vulkan/" +
        "395302c5c70f1bff604c974e92e0a87e45c9f9ee/models/models-nose/up2x-no-denoise"
    val cugan = QualityModel(
        QualityEngineId.REAL_CUGAN,
        "nose-x2-395302c5",
        "MIT",
        listOf(
            QualityModelFile(
                "model.param",
                3309,
                "c370b70321d45301b9c682121130ad167b3dd1bc8e122c1d77c7ac44f1943708",
                "$CUGAN.param",
            ),
            QualityModelFile(
                "model.bin",
                2551408,
                "2b6f4db8fdc04336ac68ba954b4cd3b280beb5e7b6b0bcd97f769accc512cf4a",
                "$CUGAN.bin",
            ),
        ),
    )
    val esrgan = QualityModel(
        QualityEngineId.REAL_ESRGAN,
        "animevideo-x2-20220424",
        "BSD-3-Clause / MIT",
        listOf(
            QualityModelFile(
                "model.param",
                3173,
                "b88ff4f00ebf019a7fdac17fdd45a7fd3665d37509efc5baf2e4da2e24420a04",
                archiveEntry = "models/realesr-animevideov3-x2.param",
            ),
            QualityModelFile(
                "model.bin",
                1247368,
                "548a36f9c3f4ab8da56cd3b13badf23968bee207b396dad14d04b830e5f2ab2d",
                archiveEntry = "models/realesr-animevideov3-x2.bin",
            ),
        ),
        archiveUrl = "https://github.com/xinntao/Real-ESRGAN/releases/download/" +
            "v0.2.5.0/realesrgan-ncnn-vulkan-20220424-windows.zip",
        archiveSize = 45474481,
        archiveSha256 = "abc02804e17982a3be33675e4d471e91ea374e65b70167abc09e31acb412802d",
    )
    fun get(engine: QualityEngineId): QualityModel? = when (engine) {
        QualityEngineId.REAL_CUGAN -> cugan
        QualityEngineId.REAL_ESRGAN -> esrgan
        else -> null
    }
}

/** The host supplies a cancellable transport; no networking occurs on construction or lookup. */
fun interface QualityModelDownloader {
    suspend fun download(url: String, destination: File, maxBytes: Long, progress: (Long) -> Unit)
}

/** Verified, atomic publication. Partial downloads and extracted desktop binaries are never exposed. */
class QualityModelStore(private val root: File) {
    private val mutation = Mutex()
    fun directory(model: QualityModel) = File(root, model.revision)
    fun available(model: QualityModel): Boolean = model.files.all { matches(File(directory(model), it.name), it) }

    suspend fun install(model: QualityModel, transport: QualityModelDownloader, progress: (Long, Long) -> Unit) =
        mutation.withLock {
            withContext(Dispatchers.IO) {
                if (available(model)) return@withContext
                check(root.exists() || root.mkdirs()) { "Cannot prepare model storage" }
                check(root.usableSpace > model.downloadBytes + model.files.sumOf { it.size } + 4L * 1024 * 1024) {
                    "Insufficient model storage"
                }
                val staging = File(root, ".download-${UUID.randomUUID()}")
                check(staging.mkdir())
                try {
                    if (model.archiveUrl != null) {
                        val archive = File(staging, "download.zip")
                        transport.download(model.archiveUrl, archive, model.archiveSize) {
                            progress(it, model.downloadBytes)
                        }
                        check(archive.length() == model.archiveSize && hash(archive) == model.archiveSha256) {
                            "Model archive verification failed"
                        }
                        ZipFile(archive).use { zip ->
                            for (file in model.files) {
                                coroutineContext.ensureActive()
                                val entry = zip.getEntry(requireNotNull(file.archiveEntry))
                                    ?: error("Model archive incomplete")
                                check(entry.size == file.size)
                                // Read only the two declared entries to fixed filenames, never archive paths.
                                zip.getInputStream(entry).use { input ->
                                    File(staging, file.name).outputStream().use { output ->
                                        val buffer = ByteArray(8192)
                                        var copied = 0L
                                        while (true) {
                                            coroutineContext.ensureActive()
                                            val count = input.read(buffer)
                                            if (count < 0) break
                                            copied += count
                                            check(copied <= file.size)
                                            output.write(buffer, 0, count)
                                        }
                                    }
                                }
                            }
                        }
                        check(archive.delete())
                    } else {
                        var completed = 0L
                        for (file in model.files) {
                            transport.download(requireNotNull(file.url), File(staging, file.name), file.size) {
                                progress(completed + it, model.downloadBytes)
                            }
                            completed += file.size
                        }
                    }
                    coroutineContext.ensureActive()
                    check(model.files.all { matches(File(staging, it.name), it) }) { "Model verification failed" }
                    val destination = directory(model)
                    if (destination.exists()) check(destination.deleteRecursively())
                    check(staging.renameTo(destination)) { "Cannot publish model" }
                    progress(model.downloadBytes, model.downloadBytes)
                } finally {
                    staging.deleteRecursively()
                }
            }
        }

    suspend fun remove(model: QualityModel) = mutation.withLock {
        withContext(Dispatchers.IO) { check(!directory(model).exists() || directory(model).deleteRecursively()) }
    }

    private fun matches(file: File, expected: QualityModelFile) =
        file.isFile &&
            file.length() == expected.size &&
            runCatching { hash(file) == expected.sha256 }.getOrDefault(false)

    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(8192)
            while (true) {
                val count = input.read(bytes)
                if (count < 0) break
                digest.update(bytes, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
