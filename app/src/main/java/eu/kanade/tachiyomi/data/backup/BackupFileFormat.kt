package eu.kanade.tachiyomi.data.backup

object BackupFileFormat {
    fun acceptsPath(path: String?): Boolean =
        path != null && (path.endsWith(".nyabk", ignoreCase = true) || path.endsWith(".tachibk", ignoreCase = true))
}
