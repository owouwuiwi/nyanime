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
    val publisherRollback: RinRecord? = null,
) {
    val publisherSigners get() = signers.ifEmpty { setOf(signer) }
    val publisherHistory get() = signerHistory.ifEmpty { publisherSigners }
}

/** Immutable versions and an atomic active pointer. Existing source instances retain their version. */
class RinStorage(private val context: Context) {
    private val root = File(context.filesDir, "rin").apply { mkdirs() }
    private val index = AtomicFile(File(root, "active.json"))

    fun records(): List<RinRecord> = synchronized(lock) { readRecords().filter { it.format == "apk" } }

    private fun readRecords(): List<RinRecord> =
        runCatching {
            RinArchive.json.parseToJsonElement(index.openRead().use { it.readBytes().decodeToString() }).jsonArray
                .mapNotNull { encoded ->
                    runCatching {
                        RinArchive.json.decodeFromJsonElement<RinRecord>(encoded).also {
                            require(it.manifest.medium in RinManifest.supportedMedia && it.manifest.versionCode > 0)
                            require(file(it).isFile)
                        }
                    }.getOrNull()
                }.distinctBy { it.manifest.packageName to it.format }
        }.getOrDefault(emptyList())

    fun record(packageName: String) = records().firstOrNull { it.manifest.packageName == packageName }

    fun file(record: RinRecord): File {
        require(record.format in setOf("rin", "apk"))
        require(record.file == "${record.sha256}.${record.format}" && record.sha256.matches(Regex("[a-f0-9]{64}")))
        return File(root, record.file)
    }

    fun stage(input: File, signer: String, hash: String, repository: String): RinRecord = synchronized(lock) {
        val apk = eu.kanade.tachiyomi.extension.ExtensionPackageArchive.inspect(context, input)
        val metadata = eu.kanade.tachiyomi.extension.ExtensionPackageInspector.inspect(apk.info) { 0 }
        require(!apk.requiresAndroid) { "This extension requires Android installation" }
        require(signer in metadata.signers || signer in metadata.signerHistory)
        require(eu.kanade.tachiyomi.extension.ExtensionPackageArchive.sha256(input) == hash)
        val target = File(root, "$hash.apk")
        val record = RinRecord(
            apk.manifest, signer, hash, repository, target.name, format = "apk",
            signers = metadata.signers, signerHistory = metadata.signerHistory, trusted = false,
        )
        if (!target.isFile) {
            val temp = File.createTempFile("stage-", ".apk", root)
            try {
                // Android 14: establish read-only permissions before writing executable bytes.
                temp.outputStream().use { output ->
                    check(temp.setReadOnly())
                    input.inputStream().use { it.copyTo(output) }
                    output.flush()
                    output.fd.sync()
                }
                eu.kanade.tachiyomi.extension.ExtensionPackageArchive.verify(context, temp, record)
                check(temp.renameTo(target))
            } finally {
                temp.delete()
            }
        } else {
            eu.kanade.tachiyomi.extension.ExtensionPackageArchive.verify(context, target, record)
        }
        record
    }

    fun activate(
        record: RinRecord,
        approveLayerScope: Boolean = false,
        expected: RinRecord? = null,
        requireUnchanged: Boolean = false,
    ) = synchronized(lock) {
        eu.kanade.tachiyomi.extension.ExtensionPackageArchive.verify(context, file(record), record)
        val old = record(record.manifest.packageName)
        check(!requireUnchanged || old == expected) { "Extension was removed or changed before activation" }
        if (record.manifest.isLayer && record.trusted && !approveLayerScope) {
            require(old != null && record.manifest.preservesMangaLayerScope(old.manifest))
        }
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
                        (
                            old.manifest.medium == record.manifest.medium ||
                                !old.manifest.isLayer && !record.manifest.isLayer
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
                record.copy(
                    storagePublisher = old?.storagePublisher ?: record.storagePublisher,
                    publisherRollback = old?.publisherRollback,
                ),
        )
    }

    internal fun switchMangaPublisher(previous: RinRecord, next: RinRecord) = synchronized(lock) {
        val packageName = previous.manifest.packageName
        require(record(packageName) == previous && previous.publisherRollback == null)
        requirePublisherSwitch(previous, next)
        write(
            records().filterNot { it.manifest.packageName == packageName } +
                next.copy(publisherRollback = previous),
        )
    }

    internal fun rollbackMangaPublisher(current: RinRecord, restored: RinRecord) = synchronized(lock) {
        val packageName = current.manifest.packageName
        require(record(packageName) == current && current.publisherRollback?.sha256 == restored.sha256)
        requirePublisherSwitch(current, restored)
        write(records().filterNot { it.manifest.packageName == packageName } + restored)
    }

    private fun requirePublisherSwitch(current: RinRecord, replacement: RinRecord) {
        require(
            current.manifest.packageName == replacement.manifest.packageName &&
                current.manifest.medium == "manga" &&
                replacement.manifest.medium == "manga" &&
                current.format == "apk" && replacement.format == "apk" &&
                !current.isShared && !replacement.isShared &&
                current.manifest.validSourceIdentities() &&
                replacement.manifest.validSourceIdentities() &&
                current.manifest.knownSourceIds == replacement.manifest.knownSourceIds,
        )
        eu.kanade.tachiyomi.extension.ExtensionPackageArchive.verify(context, file(current), current)
        eu.kanade.tachiyomi.extension.ExtensionPackageArchive.verify(context, file(replacement), replacement)
    }

    fun remove(packageName: String) = synchronized(lock) {
        // Never delete executable files during this process: old parsers may still reference them.
        write(records().filterNot { it.manifest.packageName == packageName })
    }
    fun revokeTrust() = synchronized(lock) {
        write(records().map { it.copy(trusted = false, catalogueConsent = null) })
    }

    fun setEnabled(packageName: String, enabled: Boolean, expected: RinRecord? = null): RinRecord = synchronized(lock) {
        val current = requireNotNull(record(packageName))
        check(expected == null || current == expected) { "Extension changed before activation" }
        val changed = current.copy(enabled = enabled)
        write(records().map { if (it.manifest.packageName == packageName) changed else it })
        changed
    }

    fun setAutomaticUpdates(packageName: String, enabled: Boolean) = synchronized(lock) {
        val installed = records()
        if (installed.none { it.manifest.packageName == packageName && it.automaticUpdates != enabled }) return@synchronized
        write(installed.map { if (it.manifest.packageName == packageName) it.copy(automaticUpdates = enabled) else it })
    }

    fun pruneOnColdStart(prepared: List<RinRecord>) = synchronized(lock) {
        val keep = (records() + prepared).flatMap { listOfNotNull(it.file, it.publisherRollback?.file) }.toSet()
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        root.listFiles()?.filter {
            it.isFile && it.extension == "apk" && it.name !in keep && it.lastModified() < cutoff
        }?.forEach { it.delete() }
    }

    private fun write(records: List<RinRecord>) {
        require(records.all { it.format == "apk" })
        // Keep historical package records on disk, outside the active APK inventory.
        val retired = readRecords().filter { it.format != "apk" }
        val output = index.startWrite()
        try {
            output.write(RinArchive.json.encodeToString(records + retired).toByteArray())
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
