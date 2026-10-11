package eu.kanade.tachiyomi.data.reading

import eu.kanade.tachiyomi.data.watch.watchHex
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.InputStream
import java.security.MessageDigest

/** Hash original encoded bytes, never a screenshot, crop or re-encoded thumbnail. */
object ReadingPageFingerprint {
    const val MAX_BYTES = 32 * 1024 * 1024
    suspend fun calculate(open: () -> InputStream): String? {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(16 * 1024)
        var total = 0
        open().use { input ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_BYTES) return null
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().watchHex().takeIf { total > 0 }
    }

    fun matches(local: String?, remote: String?): Boolean =
        !local.isNullOrBlank() && local.matches(Regex("[0-9a-f]{64}")) && local == remote
}
