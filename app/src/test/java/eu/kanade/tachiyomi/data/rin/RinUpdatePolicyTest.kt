package eu.kanade.tachiyomi.data.rin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RinUpdatePolicyTest {
    private val manifest = RinManifest(
        1, "test.extension.video", "Example source", 1, "16.1", "anime", 16.0,
        listOf("test.extension.video.Source"), listOf(RinSource(901L, "Example", "it")), hashes = emptyMap(),
    )
    private val old = RinRecord(
        manifest,
        "c".repeat(64),
        "d".repeat(64),
        "https://example.invalid/index.json",
        "d".repeat(64) + ".rin",
    )
    private val next =
        RinEntry(
            manifest.copy(versionCode = 2, versionName = "16.2"),
            "https://example.invalid/extension.rin",
            "e".repeat(64),
            old.signer,
            old.repository,
        )

    @Test fun `updates are restricted to already installed RIN sources`() {
        val other = next.copy(manifest = next.manifest.copy(packageName = "test.extension.other"))
        assertEquals(listOf(next), RinUpdatePolicy.updates(listOf(next, other), listOf(old)))
        assertTrue(RinUpdatePolicy.updates(listOf(next, other), emptyList()).isEmpty())
    }

    @Test fun `publisher catalogue downgrade and identity changes are excluded`() {
        listOf(
            next.copy(signer = "f".repeat(64)),
            next.copy(repository = "https://other.invalid/index.json"),
            next.copy(manifest = next.manifest.copy(versionCode = 1)),
            next.copy(manifest = next.manifest.copy(sources = listOf(RinSource(902L, "Other", "en")))),
            next.copy(manifest = next.manifest.copy(medium = "manga")),
        ).forEach {
            assertTrue(RinUpdatePolicy.updates(listOf(it), listOf(old)).isEmpty())
        }
    }

    @Test fun `updates retain deliberately dormant identities through subsequent versions`() {
        val dormant = manifest.sources.single()
        val replacement = next.copy(
            manifest = next.manifest.copy(
                sources = listOf(RinSource(902L, "Active edition", "en")),
                dormantSources = listOf(dormant),
            ),
        )
        assertEquals(listOf(replacement), RinUpdatePolicy.updates(listOf(replacement), listOf(old)))
        val installed = old.copy(manifest = replacement.manifest)
        val future = replacement.copy(manifest = replacement.manifest.copy(versionCode = 3))
        assertTrue(RinUpdatePolicy.accepts(future, installed))
        assertTrue(
            !RinUpdatePolicy.accepts(
                future.copy(manifest = future.manifest.copy(dormantSources = emptyList())),
                installed,
            ),
        )
    }
}
