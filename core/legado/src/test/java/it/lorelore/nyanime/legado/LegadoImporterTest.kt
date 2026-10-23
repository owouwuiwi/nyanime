package it.lorelore.nyanime.legado

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CancellationException

class LegadoImporterTest {
    @Test
    fun extremelyWideDocumentsFailBeforeConstructingTheJsonTree() {
        val raw = "[" + List(20_002) { "0" }.joinToString(",") + "]"
        val failure = assertThrows(LegadoFileException::class.java) {
            LegadoImporter.read(ByteArrayInputStream(raw.toByteArray()))
        }
        assertEquals("document_complexity", failure.reason)
    }

    @Test
    fun readFailuresDoNotExposeProviderDiagnostics() {
        val stream =
            object : InputStream() {
                override fun read(): Int = throw IOException("private-fixture-credential")
            }
        val failure =
            assertThrows(LegadoFileException::class.java) {
                LegadoImporter.read(stream)
            }
        assertEquals("read", failure.reason)
        assertFalse(failure.toString().contains("private-fixture-credential"))
    }

    @Test
    fun tooManySourcesAndOtherMediaAreReportedExplicitly() {
        assertEquals(
            "source_count",
            assertThrows(LegadoFileException::class.java) {
                read(JsonArray(List(LegadoImporter.MAX_SOURCES + 1) { fixture() }))
            }
                .reason,
        )
        val audio = read(fixture(mapOf("bookSourceType" to JsonPrimitive(1)))).single()
        assertTrue(audio.issues.any { it.capability == "text_sources_only" && it.blocking })
    }

    private fun fixture(extra: Map<String, JsonElement> = emptyMap()): JsonObject {
        val normal = buildJsonObject {
            put("bookSourceUrl", "https://source.invalid/")
            put("bookSourceName", "Synthetic books")
            put("bookSourceType", 0)
            put("searchUrl", "/search?q={{key}}&page={{page}}")
            putJsonObject("ruleSearch") {
                put("bookList", "@css:.book")
                put("name", "@css:.title@text")
                put("bookUrl", "@css:a@href")
            }
            putJsonObject("ruleBookInfo") { put("name", "@css:h1@text") }
            putJsonObject("ruleToc") {
                put("chapterList", "@css:.chapters a")
                put("chapterName", "text")
                put("chapterUrl", "href")
            }
            putJsonObject("ruleContent") { put("content", "@css:#chapter@html") }
        }
        return JsonObject(normal + extra)
    }

    private fun read(value: JsonElement) =
        LegadoImporter.read(ByteArrayInputStream(value.toString().toByteArray()))

    @Test
    fun singleSourceAndArraysProduceTheSamePlanWithoutExecution() {
        val one = read(fixture()).single()
        val array = read(JsonArray(listOf(fixture()))).single()
        assertEquals(LegadoCompatibility.COMPLETE, one.compatibility)
        assertEquals(one.uuid, array.uuid)
        assertEquals(one.fingerprint, array.fingerprint)
        assertTrue(one.fingerprint!!.matches(Regex("[a-f0-9]{64}")))
        assertEquals(8, one.rules.size)
        assertFalse(one.requiresScriptReview)
        assertEquals("GET", one.request?.method)
    }

    @Test
    fun updatesPreserveIdentityButInvalidateDefinitionFingerprint() {
        val first = read(fixture()).single()
        val renamed = read(fixture(mapOf("bookSourceName" to JsonPrimitive("New name")))).single()
        assertEquals(first.uuid, renamed.uuid)
        assertNotEquals(first.fingerprint, renamed.fingerprint)
        val shuffled = JsonObject(fixture().entries.reversed().associate { it.toPair() })
        assertEquals(first.fingerprint, read(shuffled).single().fingerprint)
    }

    @Test
    fun duplicatesAreFlaggedOnEveryRow() {
        val result = read(JsonArray(listOf(fixture(), fixture())))
        assertTrue(result.all { it.compatibility == LegadoCompatibility.BLOCKED })
        assertTrue(result.all { row -> row.issues.any { it.capability == "duplicate_in_file" } })
    }

    @Test
    fun optionalUnsupportedCapabilitiesAreExplicitAndRequiredOnesBlock() {
        val partial = read(fixture(mapOf("exploreUrl" to JsonPrimitive("/explore")))).single()
        assertEquals(LegadoCompatibility.PARTIAL, partial.compatibility)
        val unsupported = read(fixture(mapOf("loginUi" to JsonPrimitive("custom login")))).single()
        assertEquals(LegadoCompatibility.BLOCKED, unsupported.compatibility)
        val missing = read(fixture(mapOf("ruleToc" to JsonObject(emptyMap())))).single()
        assertTrue(missing.issues.any { it.field == "ruleToc.chapterUrl" && it.blocking })
    }

