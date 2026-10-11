package eu.kanade.tachiyomi.data.rin

import android.content.Context
import android.util.AtomicFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File

@Serializable
data class RinRecord(
    val manifest: RinManifest,
    val signer: String,
    val sha256: String,
    val repository: String,
    val file: String,
    val enabled: Boolean = true,
)

/** Immutable versions and an atomic active pointer. Existing source instances retain their version. */
class RinStorage(context: Context) {
    private val root = File(context.filesDir, "rin").apply { mkdirs() }
    private val index = AtomicFile(File(root, "active.json"))

    fun records(): List<RinRecord> = synchronized(lock) {
        runCatching {
            RinArchive.json.decodeFromString<List<RinRecord>>(index.openRead().use { it.readBytes().decodeToString() })
                .filter { file(it).isFile }
        }.getOrDefault(emptyList())
    }

    fun record(packageName: String) = records().firstOrNull { it.manifest.packageName == packageName }

    fun file(record: RinRecord): File {
        require(record.file.matches(Regex("[a-f0-9]{64}\\.rin")))
        return File(root, record.file)
    }

    fun stage(input: File, signer: String, hash: String, repository: String): RinRecord = synchronized(lock) {
        val manifest = RinArchive.verify(input, signer, hash)
        val target = File(root, "$hash.rin")
        if (!target.isFile) {
            val temp = File.createTempFile("stage-", ".rin", root)
            try {
                // Android 14: establish read-only permissions before writing executable bytes.
                temp.outputStream().use { output ->
                    check(temp.setReadOnly())
                    input.inputStream().use { it.copyTo(output) }
                    output.flush()
                }
                RinArchive.verify(temp, signer, hash)
                check(temp.renameTo(target))
            } finally {
                temp.delete()
            }
        } else {
            RinArchive.verify(target, signer, hash)
        }
        RinRecord(manifest, signer, hash, repository, target.name)
    }

    fun activate(record: RinRecord) = synchronized(lock) {
        RinArchive.verify(file(record), record.signer, record.sha256)
        val old = record(record.manifest.packageName)
        require(
            old == null ||
                (
                    old.signer == record.signer &&
                        old.manifest.versionCode <= record.manifest.versionCode &&
                        (old.manifest.versionCode != record.manifest.versionCode || old.sha256 == record.sha256) &&
                        record.manifest.preservesSourceIds(old.manifest.knownSourceIds)
                    ),
        )
        write(records().filterNot { it.manifest.packageName == record.manifest.packageName } + record)
    }

    fun remove(packageName: String) = synchronized(lock) {
        // Never delete executable files during this process: old parsers may still reference them.
        write(records().filterNot { it.manifest.packageName == packageName })
    }

    fun setEnabled(packageName: String, enabled: Boolean): RinRecord = synchronized(lock) {
        val current = requireNotNull(record(packageName))
        val changed = current.copy(enabled = enabled)
        write(records().map { if (it.manifest.packageName == packageName) changed else it })
        changed
    }

    fun pruneOnColdStart(prepared: List<RinRecord>) = synchronized(lock) {
        val keep = (records() + prepared).map { it.file }.toSet()
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        root.listFiles()?.filter {
            it.isFile && it.extension == "rin" && it.name !in keep && it.lastModified() < cutoff
        }?.forEach { it.delete() }
    }

    private fun write(records: List<RinRecord>) {
        val output = index.startWrite()
        try {
            output.write(RinArchive.json.encodeToString(records).toByteArray())
            index.finishWrite(output)
        } catch (error: Exception) {
            index.failWrite(output)
            throw error
        }
    }

    companion object {
        private val lock = Any()
    }
}
