package eu.kanade.tachiyomi.data.rin

import eu.kanade.tachiyomi.data.layer.MangaLayerDeclaration
import eu.kanade.tachiyomi.data.layer.MangaLayerTarget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SourceLayerPackageTest {
    private val authorSigner = "a".repeat(64)
    private val baseSigner = "b".repeat(64)
    private val archiveHash = "c".repeat(64)
    private val otherSigner = "d".repeat(64)
    private val repository = "https://example.invalid/repo"
    private val manga = MangaLayerTarget(
        packageName = "test.original.manga",
        sourceId = 42001L,
        signer = baseSigner,
        minVersionCode = 106030,
        maxVersionCode = 106045,
        capabilities = setOf("home", "reader"),
    )

    @Test
    fun `legacy manga envelope accepts an omitted target medium`() {
        val manifest = RinArchive.json.decodeFromString<RinManifest>(
            """
            {
              "schema":2, "packageName":"test.publisher.layer", "name":"Example layer",
              "versionCode":2, "versionName":"1.0.2", "medium":"manga-layer", "extensionLib":1.0,
              "entryPoints":["test.publisher.layer.Factory"], "sources":[], "hashes":{},
              "mangaLayer":{"api":1,"targets":[{
                "packageName":"test.original.manga", "sourceId":42001,
                "signer":"$baseSigner", "minVersionCode":106030, "maxVersionCode":106045,
                "capabilities":["home","reader"]
              }]}
            }
            """.trimIndent(),
        )
        assertEquals(manga, manifest.mangaLayer?.targets?.single())
        assertTrue(manifest.isLayer)
        assertTrue(RinRepository.compatible(manifest, "apk"))
        assertFalse(RinRepository.compatible(manifest, "rin"))
        assertTrue(manifest.preservesSourceIds(emptyList()))
        assertFalse(manifest.preservesSourceIds(listOf(manga.sourceId)))
    }

    @Test
    fun `generic envelope covers separate manga anime news and TV capability scopes`() {
        val anime = target("anime", setOf("catalog", "player"))
        val news = target("news", setOf("feed", "article", "search"))
        val tv = target("tv", setOf("catalog", "guide", "player")).copy(
            requires = mapOf("guide" to setOf("catalog")),
        )
        val generic = manifest(targets = listOf(manga, anime, news, tv))
        assertTrue(generic.validMangaLayer())
        assertTrue(RinRepository.compatible(generic, "apk"))
        assertFalse(generic.copy(medium = RinManifest.MANGA_LAYER).validMangaLayer())
        assertTrue(manga.supports(106030))
        assertTrue(manga.supports(106045))
        assertFalse(manga.supports(106029))
        assertFalse(manga.supports(106046))
    }

    @Test
    fun `layers reject malformed publisher and capability scopes`() {
        listOf(
            manga.copy(signer = "unsigned"),
            manga.copy(capabilities = setOf("guide")),
            manga.copy(requires = mapOf("reader" to setOf("player"))),
            manga.copy(sourceId = 0),
            manga.copy(maxVersionCode = 106029),
            target("news", setOf("feed")).copy(sourceId = 10),
            target("tv", setOf("reader")),
            target("anime", setOf("guide")),
            target("unknown", setOf("catalog")),
            manga.copy(packageName = "test.publisher.layer"),
        ).forEach {
            assertFalse(manifest(targets = listOf(it)).validMangaLayer(), it.toString())
        }
        assertFalse(manifest(targets = listOf(manga, manga)).validMangaLayer())
        assertFalse(manifest().copy(schema = 1).validMangaLayer())
        assertFalse(manifest().copy(extensionLib = 2.0).validMangaLayer())
        assertFalse(manifest().copy(mangaLayer = null).validMangaLayer())
        assertFalse(manifest().copy(sources = listOf(RinSource(14, "New source", "it"))).validMangaLayer())
        assertFalse(manifest().copy(optionalFactories = listOf("test.publisher.OtherFactory")).validMangaLayer())
    }

    @Test
    fun `automatic layer update must preserve author origin and the approved scope`() {
        val original = manifest()
        val installed = record(original)
        val update = entry(original.copy(versionCode = 3, versionName = "1.0.3"))
        assertTrue(RinUpdatePolicy.accepts(update, installed))
        listOf(
            update.copy(signer = otherSigner),
            update.copy(repository = "https://elsewhere.invalid/repo"),
            update.copy(requiresAndroid = true),
            update.copy(format = "rin"),
            update.copy(manifest = update.manifest.copy(medium = RinManifest.MANGA_LAYER)),
            update.copy(manifest = update.manifest.withTargets(manga.copy(signer = otherSigner))),
            update.copy(manifest = update.manifest.withTargets(manga.copy(capabilities = setOf("home", "reader", "identity")))),
            update.copy(manifest = update.manifest.withTargets(manga, target("anime", setOf("player")))),
        ).forEach { assertFalse(RinUpdatePolicy.accepts(it, installed), it.toString()) }

        val narrower = update.manifest.withTargets(manga.copy(capabilities = setOf("home")))
        assertTrue(RinUpdatePolicy.accepts(entry(narrower), installed))
        assertFalse(RinUpdatePolicy.accepts(update.copy(manifest = update.manifest.copy(versionCode = 4)), record(narrower)))

        val disallowed = update.manifest.withTargets(manga, target("news", setOf("feed")))
        assertTrue(RinLocalImportPolicy.accepts(record(disallowed), installed))
    }

    @Test
    fun `same scope updates can follow newer base versions and replace a factory implementation`() {
        val tv = target("tv", setOf("catalog", "guide"))
        val original = manifest(targets = listOf(manga, tv))
        val update = original.copy(versionCode = 3, entryPoints = listOf("test.publisher.OtherFactory")).withTargets(
            tv.copy(requires = mapOf("guide" to setOf("catalog"))),
            manga.copy(minVersionCode = 106031, maxVersionCode = null),
        )
        assertTrue(RinUpdatePolicy.accepts(entry(update), record(original)))

        val declarative = original.copy(entryPoints = emptyList())
        assertFalse(RinUpdatePolicy.accepts(entry(update), record(declarative)))
        assertTrue(RinLocalImportPolicy.accepts(record(update), record(declarative)))
        assertTrue(RinUpdatePolicy.accepts(entry(update.copy(entryPoints = emptyList())), record(original)))
    }

    @Test
    fun `a rejected layer candidate does not affect a valid base update`() {
        val layer = manifest()
        val original = RinManifest(
            schema = 1,
            packageName = manga.packageName,
            name = "Original base",
            versionCode = 106031,
            versionName = "1.6.31",
            medium = "manga",
            extensionLib = 1.6,
            entryPoints = listOf("test.original.manga.Factory"),
            sources = listOf(RinSource(manga.sourceId, "Original", "it")),
            hashes = emptyMap(),
        )
        val base = record(original, signer = baseSigner)
        val nextBase = entry(original.copy(versionCode = 106032, versionName = "1.6.32"), signer = baseSigner)
        val badLayer = entry(layer.copy(versionCode = 3), signer = otherSigner)
        val nextLayer = entry(layer.copy(versionCode = 3))
        assertEquals(listOf(nextBase), RinUpdatePolicy.updates(listOf(badLayer, nextBase), listOf(base, record(layer))))
        assertEquals(
            listOf(nextBase, nextLayer),
            RinUpdatePolicy.updates(listOf(nextLayer, nextBase), listOf(base, record(layer))),
        )
    }

    private fun target(medium: String, capabilities: Set<String>) = MangaLayerTarget(
        packageName = "test.original.$medium",
        sourceId = if (medium == "news") 0 else 71,
        signer = baseSigner,
        minVersionCode = 1,
        capabilities = capabilities,
        medium = medium,
    )

    private fun manifest(targets: List<MangaLayerTarget> = listOf(manga)) = RinManifest(
        schema = 2,
        packageName = "test.publisher.layer",
        name = "Example layer",
        versionCode = 2,
        versionName = "1.0.2",
        medium = RinManifest.LAYER,
        extensionLib = 1.0,
        entryPoints = listOf("test.publisher.layer.Factory"),
        sources = emptyList(),
        hashes = emptyMap(),
        mangaLayer = MangaLayerDeclaration(1, targets),
    )

    private fun RinManifest.withTargets(vararg targets: MangaLayerTarget) =
        copy(mangaLayer = MangaLayerDeclaration(1, targets.toList()))

    private fun record(manifest: RinManifest, signer: String = authorSigner) =
        RinRecord(manifest, signer, archiveHash, repository, "$archiveHash.apk", format = "apk")

    private fun entry(manifest: RinManifest, signer: String = authorSigner) =
        RinEntry(manifest, "$repository/example.apk", archiveHash, signer, repository, format = "apk")
}
