package it.lorelore.nyanime.legado

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.Locale

/**
 * Transient broker input. Neither headers nor payloads belong in diagnostics or shared definitions.
 */
class LegadoRequest(
    val url: String,
    val method: String,
    val body: String?,
    val charset: String,
    val headers: Map<String, String>,
    val contentType: String?,
) {
    override fun toString() = "LegadoRequest(" + method + ")"
}

class LegadoResponse(val body: String, val url: String) {
    override fun toString() = "LegadoResponse(private)"
}

/**
 * One operation gets one bounded broker session; the host owns cookies, redirects and revocation.
 */
fun interface LegadoHttp {
    suspend fun request(request: LegadoRequest): LegadoResponse
}

object LegadoRequests {
    fun parse(raw: String): LegadoRequestTemplate {
        val issues = mutableListOf<LegadoImportIssue>()
        val parsed =
            LegadoImporter.request(raw) { field, code, blocking ->
                issues += LegadoImportIssue(field, code, blocking)
            }
        if (parsed == null || issues.isNotEmpty()) {
            throw LegadoRuleException(issues.firstOrNull()?.capability ?: "web_request")
        }
        return parsed
    }

    suspend fun resolve(
        template: LegadoRequestTemplate,
        base: String,
        allowedOrigins: Set<String>,
        query: String = "",
        page: Int = 1,
        inheritedHeaders: Map<String, String> = emptyMap(),
        scripts: LegadoScript? = null,
    ): LegadoRequest = try {
        currentCoroutineContext().ensureActive()
        if (query.length > 2048 || page !in 1..1_000_000) fail("search_input")
        val charset =
            try {
                Charset.forName(template.charset)
            } catch (_: Exception) {
                fail("charset")
            }
        if (!charset.newEncoder().canEncode(query)) fail("charset_input")
        val variables = buildJsonObject {
            put("key", query)
            put("page", page)
            put("baseUrl", base)
        }
        suspend fun expand(raw: String, jsonBody: Boolean = false): String {
            val output = StringBuilder()
            var offset = 0
            for (match in expression.findAll(raw)) {
                currentCoroutineContext().ensureActive()
                output.append(raw, offset, match.range.first)
                val code = match.groupValues[1].trim()
                val value =
                    when (code) {
                        "key" ->
                            if (jsonBody) {
                                JsonPrimitive(query).toString().drop(1).dropLast(1)
                            } else {
                                URLEncoder.encode(query, charset.name())
                            }
                        "page" -> page.toString()
                        else -> {
                            val port = scripts ?: fail("script_not_authorized")
                            val result =
                                port.evaluate(code, JsonNull, variables) as? JsonPrimitive
                                    ?: fail("url_script_result")
                            result.content
                        }
                    }
                if (output.length.toLong() + value.length > 131_072) fail("request_size")
                output.append(value)
                offset = match.range.last + 1
            }
            if (output.length.toLong() + raw.length - offset > 131_072) fail("request_size")
            output.append(raw, offset, raw.length)
            if (output.contains("{{") || output.contains("}}")) fail("url_template")
            return output.toString()
        }
        val target = absolute(base, expand(template.url))
        val allowed = allowedOrigins.map(::origin).toSet()
        if (origin(target) !in allowed) fail("origin_not_authorized")
        val jsonBody = template.body?.firstOrNull { !it.isWhitespace() } in setOf('{', '[')
        val body = template.body?.let { expand(it, jsonBody) }
        if (body != null && !charset.newEncoder().canEncode(body)) fail("charset_input")
        if (jsonBody && body != null) LegadoInput.json(body)
        val headers = linkedMapOf<String, Pair<String, String>>()
        fun merge(values: Map<String, String>) {
            values.forEach { (key, value) ->
                if (
                    !key.matches(Regex("[A-Za-z0-9-]{1,64}")) ||
                    key.lowercase(Locale.ROOT) in
                    setOf("host", "content-length", "connection") ||
                    value.length > 8192 ||
                    value.any { it == '\r' || it == '\n' || it == '\u0000' }
                ) {
                    fail("header_value")
                }
                if (value.contains("{{") || value.contains("@js:")) fail("header_templates")
                headers[key.lowercase(Locale.ROOT)] = key to value
            }
        }
        // Global source credentials must never silently leak to a declared secondary origin.
        if (origin(target) == origin(base)) merge(inheritedHeaders)
        merge(template.headers.copyForLocalStorage())
        if (headers.size > 100 || template.method !in setOf("GET", "POST")) fail("request_options")
        if (template.method == "GET" && body != null) fail("get_body")
        val contentType =
            if (template.method == "POST") {
                headers["content-type"]?.second
                    ?: if (jsonBody) {
                        "application/json; charset=" + charset.name()
                    } else {
                        "application/x-www-form-urlencoded; charset=" + charset.name()
                    }
            } else {
                null
            }
        currentCoroutineContext().ensureActive()
        LegadoRequest(
            target,
            template.method,
            body,
            charset.name(),
            headers.values.associate { it },
            contentType,
        )
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (failure: LegadoRuleException) {
        throw failure
    } catch (_: Exception) {
        throw LegadoRuleException("request_execution")
    }

    fun absolute(base: String, reference: String): String {
        if (reference.length > 8192) fail("web_request")
        val uri =
            try {
                URI(base).resolve(reference)
            } catch (_: Exception) {
                fail("web_request")
            }
        if (
            uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https") ||
            uri.host.isNullOrBlank() ||
            uri.userInfo != null ||
            uri.port !in -1..65535
        ) {
            fail("web_request")
        }
        return uri.toASCIIString().substringBefore('#').also {
            if (it.length > 8192) fail("web_request")
        }
    }

    fun origin(raw: String): String {
        val uri =
            try {
                URI(raw)
            } catch (_: Exception) {
                fail("web_origin")
            }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (
            scheme !in setOf("http", "https") ||
            uri.host.isNullOrBlank() ||
            uri.userInfo != null ||
            uri.port !in -1..65535
        ) {
            fail("web_origin")
        }
        val port = if (uri.port == -1) (if (scheme == "https") 443 else 80) else uri.port
        return scheme + "://" + uri.host.lowercase(Locale.ROOT) + ":" + port
    }

    private val expression = Regex("""\{\{(.*?)}}""", RegexOption.DOT_MATCHES_ALL)

    private fun fail(code: String): Nothing = throw LegadoRuleException(code)
}
