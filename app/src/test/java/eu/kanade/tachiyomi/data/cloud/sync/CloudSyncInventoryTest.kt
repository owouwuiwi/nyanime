package eu.kanade.tachiyomi.data.cloud.sync

import eu.kanade.tachiyomi.data.rin.RinManifest
import nyanime.telegram.sync.syncJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CloudSyncInventoryTest {
    private fun inventory(medium: String, origin: String = "") = PortableRin(
        "org.example.bundle",
        origin,
        "a".repeat(64),
        medium,
        if (medium in setOf("anime", "manga", "tv")) setOf(17L) else emptySet(),
    )

    @Test
    fun localInventoryForEverySupportedMediumNeedsNoCatalogue() {
        for (medium in RinManifest.supportedMedia) {
            val item = inventory(medium)
            item.validate()
            val restored = syncJson.decodeFromString<PortableRin>(syncJson.encodeToString(item))
            restored.validate()
            assertEquals(item, restored)
            assertEquals("", restored.repository)
        }
    }

    @Test
    fun remoteOriginAndIdentityChecksRemainStrict() {
        inventory("manga", "https://catalogue.example.invalid/index.json").validate()
        for (origin in listOf("http://catalogue.example.invalid", "file:///private/data", " ", "x".repeat(4097))) {
            assertThrows(IllegalArgumentException::class.java) { inventory("manga", origin).validate() }
        }
        assertThrows(IllegalArgumentException::class.java) { inventory("unknown").validate() }
        assertThrows(IllegalArgumentException::class.java) { inventory("manga").copy(signer = "bad").validate() }
        assertThrows(IllegalArgumentException::class.java) { inventory("manga").copy(sources = setOf(0L)).validate() }
    }
}
