package eu.kanade.tachiyomi.data.telegram

import android.content.Context
import android.os.Bundle
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File
import java.io.IOException

/**
 * Only small control data belongs in Binder's shared transaction buffer. Large JSON replies
 * travel through a read-only descriptor instead. The private temporary file is unlinked
 * before sending; Android owns the duplicated descriptor until its receiver closes it.
 * No public URI, path, retained plaintext file or writable descriptor crosses IPC.
 */
internal class TelegramIpcPayload private constructor(
    private val inline: String?,
    private val descriptor: ParcelFileDescriptor?,
    private val length: Int,
) : Closeable {
    fun read(): String {
        inline?.let { return it }
        val source = requireNotNull(descriptor)
        return ParcelFileDescriptor.AutoCloseInputStream(source).use { input ->
            require(length in 1..MAX_BYTES && source.statSize == length.toLong())
            val bytes = ByteArray(length)
            var offset = 0
            while (offset < bytes.size) {
                val count = input.read(bytes, offset, bytes.size - offset)
                if (count < 0) throw IOException("Incomplete Telegram IPC payload")
                offset += count
            }
            check(input.read() == -1)
            bytes.decodeToString(throwOnInvalidSequence = true)
        }
    }

    override fun close() {
        descriptor?.close()
    }

    companion object {
        // String parcels use UTF-16. Keep even the inline path well below shared Binder limits.
        private const val INLINE_CHARS = 8_192
        const val MAX_BYTES = 8 * 1024 * 1024
        private const val DESCRIPTOR = "json_descriptor"
        private const val LENGTH = "json_bytes"

        fun receive(bundle: Bundle): TelegramIpcPayload {
            bundle.classLoader = ParcelFileDescriptor::class.java.classLoader
            @Suppress("DEPRECATION")
            val descriptor = bundle.getParcelable<ParcelFileDescriptor>(DESCRIPTOR)
            if (descriptor != null) {
                val length = bundle.getInt(LENGTH)
                try {
                    require(length in 1..MAX_BYTES)
                    return TelegramIpcPayload(null, descriptor, length)
                } catch (error: Exception) {
                    descriptor.close()
                    throw error
                }
            }
            val value = requireNotNull(bundle.getString("json"))
            require(value.length <= INLINE_CHARS)
            return TelegramIpcPayload(value, null, 0)
        }

        /** Blocking file work must run on IO; ownership of the passed Message stays with Messenger. */
        fun send(context: Context, peer: Messenger, message: Message, raw: String) {
            if (raw.length <= INLINE_CHARS) {
                message.data = Bundle().apply { putString("json", raw) }
                peer.send(message)
                return
            }
            val bytes = raw.toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_BYTES)
            val directory = File(context.noBackupFilesDir, "telegram-ipc").apply {
                check(isDirectory || mkdirs())
            }
            val file = File.createTempFile("payload-", ".tmp", directory)
            try {
                file.outputStream().use { output ->
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                        check(file.delete())
                        output.write(bytes)
                        message.data = Bundle().apply {
                            putParcelable(DESCRIPTOR, descriptor)
                            putInt(LENGTH, bytes.size)
                        }
                        peer.send(message)
                    }
                }
            } finally {
                file.delete()
            }
        }
    }
}
