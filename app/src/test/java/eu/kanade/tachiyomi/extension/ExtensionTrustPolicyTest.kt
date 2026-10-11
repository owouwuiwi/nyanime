package eu.kanade.tachiyomi.extension

import eu.kanade.domain.extension.ExtensionTrustPolicy
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExtensionTrustPolicyTest {
    @Test
    fun validSignatureDoesNotGrantConsent() = runBlocking {
        assertFalse(ExtensionTrustPolicy.isTrusted(listOf("valid-key")) { false })
        assertTrue(ExtensionTrustPolicy.isTrusted(listOf("approved-key")) { true })
    }

    @Test
    fun unsignedOrMalformedPackagesCannotUseAnExistingGrant() = runBlocking {
        for (signatures in listOf(emptyList(), listOf(""), listOf("known", " "))) {
            assertFalse(ExtensionTrustPolicy.isTrusted(signatures) { error("Invalid signature must fail first") })
        }
    }
}
