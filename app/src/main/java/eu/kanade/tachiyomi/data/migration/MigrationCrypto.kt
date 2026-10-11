package eu.kanade.tachiyomi.data.migration

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Authenticated bounded frames: large downloads never buffer a whole GCM message in memory. */
internal object MigrationCrypto {
    private const val CHUNK = 256 * 1024
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("nyanime.migration.v1", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    "nyanime.migration.v1",
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build(),
            )
        }.generateKey()
    }

    fun encrypt(output: OutputStream, secretKey: SecretKey = key()): OutputStream = object : OutputStream() {
        val target = DataOutputStream(output)
        val stream = ByteArray(16).also(SecureRandom()::nextBytes)
        val buffer = ByteArray(CHUNK)
        val secret = secretKey
        var used = 0
        var sequence = 0L
        var closed = false
        init {
            target.writeInt(1)
            target.write(stream)
        }
        fun frame(size: Int) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secret)
            cipher.updateAAD(stream + ByteBuffer.allocate(8).putLong(sequence++).array())
            val sealed = cipher.doFinal(buffer, 0, size)
            target.writeInt(sealed.size)
            target.write(cipher.iv)
            target.write(sealed)
        }
        override fun write(value: Int) {
            write(byteArrayOf(value.toByte()), 0, 1)
        }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
            check(!closed)
            var start = offset
            var remaining = length
            while (remaining > 0) {
                val count = minOf(remaining, CHUNK - used)
                bytes.copyInto(buffer, used, start, start + count)
                used += count
                start += count
                remaining -= count
                if (used == CHUNK) {
                    frame(used)
                    used = 0
                }
            }
        }
        override fun close() {
            if (closed) return
            try {
                if (used > 0) frame(used)
                frame(0)
            } finally {
                closed = true
                buffer.fill(0)
                target.close()
            }
        }
    }

    fun decrypt(input: InputStream, secretKey: SecretKey = key()): InputStream = object : InputStream() {
        val source = DataInputStream(input)
        val stream = ByteArray(16)
        val secret = secretKey
        var buffer = ByteArray(0)
        var index = 0
        var sequence = 0L
        var complete = false
        init {
            require(source.readInt() == 1)
            source.readFully(stream)
        }
        fun next(): Boolean {
            if (complete) return false
            val size = source.readInt() // EOF before the final authenticated frame is an error.
            require(size in 16..CHUNK + 16)
            val iv = ByteArray(12).also(source::readFully)
            val sealed = ByteArray(size).also(source::readFully)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, iv))
            cipher.updateAAD(stream + ByteBuffer.allocate(8).putLong(sequence++).array())
            buffer.fill(0)
            buffer = cipher.doFinal(sealed)
            index = 0
            if (buffer.isEmpty()) {
                complete = true
                require(source.read() == -1)
                return false
            }
            return true
        }
        override fun read(): Int {
            if (index == buffer.size && !next()) return -1
            return buffer[index++].toInt() and 255
        }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
            if (length == 0) return 0
            if (index == buffer.size && !next()) return -1
            val count = minOf(length, buffer.size - index)
            buffer.copyInto(bytes, offset, index, index + count)
            index += count
            return count
        }
        override fun close() {
            buffer.fill(0)
            source.close()
        }
    }
}
