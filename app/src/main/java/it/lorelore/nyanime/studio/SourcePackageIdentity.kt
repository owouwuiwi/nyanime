package it.lorelore.nyanime.studio

import eu.kanade.tachiyomi.data.rin.RinRecord
import java.security.MessageDigest

/** The same signer-bound identity is used by package activation and both optional brokers. */
internal object SourcePackageIdentity {
    fun publisher(record: RinRecord): String = MessageDigest.getInstance("SHA-256")
        .digest(record.publisherSigners.sorted().joinToString(":").toByteArray())
        .joinToString("") { "%02x".format(it) }

    fun owner(record: RinRecord, sourceId: Long) =
        SourceAccessOwner(record.manifest.packageName, publisher(record), sourceId)
}
