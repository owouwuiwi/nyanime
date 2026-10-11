package eu.kanade.tachiyomi.data.cast

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** P-256 ECDH, commit/reveal verification and directional AES-GCM. No platform APIs. */
internal class CompanionCrypto {
    private val pair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()
    private val publicKeyObject = pair.public as ECPublicKey
    val publicKey: String = encode(byteArrayOf(4) + fixed(publicKeyObject.w.affineX) + fixed(publicKeyObject.w.affineY))
    val nonce: String = encode(ByteArray(16).also { SecureRandom().nextBytes(it) })
    val commitment: String = encode(commitment(publicKey, nonce))

    fun complete(
        receiverId: String,
        sessionId: String,
        name: String,
        serverKey: String,
        serverNonce: String,
        commitment: String,
    ): Keys {
        require(receiverId.matches(Regex("[a-f0-9]{32}")) && sessionId.matches(Regex("[a-f0-9]{32}")))
        require(name.isNotBlank() && name.length <= 64 && name.none { it.code < 32 || it.code == 127 })
        require(MessageDigest.isEqual(decode(commitment, 32), CompanionCrypto.commitment(serverKey, serverNonce)))
        decode(serverNonce, 16)
        val point = decode(serverKey, 65)
        require(point[0] == 4.toByte())
        val key = KeyFactory.getInstance("EC").generatePublic(
            ECPublicKeySpec(
                ECPoint(BigInteger(1, point.copyOfRange(1, 33)), BigInteger(1, point.copyOfRange(33, 65))),
                publicKeyObject.params,
            ),
        )
        val agreement = KeyAgreement.getInstance("ECDH").apply {
            init(pair.private)
            doPhase(key, true)
        }
        val transcript = listOf(
            LABEL,
            receiverId,
            sessionId,
            name,
            publicKey,
            serverKey,
            nonce,
            serverNonce,
        ).joinToString("\n")
        return derive(agreement.generateSecret(), transcript)
    }

    data class Keys(val client: ByteArray, val server: ByteArray, val code: String) {
        fun clear() {
            client.fill(0)
            server.fill(0)
        }
    }

    companion object {
        private const val LABEL = "nyanime-cast/1"
        private fun fixed(value: BigInteger): ByteArray {
            val bytes = value.toByteArray()
            return ByteArray(32).also { bytes.takeLast(32).toByteArray().copyInto(it, 32 - minOf(bytes.size, 32)) }
        }
        fun encode(value: ByteArray): String = Base64.getEncoder().encodeToString(value)
        fun decode(value: String, size: Int? = null): ByteArray {
            require(value.length <= 131072 && value.matches(Regex("[A-Za-z0-9+/]*={0,2}")))
            return Base64.getDecoder().decode(value).also {
                require(encode(it) == value && (size == null || it.size == size))
            }
        }
        fun commitment(publicKey: String, nonce: String): ByteArray =
            MessageDigest.getInstance("SHA-256").digest("$LABEL/commit|$publicKey|$nonce".toByteArray())
        private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)
        fun derive(secret: ByteArray, transcript: String): Keys {
            val salt = MessageDigest.getInstance("SHA-256").digest(transcript.toByteArray())
            val prk = hmac(salt, secret)
            fun expand(purpose: String) = hmac(prk, "$LABEL/$purpose".toByteArray() + byteArrayOf(1))
            val verification = hmac(expand("verification"), transcript.toByteArray())
            val number =
                verification.take(4).fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 255) } % 1000000
            return Keys(expand("client"), expand("server"), number.toString().padStart(6, '0'))
        }
        private fun cipher(key: ByteArray, sessionId: String, direction: String, sequence: Long, mode: Int): Cipher {
            require(sequence in 1..9007199254740991L)
            val iv = ByteArray(12)
            repeat(8) { iv[11 - it] = (sequence ushr (it * 8)).toByte() }
            return Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
                updateAAD("$LABEL|$sessionId|$direction|$sequence".toByteArray())
            }
        }
        fun seal(key: ByteArray, sessionId: String, direction: String, sequence: Long, body: JsonObject): JsonObject =
            buildJsonObject {
                put("seq", sequence)
                put(
                    "data",
                    encode(
                        cipher(
                            key,
                            sessionId,
                            direction,
                            sequence,
                            Cipher.ENCRYPT_MODE,
                        ).doFinal(body.toString().toByteArray()),
                    ),
                )
            }
        fun open(
            key: ByteArray,
            sessionId: String,
            direction: String,
            sequence: Long,
            envelope: JsonObject,
        ): JsonObject {
            require(envelope.getValue("seq").jsonPrimitive.long == sequence)
            val data = decode(envelope.getValue("data").jsonPrimitive.content)
            require(data.size in 16..65536)
            return Json.parseToJsonElement(
                cipher(key, sessionId, direction, sequence, Cipher.DECRYPT_MODE).doFinal(data).decodeToString(),
            ).jsonObject
        }
    }
}
