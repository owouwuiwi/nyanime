package eu.kanade.tachiyomi.data.migration

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@Serializable
data class MigrationFile(val path: String, val bytes: Long, val sha256: String)

@Serializable
data class MigrationManifest(
    val protocol: Int = 1,
    val packageName: String,
    val versionCode: Int,
    val storageUri: String?,
    val files: List<MigrationFile>,
) {
    val bytes get() = files.sumOf { it.bytes }
}

/** A closed set of roots, bounded extraction and per-file hashes; paths never come from IPC. */
object MigrationArchive {
    val roots = setOf("files", "databases", "preferences", "no_backup", "external", "webview", "portable", "secrets")
    private val json = Json { ignoreUnknownKeys = false }
    private const val MANIFEST = "manifest.json"
    private const val MAX_FILES = 250_000
    private const val MAX_MANIFEST = 64 * 1024 * 1024

    fun safePath(path: String): Boolean = path.length in 3..1024 &&
        '\\' !in path &&
        ':' !in path &&
        path.none { it.code < 32 } &&
        path.split('/').let { parts ->
            parts.size >= 2 && parts.first() in roots && parts.none { it.isBlank() || it == "." || it == ".." }
        }

    fun write(
        sources: Map<String, File>,
        output: OutputStream,
        packageName: String,
        versionCode: Int,
        storageUri: String?,
        include: (String) -> Boolean = { true },
        progress: (Long) -> Unit = {},
    ): MigrationManifest {
        require(sources.keys.all { it in roots })
        val records = mutableListOf<MigrationFile>()
        var completed = 0L
        ZipOutputStream(output).use { zip ->
            // Videos, RIN and scans are already compressed. Avoid heat and redundant CPU work.
            zip.setLevel(0)
            sources.forEach { (root, directory) ->
                if (!directory.isDirectory) return@forEach
                directory.walkTopDown().onEnter { !Files.isSymbolicLink(it.toPath()) }.filter(File::isFile)
                    .forEach fileLoop@{ file ->
                        check(!Files.isSymbolicLink(file.toPath()))
                        val path = "$root/${file.relativeTo(directory).invariantSeparatorsPath}"
                        require(safePath(path))
                        if (!include(path)) return@fileLoop
                        check(records.size < MAX_FILES)
                        val digest = MessageDigest.getInstance("SHA-256")
                        zip.putNextEntry(ZipEntry(path))
                        var count = 0L
                        file.inputStream().use { input ->
                            val buffer = ByteArray(256 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                zip.write(buffer, 0, read)
                                digest.update(buffer, 0, read)
                                count += read
                                completed += read
                                progress(completed)
                            }
                        }
                        zip.closeEntry()
                        records += MigrationFile(path, count, digest.digest().hex())
                    }
            }
            val manifest = MigrationManifest(1, packageName, versionCode, storageUri, records)
            val encoded = json.encodeToString(manifest).toByteArray()
            require(encoded.size <= MAX_MANIFEST)
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(encoded)
            zip.closeEntry()
            return manifest
        }
    }

    fun extract(input: InputStream, destination: File, byteBudget: Long): MigrationManifest {
        require(byteBudget >= 0)
        check(destination.mkdirs() || destination.isDirectory)
        val received = mutableListOf<MigrationFile>()
        val names = hashSetOf<String>()
        var total = 0L
        var manifest: MigrationManifest? = null
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(names.add(entry.name) && !entry.isDirectory && manifest == null)
                if (entry.name == MANIFEST) {
                    val buffer = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(64 * 1024)
                    while (true) {
                        val read = zip.read(chunk)
                        if (read < 0) break
                        require(buffer.size().toLong() + read <= MAX_MANIFEST)
                        buffer.write(chunk, 0, read)
                    }
                    manifest = json.decodeFromString<MigrationManifest>(buffer.toString("UTF-8"))
                } else {
                    require(safePath(entry.name) && received.size < MAX_FILES)
                    val file = File(destination, entry.name)
                    require(file.canonicalPath.startsWith(destination.canonicalPath + File.separator))
                    check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
                    val digest = MessageDigest.getInstance("SHA-256")
                    var count = 0L
                    file.outputStream().use { output ->
                        val chunk = ByteArray(256 * 1024)
                        while (true) {
                            val read = zip.read(chunk)
                            if (read < 0) break
                            total += read
                            require(total <= byteBudget)
                            output.write(chunk, 0, read)
                            digest.update(chunk, 0, read)
                            count += read
                        }
                    }
                    received += MigrationFile(entry.name, count, digest.digest().hex())
                }
                zip.closeEntry()
            }
            // Consume the transport to EOF, including the authenticated cipher's final tag.
            val drain = ByteArray(64 * 1024)
            while (input.read(drain) >= 0) Unit
        }
        return requireNotNull(manifest).also {
            require(it.protocol == 1 && it.files == received && it.bytes == total)
        }
    }

    fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().hex()
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
