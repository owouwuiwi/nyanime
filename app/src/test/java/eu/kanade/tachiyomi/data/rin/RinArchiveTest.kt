package eu.kanade.tachiyomi.data.rin

import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RinArchiveTest {
    @Test fun `TV executable payload is signed separately from legacy source APIs`() {
        val live = manifest.copy(schema = 2, medium = "tv", extensionLib = 1.0)
        assertEquals(live, RinArchive.verify(archive(live), signer))
        assertTrue(RinRepository.compatible(live))
        listOf(live.copy(schema = 1), live.copy(sources = emptyList()), live.copy(entryPoints = emptyList()))
            .forEach { invalid ->
                assertThrows(IllegalArgumentException::class.java) { RinArchive.verify(archive(invalid), signer) }
                assertFalse(RinRepository.compatible(invalid))
            }
        assertFalse(RinRepository.compatible(live.copy(extensionLib = 2.0)))
    }

    @Test fun `bundle URLs accept RIN artifacts instead of requiring a JSON catalogue`() {
        assertTrue(RinArchive.validBundleUrl("https://example.invalid/modules/example-v2.rin"))
        assertTrue(RinArchive.validBundleUrl("https://cdn.example.invalid/download/abc?token=temporary"))
    }

    @Test fun `bundle URLs reject insecure transports credentials fragments and malformed addresses`() {
        listOf(
            "http://example.invalid/example.rin",
            "file:///example.rin",
            "https://user:password@example.invalid/example.rin",
            "https://example.invalid/example.rin#other",
            "https://example.invalid",
            "https:///example.rin",
            "invalid address",
        ).forEach { assertFalse(RinArchive.validBundleUrl(it), it) }
    }

    private val certificate = javaClass.getResourceAsStream("/rin/test-publisher.der")!!.readBytes()
    private val keyBytes = javaClass.getResourceAsStream("/rin/test-key.pk8")!!.readBytes()
    private val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyBytes))
    private val signer = RinArchive.sha256(certificate)
    private val payload = mapOf("classes.dex" to "test dex".toByteArray(), "assets/example.json" to "{}".toByteArray())
    private val manifest = RinManifest(
        1, "test.extension.video", "Example video source", 2, "16.2", "anime", 16.0,
        listOf("test.extension.video.ExampleSource"), listOf(RinSource(7770000000000000123L, "Example source", "it")),
        hashes = payload.mapValues { RinArchive.sha256(it.value) },
    )

    private fun archive(
        m: RinManifest = manifest,
        contents: Map<String, ByteArray> = payload,
        corruptSignature: Boolean = false,
    ): File {
        val file = File.createTempFile("rin-test-", ".rin").apply { deleteOnExit() }
        val raw = RinArchive.json.encodeToString(m).toByteArray()
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(key)
            update(raw)
            sign()
        }
        if (corruptSignature) signature[0] = (signature[0].toInt() xor 1).toByte()
        ZipOutputStream(file.outputStream()).use { zip ->
            val all =
                contents +
                    mapOf("manifest.json" to raw, "META-INF/rin.cert" to certificate, "META-INF/rin.sig" to signature)
            all.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }

    @Test fun `addon resources and optional host range are authenticated without source identities`() {
        val contents =
            payload +
                mapOf(
                    "resources.arsc" to byteArrayOf(1),
                    "AndroidManifest.xml" to byteArrayOf(2),
                    "res/drawable/example.xml" to byteArrayOf(3),
                )
        val declaration =
            RinAddonDeclaration(
                1,
                "more_action",
                mapOf("en" to "Open utility"),
                mapOf("en" to "Manage your devices"),
                mapOf("en" to "A detailed optional utility"),
            )
        val addon = manifest.copy(
            schema = 2,
            medium = "addon",
            extensionLib = 1.0,
            sources = emptyList(),
            addon = declaration,
            minHostVersionCode = 162,
            minHostVersionName = "0.29.0.5",
            hashes = contents.mapValues {
                RinArchive.sha256(it.value)
            },
        )
        assertEquals(addon, RinArchive.verify(archive(addon, contents), signer))
        assertTrue(addon.preservesSourceIds(emptyList()))
        assertTrue(addon.hostVersions.supports(162))
        assertFalse(addon.hostVersions.supports(161))
        assertThrows(IllegalArgumentException::class.java) {
            RinArchive.verify(
                archive(
                    addon,
                    contents + ("resources.arsc" to byteArrayOf(9)),
                ),
                signer,
            )
        }
        listOf(
            addon.copy(schema = 1),
            addon.copy(sources = manifest.sources),
            addon.copy(maxHostVersionCode = 161),
            addon.copy(addon = declaration.copy(api = 2)),
            addon.copy(
                entryPoints =
                addon.entryPoints + "test.extension.Second",
            ),
        )
            .forEach {
                assertThrows(IllegalArgumentException::class.java) { RinArchive.verify(archive(it, contents), signer) }
            }
    }

    @Test fun `authenticated DEX assets and exact long source identity survive packaging`() {
        val file = archive()
        assertEquals(manifest, RinArchive.verify(file, signer, RinArchive.sha256(file.readBytes())))
    }

    @Test fun `guide declaration and host requirements are signed without playable source registration`() {
        val declaration = RinGuideDeclaration(1, listOf("episode-guides.v1"), mapOf("en" to "Example guide"))
        val guide = manifest.copy(
            schema = 2,
            medium = "guide",
            extensionLib = 1.0,
            sources = emptyList(),
            guide = declaration,
            minHostVersionCode = 185,
            minHostVersionName = "0.34.0.0",
        )
        assertEquals(guide, RinArchive.verify(archive(guide), signer))
        assertFalse(guide.hostVersions.supports(184))
        assertTrue(guide.hostVersions.supports(185))
        listOf(
            guide.copy(schema = 1),
            guide.copy(sources = manifest.sources),
            guide.copy(guide = declaration.copy(api = 99)),
            guide.copy(guide = declaration.copy(capabilities = emptyList())),
            guide.copy(guide = null),
        ).forEach {
            assertThrows(IllegalArgumentException::class.java) { RinArchive.verify(archive(it), signer) }
        }
    }

    @Test fun `another publisher cannot replace a source`() {
        assertThrows(IllegalArgumentException::class.java) { RinArchive.verify(archive(), "a".repeat(64)) }
    }

    @Test fun `reading a local publisher never substitutes for full payload verification`() {
        val valid = archive()
        assertEquals(signer, RinArchive.publisher(valid))
        assertEquals(manifest, RinArchive.verify(valid, RinArchive.publisher(valid)))
        val altered = archive(corruptSignature = true)
        assertEquals(signer, RinArchive.publisher(altered))
        assertThrows(IllegalArgumentException::class.java) {
            RinArchive.verify(altered, RinArchive.publisher(altered))
        }
    }

    @Test fun `altered manifest signature is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            RinArchive.verify(archive(corruptSignature = true), signer)
        }
    }

    @Test fun `altered executable bytes are rejected before class loading`() {
        assertThrows(IllegalArgumentException::class.java) {
            RinArchive.verify(
                archive(
                    contents =
                    payload + ("classes.dex" to byteArrayOf(4)),
                ),
                signer,
            )
        }
    }

    @Test fun `unexpected executable files cannot be smuggled into the archive`() {
        assertThrows(IllegalArgumentException::class.java) {
            RinArchive.verify(
                archive(
                    contents =
                    payload + ("classes2.dex" to byteArrayOf(4)),
                ),
                signer,
            )
        }
    }

    @Test fun `signed path traversal and Android package payloads are still rejected`() {
        listOf("assets/../example.json", "AndroidManifest.xml", "classes01.dex").forEach { path ->
            val contents = payload + (path to byteArrayOf(1))
            assertThrows(IllegalArgumentException::class.java) {
                RinArchive.verify(
                    archive(
                        manifest.copy(
                            hashes = contents.mapValues {
                                RinArchive.sha256(it.value)
                            },
                        ),
                        contents,
                    ),
                    signer,
                )
            }
        }
    }

    @Test fun `duplicate source identities are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            RinArchive.verify(
                archive(
                    manifest.copy(
                        sources =
                        manifest.sources + manifest.sources,
                    ),
                ),
                signer,
            )
        }
    }

    @Test fun `signed dormant identities preserve migration without enabling extra sources`() {
        val dormant = RinSource(8880000000000000123L, "Inactive edition", "fr")
        val m = manifest.copy(dormantSources = listOf(dormant))
        assertEquals(m, RinArchive.verify(archive(m), signer))
        assertEquals(manifest.sources, m.sources)
        assertTrue(m.preservesSourceIds(listOf(manifest.sources.single().id, dormant.id)))
        assertFalse(m.preservesSourceIds(listOf(999L)))
        assertFalse(m.preservesSourceIds(emptyList()))
    }

    @Test fun `dormant identities cannot duplicate active identities or bypass validation`() {
        listOf(
            manifest.copy(dormantSources = manifest.sources),
            manifest.copy(dormantSources = listOf(RinSource(0L, "Invalid", "en"))),
            manifest.copy(dormantSources = listOf(RinSource(99L, "", "en"))),
            manifest.copy(dormantSources = (1L..129L).map { RinSource(it, "Inactive", "fr") }),
        ).forEach { m ->
            assertThrows(IllegalArgumentException::class.java) { RinArchive.verify(archive(m), signer) }
        }
    }

    @Test fun `unsupported format and missing primary dex are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            RinArchive.verify(archive(manifest.copy(schema = 2)), signer)
        }
        val contents = payload - "classes.dex"
        assertThrows(IllegalArgumentException::class.java) {
            RinArchive.verify(
                archive(
                    manifest.copy(
                        hashes = contents.mapValues {
                            RinArchive.sha256(it.value)
                        },
                    ),
                    contents,
                ),
                signer,
            )
        }
    }

    @Test fun `catalogue content hash must match the downloaded archive`() {
        assertThrows(IllegalArgumentException::class.java) { RinArchive.verify(archive(), signer, "b".repeat(64)) }
    }

    @Test fun `oversized expanded content is bounded`() {
        val contents = payload + ("assets/large" to ByteArray(RinArchive.MAX_ARCHIVE_BYTES + 1))
        assertThrows(IllegalArgumentException::class.java) {
            RinArchive.verify(
                archive(
                    manifest.copy(
                        hashes = contents.mapValues {
                            RinArchive.sha256(it.value)
                        },
                    ),
                    contents,
                ),
                signer,
            )
        }
    }
}
