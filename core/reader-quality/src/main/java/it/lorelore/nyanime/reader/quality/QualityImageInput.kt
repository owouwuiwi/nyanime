package it.lorelore.nyanime.reader.quality

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.Closeable
import java.io.FilterInputStream
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest

/** Owns the source while a decoder consumes and closes its borrowed stream view. */
class QualityImageInput(source: InputStream) : Closeable {
    private val digest = MessageDigest.getInstance("SHA-256")
    private val stream = DigestInputStream(source, digest)
    private var fingerprint: String? = null
    val decoderStream: InputStream = object : FilterInputStream(stream) {
        // ImageDecoder.newInstance closes its argument. The owner still needs any unread bytes.
        override fun close() = Unit
    }

    suspend fun fingerprint(): String {
        fingerprint?.let { return it }
        val buffer = ByteArray(8192)
        while (true) {
            currentCoroutineContext().ensureActive()
            if (stream.read(buffer) < 0) break
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.also { fingerprint = it }
    }

    override fun close() = stream.close()
}
