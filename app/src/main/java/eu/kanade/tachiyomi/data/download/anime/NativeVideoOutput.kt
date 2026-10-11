package eu.kanade.tachiyomi.data.download.anime

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.Closeable

/** Keep the Android-granted descriptor alive until the native session actually ends.
 * FFmpeg's fd protocol duplicates it and preserves seeking, without reopening a scoped-storage path.
 */
class NativeVideoOutput(context: Context, uri: Uri) : Closeable {
    private val descriptor: ParcelFileDescriptor? = if (uri.scheme == "content") {
        context.contentResolver.openFileDescriptor(uri, "rwt")
            ?: throw VideoStorageException("Cartella download non accessibile.")
    } else {
        null
    }
    val path: String = descriptor?.let { "fd:" }
        ?: uri.path ?: throw VideoStorageException("Percorso download non disponibile.")

    fun arguments(arguments: Array<String>): Array<String> = nativeOutputArguments(arguments, path, descriptor?.fd)

    override fun close() {
        descriptor?.close()
    }
}

/** Protocol options must precede the output URL, never an input belonging to the extension. */
internal fun nativeOutputArguments(arguments: Array<String>, output: String, descriptor: Int?): Array<String> {
    if (descriptor == null) return arguments
    require(descriptor >= 0)
    val index = arguments.indexOfLast { it == output }
    require(index >= 0) { "Missing native output" }
    return (arguments.take(index) + listOf("-fd", descriptor.toString()) + arguments.drop(index)).toTypedArray()
}
