package eu.kanade.tachiyomi.data.migration

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import com.hippo.unifile.UniFile
import java.io.File
import java.security.MessageDigest

/** Shared files are reused, never moved or deleted. The new UID needs its own Android grant. */
internal object MigrationSharedStorage {
    private const val ACCESS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    /** Delegate only the existing tree, only after the owner's signed migration consent. */
    fun delegate(context: Context, recipient: String, storage: String?): Boolean = runCatching {
        val uri = Uri.parse(requireNotNull(storage))
        require(uri.scheme == "content" && DocumentsContract.isTreeUri(uri))
        require(
            context.contentResolver.persistedUriPermissions.any {
                identity(it.uri) == identity(uri) && it.isReadPermission && it.isWritePermission
            },
        )
        context.grantUriPermission(
            recipient,
            uri,
            ACCESS or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
        true
    }.getOrDefault(false)

    fun retain(context: Context, storage: String): Boolean = runCatching {
        val uri = Uri.parse(storage)
        require(uri.scheme == "content" && DocumentsContract.isTreeUri(uri))
        context.contentResolver.takePersistableUriPermission(uri, ACCESS)
        verify(context, storage, uri)
        true
    }.getOrDefault(false)

    fun identity(uri: Uri): String = if (uri.scheme == "file") {
        val root = Environment.getExternalStorageDirectory().canonicalFile
        val file = File(requireNotNull(uri.path)).canonicalFile
        require(file == root || file.path.startsWith(root.path + File.separator))
        "com.android.externalstorage.documents|primary:" +
            (if (file == root) "" else file.relativeTo(root).invariantSeparatorsPath)
    } else {
        require(DocumentsContract.isTreeUri(uri))
        "${uri.authority}|${DocumentsContract.getTreeDocumentId(uri).trimEnd('/')}"
    }

    fun verify(context: Context, expected: String, selected: Uri) {
        require(identity(Uri.parse(expected)) == identity(selected)) { "Select the original folder" }
        val folder = checkNotNull(UniFile.fromUri(context, selected))
        check(folder.exists() && folder.isDirectory && folder.canRead() && folder.canWrite())
    }

    /** Metadata verification of an unchanged, identical shared tree without rereading large videos. */
    fun inventory(context: Context, uri: String): String? {
        val folder = UniFile.fromUri(context, Uri.parse(uri)) ?: return null
        if (!folder.exists()) return null
        check(folder.isDirectory && folder.canRead())
        val digest = MessageDigest.getInstance("SHA-256")
        var files = 0
        fun visit(parent: UniFile, path: String, depth: Int) {
            require(depth < 64)
            checkNotNull(parent.listFiles()).sortedBy { it.name }.forEach { child ->
                val name = requireNotNull(child.name)
                require(name !in setOf(".", "..") && '/' !in name)
                val relative = "$path/$name"
                if (child.isDirectory) {
                    visit(child, relative, depth + 1)
                } else {
                    require(++files <= 250_000)
                    digest.update("${relative}\u0000${child.length()}\u0000${child.lastModified()}\n".toByteArray())
                }
            }
        }
        visit(folder, "", 0)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
