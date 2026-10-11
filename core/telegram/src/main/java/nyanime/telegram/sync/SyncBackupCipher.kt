package nyanime.telegram.sync

import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.streamingaead.AesGcmHkdfStreamingParameters
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import kotlinx.serialization.Serializable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

@Serializable
data class SyncBackupHeader(val version: Int = 1, val protection: SyncEnvelope, val bytes: Long) {
    fun validate() {
        require(version == 1 && bytes in 1..SyncBackupCipher.MAX_BYTES)
        protection.validate()
    }
}

/**
 * Tink streaming AEAD authenticates bounded segments, including their position and final marker.
 * Callers decrypt to a private temporary file and expose it ONLY after successful completion.
 */
object SyncBackupCipher {
    const val MAX_BYTES = 2L * 1024 * 1024 * 1024
    private val magic = "NYABKS1\n".toByteArray(Charsets.US_ASCII)
    private val parameters = AesGcmHkdfStreamingParameters.builder()
        .setKeySizeBytes(32)
        .setDerivedAesGcmKeySizeBytes(32)
        .setHkdfHashType(AesGcmHkdfStreamingParameters.HashType.SHA256)
        .setCiphertextSegmentSizeBytes(1024 * 1024)
        .build()

    init {
        StreamingAeadConfig.register()
    }

    fun encrypt(
        input: InputStream,
        output: OutputStream,
        bytes: Long,
        account: Long,
        space: String,
        epoch: Long,
        id: String,
        key: ByteArray,
    ): SyncBackupHeader {
        require(bytes in 1..MAX_BYTES)
        val handle = KeysetHandle.generateNew(parameters)
        val secret = TinkProtoKeysetFormat.serializeKeyset(
            handle,
            InsecureSecretKeyAccess.get(),
            RegistryConfiguration.get(),
        )
        val wrapped = try {
            SyncCipher.encrypt(account, space, epoch, id, key, secret)
        } finally {
            secret.fill(0)
        }
        val header = SyncBackupHeader(protection = wrapped, bytes = bytes).also { it.validate() }
        val encoded = syncJson.encodeToString(header).toByteArray(Charsets.UTF_8)
        val data = DataOutputStream(output)
        data.write(magic)
        data.writeInt(encoded.size)
        data.write(encoded)
        handle.getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
            .newEncryptingStream(output, binding(account, encoded)).use { encrypted ->
                require(copy(input, encrypted, bytes) == bytes)
            }
        return header
    }

    fun readHeader(input: InputStream): SyncBackupHeader {
        val data = DataInputStream(input)
        val prefix = ByteArray(magic.size)
        data.readFully(prefix)
        require(prefix.contentEquals(magic))
        val size = data.readInt()
        require(size in 1..64 * 1024)
        val encoded = ByteArray(size)
        data.readFully(encoded)
        return syncJson.decodeFromString<SyncBackupHeader>(encoded.toString(Charsets.UTF_8)).also { it.validate() }
    }

    fun decrypt(
        inputAfterHeader: InputStream,
        output: OutputStream,
        account: Long,
        header: SyncBackupHeader,
        key: ByteArray,
    ) {
        header.validate()
        val secret = SyncCipher.decrypt(account, header.protection.space, key, header.protection)
        val handle = try {
            TinkProtoKeysetFormat.parseKeyset(secret, InsecureSecretKeyAccess.get(), RegistryConfiguration.get())
        } finally {
            secret.fill(0)
        }
        val encoded = syncJson.encodeToString(header).toByteArray(Charsets.UTF_8)
        handle.getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
            .newDecryptingStream(inputAfterHeader, binding(account, encoded)).use { clear ->
                require(copy(clear, output, header.bytes) == header.bytes)
            }
    }

    private fun binding(account: Long, header: ByteArray): ByteArray {
        require(account > 0)
        return "nyanime-cloud-backup|1|$account|".toByteArray(Charsets.UTF_8) + header
    }

    private fun copy(input: InputStream, output: OutputStream, limit: Long): Long {
        val buffer = ByteArray(64 * 1024)
        var count = 0L
        while (true) {
            val size = input.read(buffer)
            if (size < 0) return count
            count += size
            require(count <= limit) { "Cloud backup size differs from its protected header" }
            output.write(buffer, 0, size)
        }
    }
}
