package eu.kanade.tachiyomi.data.migration

import android.content.Context
import android.net.Uri
import java.io.File

/** App-owned downloads move with the copied files; shared folders keep their own Android grant. */
internal object MigrationStoragePaths {
    fun valid(path: String): Boolean = path.substringBefore('/') in setOf("files", "external") &&
        MigrationArchive.safePath("${path.trimEnd('/')}/storage-marker")

    fun owned(context: Context, storage: String?): String? {
        val uri = storage?.let(Uri::parse)?.takeIf { it.scheme == "file" } ?: return null
        val file = File(requireNotNull(uri.path)).canonicalFile
        return MigrationSnapshot.roots(context).entries.filter { it.key in setOf("files", "external") }
            .firstNotNullOfOrNull { (name, directory) ->
                val root = directory.canonicalFile
                if (file == root || file.path.startsWith("${root.path}${File.separator}")) {
                    "$name/${file.relativeTo(root).invariantSeparatorsPath}"
                } else {
                    null
                }
            }
    }

    fun destination(context: Context, path: String): String {
        require(valid(path))
        val root = checkNotNull(MigrationSnapshot.roots(context)[path.substringBefore('/')]).canonicalFile
        val file = File(root, path.substringAfter('/', "")).canonicalFile
        require(file == root || file.path.startsWith("${root.path}${File.separator}"))
        return Uri.fromFile(file).toString()
    }
}
