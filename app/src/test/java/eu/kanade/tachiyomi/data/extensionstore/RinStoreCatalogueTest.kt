package eu.kanade.tachiyomi.data.extensionstore

import eu.kanade.tachiyomi.data.layer.MangaLayerChoice
import eu.kanade.tachiyomi.data.layer.MangaLayerDeclaration
import eu.kanade.tachiyomi.data.layer.MangaLayerStatus
import eu.kanade.tachiyomi.data.layer.MangaLayerTarget
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
        "version.apk",
        format = "apk",
    )
    private val candidate = RinEntry(
        manifest.copy(versionCode = 2, versionName = "16.2"),
        "https://example.invalid/package.apk",
        "e".repeat(64),
        record.signer,
        record.repository,
        format = "apk",
    )

    private val bridgeManifest = RinManifest(
        schema = 2,
        packageName = "test.extension.bridge",
        name = "Compact home",
        versionCode = 1,
        versionName = "1.0",
        medium = "layer",
        extensionLib = 1.0,
        entryPoints = emptyList(),
        sources = emptyList(),
        hashes = emptyMap(),
        mangaLayer = MangaLayerDeclaration(
            1,
            listOf(MangaLayerTarget(
                packageName = manifest.packageName,
                sourceId = 901,
                signer = record.signer,
                minVersionCode = 1,
                maxVersionCode = 2,
                capabilities = setOf("home"),
                medium = "anime",
            )),
        ),
    )
    private val bridgeCandidate = candidate.copy(
        manifest = bridgeManifest,
        url = "https://example.invalid/bridge.apk",
        format = "apk",
    )

    @Test fun `store offers new bridge capabilities for review while background updates retain safe candidates`() {
        val installed = record.copy(manifest = bridgeManifest)
        val stable = bridgeCandidate.copy(manifest = bridgeManifest.copy(versionCode = 2, versionName = "1.1"))
        val expanded = expandedBridge(3)

        fun row(vararg candidates: RinEntry) =
            RinStoreCatalogue.entries(candidates.toList(), listOf(installed), emptyList(), emptyList()).single()

        val review = row(stable, expanded)
        assertEquals(StoreAction.UPDATE, review.action)
        assertEquals(expanded, review.rinCandidate)
        assertTrue(review.requiresBridgeScopeReview)
        assertFalse(review.automaticUpdatePending)
        assertTrue(RinUpdatePolicy.updates(listOf(expanded), listOf(installed)).isEmpty())
        assertEquals(listOf(stable), RinUpdatePolicy.updates(listOf(stable, expanded), listOf(installed)))
        assertFalse(row(stable).requiresBridgeScopeReview)
        assertTrue(row(stable).automaticUpdatePending)

        val additions = expanded.manifest.addedLayerCapabilities(installed.manifest)
        assertEquals(listOf(901L, 902L), additions.map { it.sourceId })
        assertEquals(listOf(setOf("catalog"), setOf("home")), additions.map { it.capabilities })
    }

    @Test fun `bridge review consent only authorizes the exact reviewed revision and signer`() {
        val installed = record.copy(manifest = bridgeManifest, signerHistory = setOf(record.signer, "a".repeat(64)))
        val expanded = expandedBridge(2)
        assertTrue(RinUpdatePolicy.matchesScopeApproval(expanded, installed, expanded))
        listOf(
            null,
            expanded.copy(sha256 = "f".repeat(64)),
            expanded.copy(url = "https://example.invalid/same-bridge.apk"),
            expanded.copy(manifest = expanded.manifest.copy(versionName = "different")),
        ).forEach { assertFalse(RinUpdatePolicy.matchesScopeApproval(expanded, installed, it)) }

        listOf(
            expanded.copy(repository = "https://other.example/index.json"),
            expanded.copy(signer = "a".repeat(64)),
            expanded.copy(requiresAndroid = true),
            expanded.copy(format = "rin"),
            expanded.copy(manifest = expanded.manifest.copy(versionCode = 1)),
            expanded.copy(manifest = expanded.manifest.copy(packageName = "test.extension.other")),
        ).forEach { assertFalse(RinUpdatePolicy.matchesScopeApproval(it, installed, it)) }
        assertFalse(RinUpdatePolicy.matchesScopeApproval(expanded, installed.copy(isShared = true), expanded))
        assertFalse(
            RinUpdatePolicy.matchesScopeApproval(expanded, installed.copy(catalogueConsent = record.signer), expanded),
        )
    }

    private fun expandedBridge(version: Long): RinEntry {
        val declaration = requireNotNull(bridgeManifest.mangaLayer)
        val base = declaration.targets.single()
        return bridgeCandidate.copy(
            manifest = bridgeManifest.copy(
                versionCode = version,
                versionName = "1.$version",
                mangaLayer = declaration.copy(
                    targets = listOf(
                        base.copy(capabilities = setOf("home", "catalog")),
                        base.copy(packageName = "test.extension.other", sourceId = 902),
                    ),
                ),
            ),
        )
    }

    @Test fun `catalogue previews only link a bridge to a matching source identity publisher and version`() {
        fun related(base: RinEntry): List<StoreBridge> {
            val entries = RinStoreCatalogue.entries(listOf(base, bridgeCandidate), emptyList(), emptyList(), emptyList())
            val source = entries.single { it.medium == StoreMedium.ANIME }
            return StoreBridges.bySource(entries, choices = emptyList(), adult = false)[source.id].orEmpty()
        }
        assertEquals(StoreBridgeState.NEEDS_SOURCE, related(candidate).single().state)
        assertTrue(related(candidate.copy(signer = "b".repeat(64))).isEmpty())
        assertTrue(related(candidate.copy(manifest = candidate.manifest.copy(versionCode = 3))).isEmpty())
        assertTrue(related(candidate.copy(manifest = candidate.manifest.copy(sources = emptyList()))).isEmpty())
    }

    @Test fun `catalogue search returns the source while bridge filter and installed state remain distinct`() {
        val bridge = record.copy(manifest = bridgeManifest, format = "apk")
        val nextBridge = bridgeCandidate.copy(manifest = bridgeManifest.copy(versionCode = 2, versionName = "1.1"))
        val entries = RinStoreCatalogue.entries(listOf(nextBridge), listOf(record, bridge), emptyList(), emptyList())
        val source = entries.single { it.medium == StoreMedium.ANIME }
        val patch = entries.single { it.medium == StoreMedium.MANGA_LAYER }
        val active = MangaLayerChoice(
            patch.packageName, patch.name, source.packageName, 901, "home",
            MangaLayerStatus.ACTIVE, "anime", "Example edition",
        )
        val bridges = StoreBridges.bySource(entries, choices = listOf(active), adult = false)
        assertEquals(StoreBridgeState.ACTIVE, bridges.getValue(source.id).single().state)
        val noMatch = StoreBridges.bySource(
            entries.map { if (it == source) source.copy(rinRecord = record.copy(signer = "b".repeat(64))) else it },
            choices = emptyList(),
            adult = false,
        )
        assertEquals(StoreBridgeState.INCOMPATIBLE, noMatch.getValue(source.id).single().state)
        assertEquals(
            listOf(patch, source),
            StorePresentation.select(entries, StoreShelf.CATALOGUE, null, StoreActivation.ALL, "Compact home", setOf("it"), false, bridges),
        )
        assertEquals(
            listOf(patch),
            StorePresentation.select(entries, StoreShelf.CATALOGUE, StoreMedium.MANGA_LAYER, StoreActivation.ALL, "", setOf("it"), false, bridges),
        )
    }

    @Test fun `catalogues with only bridges stay visible until a matching source can carry the bridge`() {
        val entries = RinStoreCatalogue.entries(listOf(candidate, bridgeCandidate), emptyList(), emptyList(), emptyList())
        val source = entries.single { it.medium == StoreMedium.ANIME }
        val bridge = entries.single { it.medium == StoreMedium.MANGA_LAYER }
        fun visible(all: List<StoreEntry>) = StorePresentation.select(
            all,
            StoreShelf.CATALOGUE,
            null,
            StoreActivation.ALL,
            "",
            setOf("it"),
            false,
            StoreBridges.bySource(all, emptyList(), adult = false),
        )
        assertEquals(listOf(bridge), visible(listOf(bridge)))
        assertEquals(listOf(source), visible(entries))
    }

    @Test fun `bridge approval requires the installed source identity and unchanged APK offer`() {
        val entries = RinStoreCatalogue.entries(listOf(bridgeCandidate), listOf(record), emptyList(), emptyList())
        val base = entries.single { it.medium == StoreMedium.ANIME }
        val bridge = entries.single { it.privateOnly }
        val plan = ApprovedBridgeInstall(
            base.packageName, base.medium, manifest.versionCode, record.signer, StoreInstallMode.PRIVATE,
            listOf(bridgeCandidate), createdAt = 1L,
        )
        val restored = RinArchive.json.decodeFromString<ApprovedBridgeInstall>(RinArchive.json.encodeToString(plan))
        assertEquals(listOf(bridge), restored.compatibleBridges(base, entries))
        assertTrue(restored.compatibleBridges(base.copy(installed = false), entries).isEmpty())
        assertTrue(restored.compatibleBridges(base.copy(action = StoreAction.TRUST), entries).isEmpty())
        assertTrue(restored.compatibleBridges(base.copy(rinRecord = record.copy(isShared = true)), entries).isEmpty())
        val shared = base.copy(rinRecord = record.copy(isShared = true))
        assertEquals(listOf(bridge), restored.copy(mode = StoreInstallMode.SYSTEM).compatibleBridges(shared, entries))
        assertTrue(restored.compatibleBridges(base.copy(rinRecord = record.copy(signer = "b".repeat(64))), entries).isEmpty())
        val differentSource = record.copy(manifest = manifest.copy(sources = listOf(RinSource(42, "Other", "it"))))
        assertTrue(restored.compatibleBridges(base.copy(rinRecord = differentSource), entries).isEmpty())
        val changed = bridge.copy(rinCandidate = bridgeCandidate.copy(sha256 = "f".repeat(64)))
        assertTrue(restored.compatibleBridges(base, listOf(base, changed)).isEmpty())
    }

    @Test fun `matching duplicate catalogues coalesce while conflicting bridge publishers require manual choice`() {
        val differentPublisher = bridgeCandidate.copy(signer = "b".repeat(64))
        val mirror = bridgeCandidate.copy(repository = "https://mirror.invalid/index.json")
        fun installable(vararg offers: RinEntry): List<StoreEntry> {
            val entries = RinStoreCatalogue.entries(listOf(candidate) + offers, emptyList(), emptyList(), emptyList())
            val base = entries.single { !it.privateOnly }
            val bridges = StoreBridges.bySource(entries, emptyList(), adult = false)
            return StoreBridges.forInstallation(bridges.getValue(base.id))
        }
        assertEquals(1, installable(bridgeCandidate, mirror).size)
        assertTrue(installable(bridgeCandidate, differentPublisher).isEmpty())
        val competingBridge = bridgeCandidate.copy(manifest = bridgeManifest.copy(packageName = "test.other.bridge"))
        assertTrue(installable(bridgeCandidate, competingBridge).isEmpty())
    }

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
        val sameCatalogueDifferentPublisher = candidate.copy(signer = "b".repeat(64))
        val entries = RinStoreCatalogue.entries(
            listOf(older, candidate, other, sameCatalogueDifferentPublisher),
            emptyList(),
            emptyList(),
            emptyList(),
        )
        assertEquals(3, entries.size)
        assertEquals(3, entries.map { it.id }.distinct().size)
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

    @Test fun `catalogue ignores retired packages in cache and local inventory`() {
        val legacy = record.copy(format = "rin")
        val legacyCandidate = candidate.copy(format = "rin")
        val entries = RinStoreCatalogue.entries(listOf(legacyCandidate, candidate), listOf(legacy), emptyList(), emptyList())
        assertEquals(candidate, entries.single().rinCandidate)
        assertFalse(entries.single().installed)
    }

    @Test fun `APK addons use the addon shelf without source languages`() {
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

    @Test fun `incompatible APK addons remain manageable and cannot replace a working version`() {
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
