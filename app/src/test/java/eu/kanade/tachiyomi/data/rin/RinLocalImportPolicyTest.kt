package eu.kanade.tachiyomi.data.rin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RinLocalImportPolicyTest {
    private val manifest = RinManifest(
        1, "test.extension.video", "Example", 2, "16.2", "anime", 16.0,
        listOf("test.extension.video.Source"), listOf(RinSource(901L, "Example", "it")), hashes = emptyMap(),
    )
    private val old = RinRecord(
        manifest,
        "a".repeat(64),
        "b".repeat(64),
        "https://catalog.invalid/index.json",
        "b".repeat(64) + ".rin",
    )
    private val next = old.copy(manifest = manifest.copy(versionCode = 3), sha256 = "c".repeat(64))

    @Test fun `a standalone file needs no catalogue and keeps manual updates independent`() {
        val origin = RinLocalImportPolicy.origin(old.signer, null, emptyList())
        assertEquals("", origin)
        val standalone = old.copy(repository = origin)
        assertTrue(RinLocalImportPolicy.accepts(standalone, null))
        assertTrue(RinLocalImportPolicy.accepts(next.copy(repository = origin), standalone))
        assertEquals("", RinLocalImportPolicy.origin(old.signer, standalone, listOf(old.repository to old.signer)))
        assertTrue(
            RinUpdatePolicy.updates(
                listOf(RinEntry(next.manifest, "", next.sha256, next.signer, old.repository)),
                listOf(standalone),
            ).isEmpty(),
        )
    }

    @Test fun `manual updates keep the installed origin when its catalogue was removed or replaced`() {
        assertEquals(old.repository, RinLocalImportPolicy.origin(old.signer, old, emptyList()))
        assertEquals(
            old.repository,
            RinLocalImportPolicy.origin(old.signer, old, listOf("https://other.invalid" to old.signer)),
        )
        assertTrue(RinLocalImportPolicy.accepts(next, old))
        assertFalse(RinLocalImportPolicy.accepts(next.copy(signer = "d".repeat(64)), old))
    }

    @Test fun `an optional catalogue is selected only by its matching signer`() {
        assertEquals(
            old.repository,
            RinLocalImportPolicy.origin(old.signer, null, listOf(old.repository to old.signer)),
        )
        assertEquals("", RinLocalImportPolicy.origin(old.signer, null, listOf(old.repository to "d".repeat(64))))
    }

    @Test fun `a new signed compatible source and an immutable duplicate can be selected`() {
        assertTrue(RinLocalImportPolicy.accepts(old, null))
        assertTrue(RinLocalImportPolicy.accepts(old, old))
        assertTrue(RinLocalImportPolicy.accepts(next, old))
    }

    @Test fun `manual updates cannot replace identity publisher medium or immutable version bytes`() {
        listOf(
            next.copy(signer = "d".repeat(64)),
            next.copy(repository = "https://other.invalid/index.json"),
            next.copy(manifest = next.manifest.copy(packageName = "test.other")),
            next.copy(manifest = next.manifest.copy(medium = "manga")),
            next.copy(manifest = next.manifest.copy(versionCode = 1)),
            next.copy(manifest = next.manifest.copy(versionCode = 2)),
            next.copy(manifest = next.manifest.copy(sources = listOf(RinSource(902L, "Other", "en")))),
        ).forEach { assertFalse(RinLocalImportPolicy.accepts(it, old)) }
    }

    @Test fun `dormant identities are retained while incompatible host versions cannot be installed`() {
        val replacement = next.copy(
            manifest = next.manifest.copy(
                sources = listOf(RinSource(902L, "New", "en")),
                dormantSources = manifest.sources,
            ),
        )
        assertTrue(RinLocalImportPolicy.accepts(replacement, old))
        assertFalse(
            RinLocalImportPolicy.accepts(
                next.copy(manifest = next.manifest.copy(minHostVersionCode = 2_000_000_000)),
                old,
            ),
        )
    }
}
