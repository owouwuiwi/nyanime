package eu.kanade.tachiyomi.data.download.anime

import android.content.Context
import android.os.ParcelFileDescriptor
import com.hippo.unifile.UniFile
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Properties

class UriPartialVideoStore(
    private val context: Context,
    private val file: UniFile,
    private val metadataFile: UniFile,
) : VideoBlockStore {
    @Synchronized
    private fun readState() = runCatching {
        require(metadataFile.length() in 1..262_144)
        Properties().apply { metadataFile.openInputStream().use { load(it) } }
    }.getOrNull()

    override var journal: VideoBlockJournal?
        get() = runCatching {
            val state = readState() ?: return null
            if (state.getProperty("version") != "2") return null
            val total = state.getProperty("total").toLong().also { require(it in 1..2_000_000_000_000) }
            val blockSize = state.getProperty("blockSize").toInt().also { require(it == 2 * 1024 * 1024) }
            val count = ((total - 1) / blockSize + 1).toInt()
            VideoBlockJournal(
                state.getProperty("identity"),
                state.getProperty("blockEtag"),
                total,
                blockSize,
                state.stringPropertyNames().filter { it.startsWith("block.") }.associate {
                    val index = it.removePrefix("block.").toInt().also { require(it in 0 until count) }
                    index to state.getProperty(it).also { require(it.matches(Regex("[a-f0-9]{64}"))) }
                },
            )
        }.getOrNull()
        set(value) {
            if (value == null) {
                metadata = null
                return
            }
            val state = Properties().apply {
                setProperty("version", "2")
                setProperty("identity", value.identity)
                // Never expose sparse storage as a resumable contiguous partial to older engines.
                setProperty("blockEtag", value.etag)
                setProperty("total", value.total.toString())
                setProperty("blockSize", value.blockSize.toString())
                value.completed.forEach { (index, hash) -> setProperty("block.$index", hash) }
            }
            writeState(state)
        }

    @Synchronized
    private fun writeState(state: Properties) {
        metadataFile.openOutputStream().use {
            (it as? FileOutputStream)?.channel?.truncate(0)
            state.store(it, null)
            it.flush()
            (it as? FileOutputStream)?.fd?.sync()
        }
    }
    override var metadata: PartialVideoMetadata?
        get() = runCatching {
            // Ignore oversized or incomplete state left by an interrupted write.
            require(metadataFile.length() in 1..16_384)
            val state = Properties().apply { metadataFile.openInputStream().use { load(it) } }
            PartialVideoMetadata(
                state.getProperty("identity") ?: return null,
                state.getProperty("etag"),
                state.getProperty("total")?.toLongOrNull() ?: return null,
            )
        }.getOrNull()
        set(value) {
            if (value == null) {
                metadataFile.openOutputStream().use { (it as? FileOutputStream)?.channel?.truncate(0) }
                return
            }
            val state = Properties().apply {
                setProperty("identity", value.identity)
                value.etag?.let { setProperty("etag", it) }
                setProperty("total", value.total.toString())
            }
            writeState(state)
        }

    override fun size() = file.length()

    override fun openBlock(offset: Long): OutputStream = openAt(offset, false)

    override fun supportsParallel(): Boolean = runCatching {
        val first = context.contentResolver.openFileDescriptor(file.uri, "rw") ?: return false
        ParcelFileDescriptor.AutoCloseOutputStream(first).use { a ->
            val second = context.contentResolver.openFileDescriptor(file.uri, "rw") ?: return false
            ParcelFileDescriptor.AutoCloseOutputStream(second).use { b ->
                a.channel.position(0)
                b.channel.position(1)
                a.channel.position() == 0L && b.channel.position() == 1L
            }
        }
    }.getOrDefault(false)

    override fun digest(offset: Long, length: Long): String {
        val descriptor =
            context.contentResolver.openFileDescriptor(file.uri, "r") ?: throw IOException("Unavailable file")
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            input.channel.position(offset)
            val hash = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            var remaining = length
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (count < 0) throw IOException("Incomplete block")
                hash.update(buffer, 0, count)
                remaining -= count
            }
            return hash.digest().hex()
        }
    }

    override fun open(offset: Long): OutputStream = openAt(offset, offset == 0L)

    private fun openAt(offset: Long, truncate: Boolean): OutputStream {
        val descriptor = context.contentResolver.openFileDescriptor(file.uri, "rw")
            ?: throw VideoStorageException("Cartella download non accessibile. Controlla i permessi di archiviazione.")
        val output = ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
        try {
            if (truncate) output.channel.truncate(0)
            output.channel.position(offset)
            if (output.channel.position() != offset) throw IOException("Storage does not support seeking")
            return object : java.io.FilterOutputStream(output) {
                override fun write(bytes: ByteArray, start: Int, count: Int) = output.write(bytes, start, count)
                override fun close() {
                    try {
                        output.flush()
                        output.fd.sync()
                    } finally {
                        output.close()
                    }
                }
            }
        } catch (e: Exception) {
            output.close()
            throw UnsupportedDirectVideo()
        }
    }
}
