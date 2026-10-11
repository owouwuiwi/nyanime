package tachiyomi.domain.release.model

/** Published Android release notes; download/installation remain owned by the selected release. */
data class ReleaseHistoryEntry(
    val version: String,
    val info: String,
    val prerelease: Boolean = false,
    val publishedAt: String? = null,
)
