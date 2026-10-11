package nyanime.telegram.sync

import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.hybrid.HpkeParameters
import com.google.crypto.tink.hybrid.HybridConfig
import kotlinx.serialization.Serializable
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

@Serializable
data class SyncDevicePublic(val id: String, val signingKey: String, val encryptionKey: String) {
    fun validate() {
        requireSyncId(id)
        require(signingKey.length in 80..512 && encryptionKey.length in 80..4096)
    }
}

/** Never serialize this object into a backup, a log, a URI or the ordinary sync journal. */
class SyncDeviceSecret internal constructor(
    val public: SyncDevicePublic,
    internal val signing: ByteArray,
    internal val encryption: ByteArray,
) {
    override fun toString(): String = "SyncDeviceSecret(redacted)"
}

/**
 * Device signing identifies the author independently of the shared data key. HPKE grants a new
 * epoch key only to its recipients, so removing a device can protect FUTURE data.
 */
object SyncDeviceCrypto {
    init {
        HybridConfig.register()
    }

    fun generate(id: String): SyncDeviceSecret {
        requireSyncId(id)
        val signing = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val encryption = KeysetHandle.generateNew(
            HpkeParameters.builder()
                .setKemId(HpkeParameters.KemId.DHKEM_X25519_HKDF_SHA256)
                .setKdfId(HpkeParameters.KdfId.HKDF_SHA256)
                .setAeadId(HpkeParameters.AeadId.AES_256_GCM)
                .setVariant(HpkeParameters.Variant.TINK)
                .build(),
        )
        return SyncDeviceSecret(
            public = SyncDevicePublic(
                id,
                SyncCipher.encode(signing.public.encoded),
                SyncCipher.encode(TinkProtoKeysetFormat.serializeKeysetWithoutSecret(encryption.publicKeysetHandle)),
            ),
            signing = signing.private.encoded,
            encryption = TinkProtoKeysetFormat.serializeKeyset(
                encryption,
                InsecureSecretKeyAccess.get(),
                RegistryConfiguration.get(),
            ),
        )
    }

    fun sign(device: SyncDeviceSecret, content: ByteArray): String = SyncCipher.encode(
        Signature.getInstance("SHA256withECDSA").run {
            initSign(KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(device.signing)))
            update(content)
            sign()
        },
    )

    fun verify(device: SyncDevicePublic, content: ByteArray, signature: String): Boolean = runCatching {
        device.validate()
        require(signature.length in 64..256)
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(
                KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(SyncCipher.decode(device.signingKey))),
            )
            update(content)
            verify(SyncCipher.decode(signature))
        }
    }.getOrDefault(false)

    fun seal(recipient: SyncDevicePublic, content: ByteArray, context: ByteArray): String {
        recipient.validate()
        require(content.size <= 1024 * 1024)
        val key = TinkProtoKeysetFormat.parseKeysetWithoutSecret(SyncCipher.decode(recipient.encryptionKey))
        return SyncCipher.encode(
            key.getPrimitive(RegistryConfiguration.get(), HybridEncrypt::class.java).encrypt(content, context),
        )
    }

    fun open(recipient: SyncDeviceSecret, encrypted: String, context: ByteArray): ByteArray {
        require(encrypted.length <= 2 * 1024 * 1024)
        val key = TinkProtoKeysetFormat.parseKeyset(
            recipient.encryption,
            InsecureSecretKeyAccess.get(),
            RegistryConfiguration.get(),
        )
        return key.getPrimitive(RegistryConfiguration.get(), HybridDecrypt::class.java)
            .decrypt(SyncCipher.decode(encrypted), context)
    }

    /** Only the local Keystore wrapper may persist the returned bytes. */
    fun exportForLocalWrapping(device: SyncDeviceSecret): ByteArray = syncJson.encodeToString(
        WrappedDevice(device.public, SyncCipher.encode(device.signing), SyncCipher.encode(device.encryption)),
    ).toByteArray(Charsets.UTF_8)

    fun importAfterLocalUnwrapping(bytes: ByteArray): SyncDeviceSecret {
        require(bytes.size <= 16 * 1024)
        val data = syncJson.decodeFromString<WrappedDevice>(bytes.toString(Charsets.UTF_8))
        data.public.validate()
        val result = SyncDeviceSecret(data.public, SyncCipher.decode(data.signing), SyncCipher.decode(data.encryption))
        val challenge = SyncCipher.newKey()
        require(verify(result.public, challenge, sign(result, challenge)))
        require(open(result, seal(result.public, challenge, challenge), challenge).contentEquals(challenge))
        return result
    }

    @Serializable
    private data class WrappedDevice(val public: SyncDevicePublic, val signing: String, val encryption: String)
}
