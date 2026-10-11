package eu.kanade.tachiyomi.data.updater.delta

import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** A bounded byte copier, not an APK parser or executable patch engine. */
internal object DeltaPatch {
    const val MAX_APK_SIZE = 1_073_741_824L
    private const val MAX_COMMANDS = 100_000
    private const val BUFFER_SIZE = 64 * 1024

    fun hash(file: File, checkActive: () -> Unit = {}): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(BUFFER_SIZE).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                checkActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().hex()
    }

    fun apply(
        base: File,
        patch: File,
        output: File,
        expected: DeltaRecord,
        checkActive: () -> Unit = {},
        progress: (Long, Long) -> Unit = { _, _ -> },
    ) {
        require(output.canonicalFile != base.canonicalFile && output.canonicalFile != patch.canonicalFile)
        require(!output.exists()) { "Delta output already exists" }
        require(base.length() == expected.baseSize && base.length() in 1..MAX_APK_SIZE)
        require(patch.length() == expected.patchSize && hash(patch, checkActive) == expected.patchSha256)
        require(hash(base, checkActive) == expected.baseSha256) { "Installed APK does not match delta" }
        var complete = false
        try {
            patch.inputStream().buffered(BUFFER_SIZE).use { compressed ->
                DataInputStream(GZIPInputStream(compressed, BUFFER_SIZE)).use { input ->
                    val magic = ByteArray(8).also(input::readFully)
                    require(magic.contentEquals("NYADLT01".toByteArray(Charsets.US_ASCII)))
                    val baseSize = input.readLong()
                    val targetSize = input.readLong()
                    require(baseSize == expected.baseSize && targetSize == expected.targetSize)
                    require(targetSize in 1..MAX_APK_SIZE)
                    require(ByteArray(32).also(input::readFully).hex() == expected.baseSha256)
                    require(ByteArray(32).also(input::readFully).hex() == expected.targetSha256)
                    val commands = input.readInt()
                    require(commands in 1..MAX_COMMANDS) { "Invalid delta command count" }
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(BUFFER_SIZE)
                    RandomAccessFile(base, "r").use { old ->
                        FileOutputStream(output).use { destination ->
                            var written = 0L
                            repeat(commands) {
                                checkActive()
                                val kind = input.readUnsignedByte()
                                val length: Long
                                when (kind) {
                                    0 -> {
                                        val offset = input.readLong()
                                        length = input.readLong()
                                        require(offset >= 0 && offset <= baseSize && length <= baseSize - offset)
                                        old.seek(offset)
                                    }
                                    1 -> length = input.readLong()
                                    else -> error("Unknown delta command")
                                }
                                require(length > 0 && length <= targetSize - written) { "Delta output exceeds limit" }
                                var remaining = length
                                while (remaining > 0) {
                                    checkActive()
                                    val count = minOf(buffer.size.toLong(), remaining).toInt()
                                    if (kind ==
                                        0
                                    ) {
                                        old.readFully(buffer, 0, count)
                                    } else {
                                        input.readFully(buffer, 0, count)
                                    }
                                    destination.write(buffer, 0, count)
                                    digest.update(buffer, 0, count)
                                    remaining -= count
                                    written += count
                                    progress(written, targetSize)
                                }
                            }
                            require(written == targetSize && input.read() == -1) { "Incomplete or trailing delta data" }
                            require(digest.digest().hex() == expected.targetSha256) {
                                "Reconstructed APK hash mismatch"
                            }
                            checkActive()
                            destination.fd.sync()
                        }
                    }
                }
            }
            complete = true
        } finally {
            if (!complete) output.delete()
        }
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
