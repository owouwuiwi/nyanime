package eu.kanade.tachiyomi.data.community

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

class BlossomUploadTest {
    @Test
    fun `legacy token retry and CDN verification preserve authorization boundaries`() = runBlocking {
        val certificate = HeldCertificate.Builder().commonName(
            "localhost",
        ).addSubjectAlternativeName("localhost").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(
            certificate,
        ).addTrustedCertificate(certificate.certificate).build()
        val client = OkHttpClient.Builder().followRedirects(
            false,
        ).sslSocketFactory(tls.sslSocketFactory(), tls.trustManager).build()
        MockWebServer().use { server ->
            server.useHttps(tls.sslSocketFactory())
            server.start()
            val bytes = "local image fixture".toByteArray()
            val hash = sha256(bytes).hex()
            val url = "https://localhost/$hash.jpg"
            server.enqueue(MockResponse.Builder().code(400).body("invalid base64 for auth event").build())
            server.enqueue(MockResponse.Builder().code(201).body("{\"sha256\":\"$hash\",\"url\":\"$url\"}").build())
            server.enqueue(MockResponse.Builder().code(307).addHeader("Location", "/cdn/$hash.jpg").build())
            server.enqueue(MockResponse.Builder().body(Buffer().write(bytes)).build())
            CommunityIdentity().use { identity ->
                assertEquals(
                    url,
                    BlossomUploadClient(
                        client.newBuilder().addInterceptor { chain ->
                            chain.proceed(
                                chain.request().newBuilder().url(
                                    chain.request().url.newBuilder().port(server.port).build(),
                                ).build(),
                            )
                        }.build(),
                    ).upload(identity, bytes, "https://localhost"),
                )
            }
            val first = requireNotNull(server.takeRequest().headers["Authorization"]).removePrefix("Nostr ")
            val second = requireNotNull(server.takeRequest().headers["Authorization"]).removePrefix("Nostr ")
            assertTrue(Base64.getUrlDecoder().decode(first).contentEquals(Base64.getDecoder().decode(second)))
            val signed = communityJson.decodeFromString<NostrEvent>(Base64.getDecoder().decode(second).decodeToString())
            assertTrue(signed.valid())
            assertEquals(hash, signed.tag("x"))
            assertEquals("localhost", signed.tag("server"))
            assertEquals(null, server.takeRequest().headers["Authorization"])
            assertEquals(null, server.takeRequest().headers["Authorization"])
        }
    }

    @Test
    fun `payment refusal is reported without retrying or discarding the draft`() = runBlocking {
        val certificate = HeldCertificate.Builder().commonName(
            "localhost",
        ).addSubjectAlternativeName("localhost").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(
            certificate,
        ).addTrustedCertificate(certificate.certificate).build()
        val client = OkHttpClient.Builder().sslSocketFactory(tls.sslSocketFactory(), tls.trustManager).build()
        MockWebServer().use { server ->
            server.useHttps(tls.sslSocketFactory())
            server.enqueue(MockResponse.Builder().code(402).body("private data should never be echoed").build())
            server.start()
            CommunityIdentity().use { identity ->
                val error = runCatching {
                    BlossomUploadClient(
                        client.newBuilder().addInterceptor { chain ->
                            chain.proceed(
                                chain.request().newBuilder().url(
                                    chain.request().url.newBuilder().port(server.port).build(),
                                ).build(),
                            )
                        }.build(),
                    ).upload(identity, byteArrayOf(1), "https://localhost")
                }.exceptionOrNull()
                assertEquals(402, (error as BlossomUploadException).status)
                assertTrue(error.message.orEmpty().contains("pagamento"))
                assertFalse(error.message.orEmpty().contains("private data"))
                assertEquals(1, server.requestCount)
            }
        }
    }
}
