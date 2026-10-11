package it.lorelore.nyanime.reader.quality

import java.security.MessageDigest

/** Portable source/entry identity; database row IDs can change during backup restore. */
data class QualityTitle(val sourceId: Long, val entryUrl: String) {
    init {
        require(entryUrl.isNotBlank() && entryUrl.length <= 16384)
    }
    val storageKey: String by lazy {
        MessageDigest.getInstance("SHA-256")
            .digest("reader-quality-v1\u0000$sourceId\u0000$entryUrl".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
