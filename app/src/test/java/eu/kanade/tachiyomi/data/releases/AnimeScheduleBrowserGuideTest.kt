package eu.kanade.tachiyomi.data.releases

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AnimeScheduleBrowserGuideTest {
    @Test fun onlyExactHttpsOriginCanBeEnhanced() {
        assertTrue(AnimeScheduleBrowserGuide.ownPage(AnimeScheduleBrowserGuide.LOGIN))
        for (url in listOf(
            "http://animeschedule.net/login",
            "https://animeschedule.net.other.invalid/login",
            "https://other.invalid@animeschedule.net/login",
            "https://animeschedule.net:8443/login",
            "file:///login",
            "javascript:void(0)",
            "broken url",
        )) {
            assertFalse(AnimeScheduleBrowserGuide.ownPage(url), url)
        }
    }

    @Test fun navigationTargetsCannotEscapeToAnotherPageOrOrigin() {
        assertEquals(
            "https://animeschedule.net/users/Test_Account/settings/api/create",
            AnimeScheduleBrowserGuide.apiTarget("/users/Test_Account/settings/api/create"),
        )
        for (path in listOf(
            "//other.invalid/users/me/settings/api",
            "/users/../settings/api",
            "/users/a%2fb/settings/api",
            "/users/name/settings/api?token=anything",
            "/users/name/settings/api#secret",
            "/login",
            "/users/name/settings",
        )) {
            assertNull(AnimeScheduleBrowserGuide.apiTarget(path), path)
        }
    }

    @Test fun tokenImportIsRestrictedToApplicationList() {
        assertTrue(AnimeScheduleBrowserGuide.tokenPage("https://animeschedule.net/users/Test/settings/api"))
        assertFalse(AnimeScheduleBrowserGuide.tokenPage("https://animeschedule.net/users/Test/settings/api/create"))
        assertFalse(AnimeScheduleBrowserGuide.tokenPage(AnimeScheduleBrowserGuide.LOGIN))
        assertFalse(AnimeScheduleBrowserGuide.tokenPage("https://other.invalid/users/Test/settings/api"))
    }

    @Test fun malformedBrowserResultsFailWithoutASecretOrFalseSuccess() {
        assertNull(AnimeScheduleBrowserGuide.state("null"))
        assertNull(AnimeScheduleBrowserGuide.state("not json"))
        assertNull(AnimeScheduleBrowserGuide.token("null"))
        assertNull(AnimeScheduleBrowserGuide.token("\"short\""))
        assertNull(AnimeScheduleBrowserGuide.token("\"a-token-with spaces-in-it\""))
        assertNull(AnimeScheduleBrowserGuide.token("{\"token\":\"not-a-string\"}"))
        assertEquals("synthetic-token-for-test", AnimeScheduleBrowserGuide.token("\"synthetic-token-for-test\""))
        assertEquals("CREATE", AnimeScheduleBrowserGuide.state("{\"stage\":\"CREATE\",\"future\":true}")?.stage)
    }
}
