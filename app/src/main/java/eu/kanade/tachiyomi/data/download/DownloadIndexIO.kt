package eu.kanade.tachiyomi.data.download

import com.hippo.unifile.UniFile
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

internal const val MAX_DOWNLOAD_INDEX_BYTES = 16 * 1024 * 1024

/** An unavailable directory must not be confused with an empty directory and overwrite a valid snapshot. */
internal fun UniFile?.listDownloadIndexFiles(): List<UniFile> =
    this?.let { it.listFiles()?.toList() ?: throw IOException("Download directory could not be read") }.orEmpty()

/** A corrupt index must not allocate an unbounded byte array before decoding. */
internal fun readDownloadIndex(input: InputStream, limit: Int = MAX_DOWNLOAD_INDEX_BYTES): ByteArray {
    require(limit > 0)
    val output = ByteArrayOutputStream(minOf(limit, 8_192))
    val buffer = ByteArray(minOf(limit, 8_192))
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count > limit - output.size()) throw IOException("Download index is too large")
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
