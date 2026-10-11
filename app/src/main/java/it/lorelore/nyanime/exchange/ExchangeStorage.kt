package it.lorelore.nyanime.exchange

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.util.UUID

internal object ExchangeStorage {
    private const val RESERVE = 64L * 1024 * 1024
    fun root(context: Context) = File(context.cacheDir, "source-exchange")

    fun newSession(context: Context): File {
        val root = root(context).apply { check(mkdirs() || isDirectory) }
        // Private, generated directories only. Incoming metadata never selects paths.
        root.listFiles()?.filter {
            it.isDirectory &&
                System.currentTimeMillis() - it.lastModified() > 24 * 60 * 60 * 1000L
        }
            ?.forEach { directory ->
                if (directory.canonicalFile.parentFile ==
                    root.canonicalFile
                ) {
                    directory.deleteRecursively()
                }
            }
        if (root.usableSpace < ExchangeProtocol.LIMIT * 3 + RESERVE) throw IOException("space")
        return File(root, UUID.randomUUID().toString()).apply { check(mkdir()) }
    }

    fun received(context: Context, file: ExchangeFile): File {
        require(file.bytes in 0..ExchangeProtocol.LIMIT)
        ExchangeProtocol.filename(file.name)
        require(file.hash.matches(Regex("[a-f0-9]{64}")))
        val path = File(file.path).canonicalFile
        val root = root(context).canonicalFile
        require(path.toPath().startsWith(root.toPath()) && path.isFile && path.length() == file.bytes)
        return path
    }

    fun displayName(context: Context, uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment.orEmpty()

    suspend fun copy(context: Context, uri: Uri, session: File, name: String): File {
        ExchangeProtocol.filename(name)
        val destination = File(session, "outgoing.source")
        require(!destination.exists())
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input)
                destination.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var total = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= ExchangeProtocol.LIMIT)
                        output.write(buffer, 0, count)
                    }
                    output.flush()
                    output.fd.sync()
                }
            }
            check(destination.setReadOnly())
            return destination
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
    }
}
