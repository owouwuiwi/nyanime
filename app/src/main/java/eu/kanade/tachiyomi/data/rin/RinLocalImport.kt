package eu.kanade.tachiyomi.data.rin

data class RinLocalImport(val record: RinRecord, val previous: RinRecord?)

enum class RinImportFailure {
    INVALID,
    UNTRUSTED,
    INCOMPATIBLE,
    LEGACY,
    CHANGED,
    BUSY,
}

class RinImportException(val reason: RinImportFailure) : Exception("RIN import: $reason")

/** Manual selection never grants publisher changes, downgrades or a new media/source identity. */
object RinLocalImportPolicy {
    fun accepts(record: RinRecord, previous: RinRecord?): Boolean =
        RinRepository.compatible(record.manifest) &&
            record.manifest.validSourceIdentities() &&
            (
                previous == null ||
                    record.manifest.packageName == previous.manifest.packageName &&
                    record.signer == previous.signer &&
                    record.repository == previous.repository &&
                    record.manifest.medium == previous.manifest.medium &&
                    record.manifest.preservesSourceIds(previous.manifest.knownSourceIds) &&
                    (
                        record.manifest.versionCode > previous.manifest.versionCode ||
                            record.manifest.versionCode == previous.manifest.versionCode &&
                            record.sha256 == previous.sha256
                        )
                )
}
