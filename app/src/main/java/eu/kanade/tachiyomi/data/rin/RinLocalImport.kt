package eu.kanade.tachiyomi.data.rin

data class RinLocalImport(val record: RinRecord, val previous: RinRecord?)

enum class RinImportFailure {
    INVALID,
    SYSTEM_REQUIRED,
    INCOMPATIBLE,
    LEGACY,
    CHANGED,
    BUSY,
}

class RinImportException(val reason: RinImportFailure) : Exception("RIN import: $reason")

/** Manual selection never grants publisher changes, downgrades or a new media/source identity. */
object RinLocalImportPolicy {
    /** A catalogue is optional. An installed package keeps its origin even after catalogue removal. */
    fun origin(signer: String, previous: RinRecord?, catalogues: List<Pair<String, String>>): String =
        previous?.repository
            ?: catalogues.filter { it.second == signer }.map { it.first }.distinct().singleOrNull().orEmpty()

    fun accepts(record: RinRecord, previous: RinRecord?): Boolean =
        RinRepository.compatible(record.manifest, record.format) &&
            (record.manifest.validSourceIdentities() || record.format == "apk" && record.manifest.sources.isEmpty()) &&
            (
                previous == null ||
                    record.manifest.packageName == previous.manifest.packageName &&
                    eu.kanade.tachiyomi.extension.ExtensionPackageChoice.samePublisher(
                        previous.publisherSigners,
                        eu.kanade.tachiyomi.extension.ExtensionPackageChoice.Candidate(
                            record.file,
                            record.manifest.versionCode,
                            record.publisherSigners,
                            record.publisherHistory,
                        ),
                    ) &&
                    record.repository == previous.repository &&
                    record.manifest.medium == previous.manifest.medium &&
                    (
                        record.manifest.sources.isEmpty() &&
                            record.format == "apk" ||
                            record.manifest.preservesSourceIds(previous.manifest.knownSourceIds)
                        ) &&
                    (
                        record.manifest.versionCode > previous.manifest.versionCode ||
                            record.manifest.versionCode == previous.manifest.versionCode &&
                            record.sha256 == previous.sha256
                        )
                )
}
