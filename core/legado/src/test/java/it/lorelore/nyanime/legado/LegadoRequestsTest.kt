package it.lorelore.nyanime.legado

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LegadoRequestsTest {
    @Test
    fun aScriptPortFailureDoesNotExposeItsPrivateDiagnostic() = runTest {
        val failure = try {
            LegadoRequests.resolve(
                LegadoRequests.parse("/search?n={{page*10}}"),
                base,
                origins,
                scripts = LegadoScript { _, _, _ -> error("private-test-token") },
            )
            null
        } catch (failure: LegadoRuleException) {
            failure
        }
        assertEquals("request_execution", failure?.capability)
        assertEquals(null, failure?.cause)
        assertFalse(failure.toString().contains("private-test-token"))
    }
    private val base = "https://source.invalid/base/"
    private val origins = setOf(base)

    @Test
    fun aRelativeSearchEncodesTheQueryWithoutAllowingItToInjectAnotherParameter() = runTest {
        val request =
            LegadoRequests.resolve(
                LegadoRequests.parse("/search?q={{key}}&page={{page}}"),
                base,
                origins,
                "space & 日本",
                3,
            )
        assertEquals(
            "https://source.invalid/search?q=space+%26+%E6%97%A5%E6%9C%AC&page=3",
            request.url,
        )
        assertEquals("GET", request.method)
        assertEquals(null, request.body)
    }

    @Test
    fun formBodyAndLegacyCharsetStayExplicit() = runTest {
        val request =
            LegadoRequests.resolve(
                LegadoRequests.parse(
                    """/search,{"method":"POST","charset":"gbk","body":"q={{key}}&page={{page}}"}""",
                ),
                base,
                origins,
                "中文",
                2,
            )
        assertEquals("q=%D6%D0%CE%C4&page=2", request.body)
        assertEquals("GBK", request.charset)
        assertEquals("application/x-www-form-urlencoded; charset=GBK", request.contentType)
    }

    @Test
    fun jsonBodiesEscapeQuotesAndNewlinesInsteadOfUrlEncodingThem() = runTest {
        val definition = """/search,{"method":"POST","body":"{\"query\":\"{{key}}\"}"}"""
        val request =
            LegadoRequests.resolve(LegadoRequests.parse(definition), base, origins, "a\"b\nc")
        val body = kotlinx.serialization.json.Json.parseToJsonElement(request.body!!) as JsonObject
        assertEquals(JsonPrimitive("a\"b\nc"), body["query"])
        assertEquals("application/json; charset=UTF-8", request.contentType)
    }

    @Test
    fun headerOverridesAreCaseInsensitiveAndCredentialsDoNotCrossOrigins() = runTest {
        val private = mapOf("Authorization" to "private-test-token", "X-Flag" to "old")
        val template = LegadoRequests.parse("""/one,{"headers":{"x-flag":"new"}}""")
        val local = LegadoRequests.resolve(template, base, origins, inheritedHeaders = private)
        assertEquals(
            mapOf("Authorization" to "private-test-token", "x-flag" to "new"),
            local.headers,
        )
        assertFalse(local.toString().contains("private-test-token"))
        val secondary =
            LegadoRequests.resolve(
                LegadoRequests.parse("https://images.invalid/cover"),
                base,
                origins + "https://images.invalid",
                inheritedHeaders = private,
            )
        assertTrue(secondary.headers.isEmpty())
    }

    @Test
    fun anUndeclaredOriginOrInlineCredentialIsRejectedBeforeAnyBrokerCanRun() = runTest {
        for (
        url in
        listOf(
            "https://other.invalid/a",
            "//other.invalid/a",
            "https://u:p@source.invalid/a",
            "file:///private/file",
        )
        ) {
            val failure =
                try {
                    LegadoRequests.resolve(LegadoRequests.parse(url), base, origins)
                    null
                } catch (failure: LegadoRuleException) {
                    failure
                }
            assertTrue(failure != null)
            assertFalse(failure.toString().contains("u:p"))
        }
    }

    @Test
    fun expressionEvaluationIsExplicitAndReceivesNoHeadersOrSession() = runTest {
        val template = LegadoRequests.parse("/list?offset={{(page-1)*10}}")
        val failure =
            try {
                LegadoRequests.resolve(template, base, origins, page = 3)
                null
            } catch (failure: LegadoRuleException) {
                failure
            }
        assertEquals("script_not_authorized", failure?.capability)
        val request =
            LegadoRequests.resolve(
                template,
                base,
                origins,
                page = 3,
                scripts =
                LegadoScript { code, _, variables ->
                    assertEquals("(page-1)*10", code)
                    assertEquals(setOf("key", "page", "baseUrl"), variables.keys)
                    JsonPrimitive(20)
                },
            )
        assertEquals("https://source.invalid/list?offset=20", request.url)
    }

    @Test
    fun unsupportedUrlOptionsNeverDisappearDuringRequestParsing() {
        for (
        raw in
        listOf(
            """/a,{"webView":true}""",
            """/a,{"method":"DELETE"}""",
            """/a,{"headers":{"Host":"other.invalid"}}""",
        )
        ) {
            assertThrows(LegadoRuleException::class.java) { LegadoRequests.parse(raw) }
        }
    }

    @Test
    fun aGetBodyAndInvalidTemplateFailRatherThanChangingRequestSemantics() = runTest {
        for (
        raw in
        listOf(
            """/a,{"body":"q=x"}""",
            "/a?q={{key",
        )
        ) {
            val failure =
                try {
                    LegadoRequests.resolve(LegadoRequests.parse(raw), base, origins)
                    null
                } catch (failure: LegadoRuleException) {
                    failure
                }
            assertTrue(failure != null)
        }
    }
}
