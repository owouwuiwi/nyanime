package eu.kanade.tachiyomi.data.releases

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AnimeScheduleBrowserConsentTest {
    private fun withApi(block: (AnimeScheduleBrowserConsent, MockWebServer) -> Unit) {
        val cookies = object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
            override fun loadForRequest(url: HttpUrl) =
                listOf(Cookie.Builder().name("session").value("private-session").domain(url.host).build())
        }
        val http = OkHttpClient.Builder().cookieJar(cookies).build()
        try {
            MockWebServer().use { server ->
                server.start()
                block(AnimeScheduleBrowserConsent(http, server.url("/")), server)
            }
        } finally {
            http.dispatcher.executorService.shutdownNow()
            http.connectionPool.evictAll()
        }
    }

    @Test fun serverGeneratesNecessaryOnlyConsentWithoutAnyLoginCredential() {
        withApi { api, server ->
            server.enqueue(
                MockResponse.Builder().code(204)
                    .addHeader("Set-Cookie", "as_consent_v3=site-generated; Path=/; Secure; SameSite=Lax").build(),
            )
            assertEquals(
                "as_consent_v3=site-generated; Path=/; Secure; SameSite=Lax",
                runBlocking { api.necessaryCookie() },
            )
            val request = server.takeRequest()
            assertEquals("/api/v3/consent", request.target)
            assertEquals("POST", request.method)
            assertEquals("""{"analytical":false}""", request.body!!.utf8())
            assertNull(request.headers["Authorization"])
            assertNull(request.headers["Cookie"])
        }
    }

    @Test fun unrelatedCookiesAndAccountPathsCannotBeInstalledAsConsent() {
        withApi { api, server ->
            for (cookie in listOf(
                "another-cookie=value; Path=/",
                "as_consent_v3=value; Domain=other.invalid; Path=/",
                "as_consent_v3=value; Path=/account",
            )) {
                server.enqueue(MockResponse.Builder().code(204).addHeader("Set-Cookie", cookie).build())
                assertNull(runBlocking { api.necessaryCookie() })
            }
        }
    }

    @Test fun failedConsentDoesNotClaimSuccessOrFollowRedirects() {
        withApi { api, server ->
            server.enqueue(MockResponse.Builder().code(302).addHeader("Location", server.url("/other")).build())
            assertNull(runBlocking { api.necessaryCookie() })
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun aPreviouslySavedWebsiteChoiceIsPreserved() {
        assertTrue(AnimeScheduleBrowserConsent.alreadyAccepted("session=value; as_consent_v3=existing-choice"))
        assertFalse(AnimeScheduleBrowserConsent.alreadyAccepted("as_consent_v3="))
        assertFalse(AnimeScheduleBrowserConsent.alreadyAccepted("session=as_consent_v3"))
        assertFalse(AnimeScheduleBrowserConsent.alreadyAccepted(null))
    }
}
