package eu.kanade.tachiyomi.data.rin

import android.content.Context
import android.util.AtomicFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import java.io.File

@Serializable
data class RinRecord(
    val manifest: RinManifest,
    val signer: String,
    val sha256: String,
    val repository: String,
    val file: String,
    val enabled: Boolean = true,
    val format: String = "rin",
    val signers: Set<String> = emptySet(),
    val signerHistory: Set<String> = emptySet(),
    val trusted: Boolean = true,
    val isShared: Boolean = false,
    val automaticUpdates: Boolean = true,
    val catalogueConsent: String? = null,
    val storagePublisher: String = signer,
) {
    val publisherSigners get() = signers.ifEmpty { setOf(signer) }
    val publisherHistory get() = signerHistory.ifEmpty { publisherSigners }
}

/** Immutable versions and an atomic active pointer. Existing source instances retain their version. */
class RinStorage(private val context: Context) {
    private val root = File(context.filesDir, "rin").apply { mkdirs() }
    private val index = AtomicFile(File(root, "active.json"))

    fun records(): List<RinRecord> = synchronized(lock) {
        runCatching {
            RinArchive.json.parseToJsonElement(index.openRead().use { it.readBytes().decodeToString() }).jsonArray
                .mapNotNull { encoded ->
                    runCatching {
                        RinArchive.json.decodeFromJsonElement<RinRecord>(encoded).also {
                            require(it.manifest.medium in RinManifest.supportedMedia && it.manifest.versionCode > 0)
                            require(file(it).isFile)
                        }
                    }.getOrNull()
                }.distinctBy { it.manifest.packageName }
        }.getOrDefault(emptyList())
    }

    fun record(packageName: String) = records().firstOrNull { it.manifest.packageName == packageName }

    fun file(record: RinRecord): File {
        require(record.format in setOf("rin", "apk"))
        require(record.file == "${record.sha256}.${record.format}" && record.sha256.matches(Regex("[a-f0-9]{64}")))
        return File(root, record.file)
    }

    fun stage(input: File, signer: String, hash: String, repository: String): RinRecord = synchronized(lock) {
        val apk = if (eu.kanade.tachiyomi.extension.ExtensionPackageArchive.isApk(input)) {
            eu.kanade.tachiyomi.extension.ExtensionPackageArchive.inspect(context, input)
        } else {
            null
        }
        val manifest = apk?.manifest ?: RinArchive.verify(input, signer, hash)
        val metadata = apk?.let { eu.kanade.tachiyomi.extension.ExtensionPackageInspector.inspect(it.info) { 0 } }
        require(apk == null || !apk.requiresAndroid) { "This extension requires Android installation" }
        require(metadata == null || signer in metadata.signers || signer in metadata.signerHistory)
        require(eu.kanade.tachiyomi.extension.ExtensionPackageArchive.sha256(input) == hash)
        val format = if (apk == null) "rin" else "apk"
        val target = File(root, "$hash.$format")
        if (!target.isFile) {
            val temp = File.createTempFile("stage-", ".$format", root)
            try {
                // Android 14: establish read-only permissions before writing executable bytes.
                temp.outputStream().use { output ->
                    check(temp.setReadOnly())
                    input.inputStream().use { it.copyTo(output) }
                    output.flush()
                    output.fd.sync()
                }
                if (apk == null) {
                    RinArchive.verify(temp, signer, hash)
                } else {
                    eu.kanade.tachiyomi.extension.ExtensionPackageArchive.inspect(context, temp)
                }
                check(temp.renameTo(target))
            } finally {
                temp.delete()
            }
        } else {
            if (apk == null) {
                RinArchive.verify(target, signer, hash)
            } else {
                eu.kanade.tachiyomi.extension.ExtensionPackageArchive.inspect(context, target)
            }
        }
        RinRecord(
            manifest, signer, hash, repository, target.name, format = format,
            signers = metadata?.signers.orEmpty(), signerHistory = metadata?.signerHistory.orEmpty(), trusted = false,
        )
    }

    fun activate(record: RinRecord) = synchronized(lock) {
        eu.kanade.tachiyomi.extension.ExtensionPackageArchive.verify(context, file(record), record)
        val old = record(record.manifest.packageName)
        require(
            old == null ||
                (
                    eu.kanade.tachiyomi.extension.ExtensionPackageChoice.samePublisher(
                        old.publisherSigners,
                        eu.kanade.tachiyomi.extension.ExtensionPackageChoice.Candidate(
                            record.file,
                            record.manifest.versionCode,
                            record.publisherSigners,
                            record.publisherHistory,
                        ),
                    ) &&
                        old.manifest.versionCode <= record.manifest.versionCode &&
                        (old.manifest.versionCode != record.manifest.versionCode || old.sha256 == record.sha256) &&
                        (
                            old.manifest.knownSourceIds.isEmpty() ||
                                record.manifest.preservesSourceIds(old.manifest.knownSourceIds)
                            )
                    ),
        )
        write(
            records().filterNot { it.manifest.packageName == record.manifest.packageName } +
                record.copy(storagePublisher = old?.storagePublisher ?: record.storagePublisher),
        )
    }

    fun remove(packageName: String) = synchronized(lock) {
        // Never delete executable files during this process: old parsers may still reference them.
        write(records().filterNot { it.manifest.packageName == packageName })
    }
    fun revokeTrust() = synchronized(lock) {
        write(records().map { it.copy(trusted = false, catalogueConsent = null) })
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
            it.isFile && it.extension in setOf("rin", "apk") && it.name !in keep && it.lastModified() < cutoff
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