    @Test
    fun scriptsAreNeverRunOrAuthorizedWhileImporting() {
        val script =
            fixture(
                mapOf(
                    "ruleContent" to
                        buildJsonObject {
                            put(
                                "content",
                                "@js:throw new Error('must not execute while importing')",
                            )
                        },
                ),
            )
        val result = read(script).single()
        assertEquals(LegadoCompatibility.COMPLETE, result.compatibility)
        assertTrue(result.requiresScriptReview)
        val denied =
            read(
                fixture(
                    mapOf(
                        "ruleContent" to
                            buildJsonObject {
                                put("content", "@js:java.ajax('https://blocked.invalid/')")
                            },
                    ),
                ),
            )
                .single()
        assertEquals(LegadoCompatibility.BLOCKED, denied.compatibility)
        assertTrue(denied.issues.any { it.capability == "java_android_bridge" })
    }

    @Test
    fun privateHeadersDoNotEnterDiagnosticStrings() {
        val secret = "private-fixture-value"
        val item =
            read(
                fixture(
                    mapOf(
                        "header" to
                            buildJsonObject {
                                put("Authorization", secret)
                            },
                    ),
                ),
            )
                .single()
        assertEquals(secret, item.headers.copyForLocalStorage()["Authorization"])
        assertFalse(item.toString().contains(secret))
        assertFalse(item.headers.toString().contains(secret))
        assertFalse(item.issues.toString().contains(secret))
    }

    @Test
    fun postOptionsKeepBodyCharsetAndSeparateHeaders() {
        val result =
            read(
                fixture(
                    mapOf(
                        "searchUrl" to
                            JsonPrimitive(
                                "/search,{\"method\":\"POST\",\"body\":\"q={{key}}&p={{page}}\"," +
                                    "\"charset\":\"gbk\",\"headers\":{\"X-Session\":\"private-value\"}}",
                            ),
                    ),
                ),
            )
                .single()
        assertEquals(LegadoCompatibility.COMPLETE, result.compatibility)
        assertEquals("POST", result.request?.method)
        assertEquals("gbk", result.request?.charset)
        assertEquals("q={{key}}&p={{page}}", result.request?.body)
        assertEquals(
            "private-value",
            result.request?.headers?.copyForLocalStorage()?.get("X-Session"),
        )
        val denied =
            read(fixture(mapOf("searchUrl" to JsonPrimitive("/search,{\"webView\":true}"))))
                .single()
        assertTrue(denied.issues.any { it.capability == "unsupported_url_option" && it.blocking })
    }

    @Test
    fun ambiguousReservedAndMultilineHeadersAreBlocked() {
        for (
        header in
        listOf(
            buildJsonObject {
                put("X-Test", "a")
                put("x-test", "b")
            },
            buildJsonObject { put("Host", "elsewhere.invalid") },
            buildJsonObject { put("Authorization", "a\nb") },
        )
        ) {
            assertEquals(
                LegadoCompatibility.BLOCKED,
                read(fixture(mapOf("header" to header))).single().compatibility,
            )
        }
    }

    @Test
    fun invalidContainersEncodingAndJsonDoNotQuoteInput() {
        for (
        bytes in
        listOf(
            "not-json private-password".toByteArray(),
            byteArrayOf(0xc3.toByte()),
            "[]".toByteArray(),
            "true".toByteArray(),
        )
        ) {
            val failure =
                assertThrows(LegadoFileException::class.java) {
                    LegadoImporter.read(ByteArrayInputStream(bytes))
                }
            assertFalse(failure.toString().contains("private-password"))
        }
    }

    @Test
    fun sizeAndDepthLimitsAreEnforcedBeforeAllocatingTheWholeDocument() {
        var consumed = 0
        val unlimited =
            object : InputStream() {
                override fun read(): Int = ' '.code

                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    bytes.fill(' '.code.toByte(), offset, offset + length)
                    consumed += length
                    return length
                }
            }
        assertEquals(
            "size",
            assertThrows(LegadoFileException::class.java) {
                LegadoImporter.read(unlimited)
            }
                .reason,
        )
        assertTrue(consumed <= LegadoImporter.MAX_BYTES + 8192)
        val deep = "[".repeat(65) + "0" + "]".repeat(65)
        assertEquals(
            "nesting",
            assertThrows(LegadoFileException::class.java) {
                LegadoImporter.read(ByteArrayInputStream(deep.toByteArray()))
            }
                .reason,
        )
    }

    @Test
    fun stalledStreamsAndCancellationCannotSpin() {
        val stalled =
            object : InputStream() {
                override fun read() = 0

                override fun read(bytes: ByteArray, offset: Int, length: Int) = 0
            }
        assertEquals(
            "stream_no_progress",
            assertThrows(LegadoFileException::class.java) {
                LegadoImporter.read(stalled)
            }
                .reason,
        )
        assertThrows(CancellationException::class.java) {
            LegadoImporter.read(ByteArrayInputStream(fixture().toString().toByteArray())) {
                throw CancellationException("fixture")
            }
        }
    }

    @Test
    fun malformedRowsStayVisibleBesideValidOnes() {
        val result = read(JsonArray(listOf(fixture(), JsonPrimitive("not a source"))))
        assertEquals(LegadoCompatibility.COMPLETE, result[0].compatibility)
        assertEquals(LegadoCompatibility.BLOCKED, result[1].compatibility)
    }
}
