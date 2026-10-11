package eu.kanade.tachiyomi.data.extensionstore

import eu.kanade.tachiyomi.data.rin.RinArchive
import eu.kanade.tachiyomi.data.rin.RinEntry
import eu.kanade.tachiyomi.data.rin.RinManifest
import eu.kanade.tachiyomi.data.rin.RinRecord
import eu.kanade.tachiyomi.data.rin.RinSource
import eu.kanade.tachiyomi.data.rin.RinUpdatePolicy
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RinStoreCatalogueTest {
    @Test fun `presentation order does not privilege a distribution format or installation mode`() {
        val privateEntry = RinStoreCatalogue.entries(emptyList(), listOf(record), emptyList(), emptyList()).single()
            .copy(name = "Zeta")
        val androidEntry = privateEntry.copy(id = "system", name = "Alpha", rinRecord = null)
        val selected = StorePresentation.select(
            listOf(privateEntry, androidEntry),
            StoreShelf.OWNED,
            null,
            StoreActivation.ALL,
            "",
            emptySet(),
            false,
        )
        assertEquals(listOf("Alpha", "Zeta"), selected.map { it.name })
    }

    @Test fun `live extensions have their own store category without fabricated manga registrations`() {
        val live = record.copy(manifest = manifest.copy(schema = 2, medium = "tv", extensionLib = 1.0))
        val entry = RinStoreCatalogue.entries(emptyList(), listOf(live), emptyList(), emptyList()).single()
        assertEquals(StoreMedium.TV, entry.medium)
        assertTrue(entry.ready)
        assertEquals(StoreAction.OPEN, entry.action)
        assertEquals(null, entry.anime)
        assertEquals(null, entry.manga)
    }
    private val manifest = RinManifest(
        1, "test.extension.video", "Example extension", 1, "16.1", "anime", 16.0,
        listOf("test.extension.video.Source"), listOf(RinSource(901L, "Example edition", "it")),
        hashes = emptyMap(),
    )
    private val record = RinRecord(
        manifest,
        "c".repeat(64),
        "d".repeat(64),
        "https://example.invalid/index.json",
        "version.rin",
    )
    private val candidate = RinEntry(
        manifest.copy(versionCode = 2, versionName = "16.2"),
        "https://example.invalid/package.rin",
        "e".repeat(64),
        record.signer,
        record.repository,
    )

    @Test fun `installed disabled packages stay manageable without a catalogue or loaded sources`() {
        val disabled = record.copy(enabled = false)
        val entry = RinStoreCatalogue.entries(emptyList(), listOf(disabled), emptyList(), emptyList()).single()
        assertTrue(entry.installed)
        assertTrue(entry.isRin)
        assertFalse(entry.enabled)
        assertEquals(StoreAction.OPEN, entry.action)
        assertTrue(entry.matchesQuery("example edition"))
        assertTrue(entry.matchesQuery("901"))
        assertEquals("16.1", entry.version)
        assertEquals(disabled, entry.rinRecord)
        assertFalse(entry.automaticUpdatePending)
    }

    @Test fun `installed origin is preserved while only its authenticated update is shown`() {
        val foreign = candidate.copy(repository = "https://other.invalid/index.json")
        val foreignSigner = candidate.copy(signer = "f".repeat(64))
        val entries = RinStoreCatalogue.entries(
            listOf(foreign, foreignSigner, candidate),
            listOf(record),
            emptyList(),
            emptyList(),
        )
        assertEquals(1, entries.size)
        assertEquals(candidate, entries.single().rinCandidate)
        assertEquals(record.repository, entries.single().repository)
        assertTrue(entries.single().automaticUpdatePending)
        assertEquals(StoreAction.UPDATE, entries.single().action)
    }

    @Test fun `discover keeps publisher choices separate and selects the newest immutable version`() {
        val older = candidate.copy(manifest = manifest)
        val other = candidate.copy(repository = "https://other.invalid/index.json")
        val entries = RinStoreCatalogue.entries(listOf(older, candidate, other), emptyList(), emptyList(), emptyList())
        assertEquals(2, entries.size)
        assertEquals(2, entries.map { it.id }.distinct().size)
        assertTrue(entries.all { !it.installed && it.action == StoreAction.INSTALL })
        assertTrue(entries.all { it.rinCandidate!!.manifest.versionCode == 2L })
    }

    @Test fun `older records are enabled and disabled records retain automatic update eligibility`() {
        val oldJson = RinArchive.json.encodeToString(record).replace(",\"enabled\":true", "")
        assertTrue(RinArchive.json.decodeFromString<RinRecord>(oldJson).enabled)
        val disabled = record.copy(enabled = false)
        assertEquals(disabled, RinArchive.json.decodeFromString<RinRecord>(RinArchive.json.encodeToString(disabled)))
        assertEquals(listOf(candidate), RinUpdatePolicy.updates(listOf(candidate), listOf(disabled)))
    }

    @Test fun `shelves search media and status compose without hiding installed packages`() {
        val owned = RinStoreCatalogue.entries(
            emptyList(),
            listOf(record.copy(enabled = false, manifest = manifest.copy(nsfw = true))),
            emptyList(),
            emptyList(),
        ).single()
        val available = RinStoreCatalogue.entries(listOf(candidate), emptyList(), emptyList(), emptyList()).single()
        val all = listOf(owned, available)
        fun select(
            shelf: StoreShelf,
            medium: StoreMedium? = null,
            status: StoreActivation = StoreActivation.ALL,
            query: String = "",
            languages: Set<String> = emptySet(),
            adult: Boolean = false,
        ) = StorePresentation.select(all, shelf, medium, status, query, languages, adult)
        assertEquals(listOf(owned), select(StoreShelf.OWNED))
        assertEquals(listOf(owned), select(StoreShelf.OWNED, StoreMedium.ANIME, StoreActivation.INACTIVE, "901"))
        assertTrue(select(StoreShelf.OWNED, StoreMedium.MANGA).isEmpty())
        assertTrue(select(StoreShelf.OWNED, status = StoreActivation.ACTIVE).isEmpty())
        assertTrue(select(StoreShelf.DISCOVER).isEmpty())
        assertEquals(listOf(available), select(StoreShelf.DISCOVER, languages = setOf("it")))
        assertTrue(select(StoreShelf.DISCOVER, query = "missing", languages = setOf("it")).isEmpty())
    }

    @Test fun `RIN addons use the addon shelf without source languages`() {
        val addon = manifest.copy(
            schema = 2,
            medium = "addon",
            extensionLib = 1.0,
            sources = emptyList(),
            addon = eu.kanade.tachiyomi.data.rin.RinAddonDeclaration(
                1,
                "more_action",
                mapOf("en" to "Open feature"),
                mapOf("en" to "Use the optional feature"),
                mapOf("en" to "Detailed addon information"),
            ),
        )
        val entry = RinStoreCatalogue.entries(
            listOf(candidate.copy(manifest = addon)),
            emptyList(),
            emptyList(),
            emptyList(),
        ).single()
        assertEquals(StoreMedium.ADDON, entry.medium)
        assertTrue(entry.matchesLanguages(emptySet()))
        assertTrue(entry.addonHostCompatible)
        assertTrue(entry.ready)
        assertEquals(StoreAction.INSTALL, entry.action)
    }

    @Test fun `incompatible RIN addons remain manageable and cannot replace a working version`() {
        val addon = manifest.copy(
            schema = 2,
            medium = "addon",
            extensionLib = 1.0,
            sources = emptyList(),
            addon = eu.kanade.tachiyomi.data.rin.RinAddonDeclaration(
                1,
                "more_action",
                mapOf("en" to "Open feature"),
                mapOf("en" to "Use the optional feature"),
                mapOf("en" to "Detailed addon information"),
            ),
            minHostVersionCode = eu.kanade.tachiyomi.BuildConfig.VERSION_CODE.toLong() + 1,
        )
        val installed = record.copy(manifest = addon)
        val entry = RinStoreCatalogue.entries(emptyList(), listOf(installed), emptyList(), emptyList()).single()
        assertFalse(entry.ready)
        assertFalse(entry.addonHostCompatible)
        assertEquals(StoreAction.MANAGE, entry.action)
        val working = installed.copy(manifest = addon.copy(minHostVersionCode = null))
        val upgrade = candidate.copy(manifest = addon.copy(versionCode = 2))
        assertFalse(RinUpdatePolicy.accepts(upgrade, working))
        assertTrue(RinStoreCatalogue.entries(listOf(upgrade), listOf(working), emptyList(), emptyList()).single().ready)
    }
}
