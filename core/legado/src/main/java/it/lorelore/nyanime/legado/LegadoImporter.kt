package it.lorelore.nyanime.legado

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CancellationException

enum class LegadoCompatibility {
    COMPLETE,
    PARTIAL,
    BLOCKED,
}

data class LegadoImportIssue(val field: String, val capability: String, val blocking: Boolean)

/** Private input values are deliberately absent from generated toString/diagnostic text. */
class LegadoHeaders internal constructor(private val values: Map<String, String>) {
    val names: Set<String>
        get() = values.keys

    fun copyForLocalStorage(): Map<String, String> = values.toMap()

    override fun toString() = "LegadoHeaders(private)"
}

data class LegadoRequestTemplate(
    val url: String,
    val method: String,
    val body: String?,
    val charset: String,
    val headers: LegadoHeaders,
    val requiresScriptReview: Boolean,
) {
    override fun toString() = "LegadoRequestTemplate(" + method + ")"
}

class LegadoImportedSource
internal constructor(
    val name: String,
    val key: String?,
    val uuid: String?,
    val fingerprint: String?,
    val request: LegadoRequestTemplate?,
    val rules: Map<String, LegadoRule>,
    val headers: LegadoHeaders,
    val issues: List<LegadoImportIssue>,
    private val original: JsonObject?,
) {
    fun copyOriginalForLocalStorage(): JsonObject? = original

    val compatibility: LegadoCompatibility
        get() =
            when {
                issues.any { it.blocking } -> LegadoCompatibility.BLOCKED
                issues.isNotEmpty() -> LegadoCompatibility.PARTIAL
                else -> LegadoCompatibility.COMPLETE
            }

    val requiresScriptReview: Boolean
        get() = request?.requiresScriptReview == true || rules.values.any(::hasScript)

    override fun toString() = "LegadoImportedSource(" + compatibility + ")"

    private fun hasScript(rule: LegadoRule): Boolean =
        when (rule) {
            is LegadoRule.Script -> true
            is LegadoRule.Replace -> rule.input?.let(::hasScript) == true
            is LegadoRule.Combine -> rule.rules.any(::hasScript)
            is LegadoRule.Select -> false
        }
}

class LegadoFileException(val reason: String) :
    IllegalArgumentException("Invalid source document: " + reason)

/**
 * An import is only a local compatibility plan, never permission to execute, connect or activate.
 * Keep original/private headers local. An exporter must explicitly redact them and review scripts.
 */
object LegadoImporter {
    const val MAX_BYTES = 2 * 1024 * 1024
    const val MAX_SOURCES = 64
    private val json = Json { ignoreUnknownKeys = false }
    private val sections =
        linkedMapOf(
            "ruleSearch" to
                setOf(
                    "bookList",
                    "name",
                    "author",
                    "kind",
                    "wordCount",
                    "lastChapter",
                    "intro",
                    "coverUrl",
                    "bookUrl",
                    "checkKeyWord",
                ),
            "ruleBookInfo" to
                setOf(
                    "name",
                    "author",
                    "kind",
                    "wordCount",
                    "lastChapter",
                    "intro",
                    "coverUrl",
                    "tocUrl",
                ),
            "ruleToc" to
                setOf(
                    "chapterList",
                    "chapterName",
                    "chapterUrl",
                    "isVip",
                    "updateTime",
                    "nextTocUrl",
                ),
            "ruleContent" to setOf("content", "nextContentUrl", "replaceRegex"),
        )
    private val required =
        setOf(
            "ruleSearch.bookList",
            "ruleSearch.name",
            "ruleSearch.bookUrl",
            "ruleToc.chapterList",
            "ruleToc.chapterName",
            "ruleToc.chapterUrl",
            "ruleContent.content",
        )
    private val metadata =
        setOf(
            "bookSourceUrl",
            "bookSourceName",
            "bookSourceType",
            "bookSourceGroup",
            "bookSourceComment",
            "bookUrlPattern",
            "enabled",
            "enabledExplore",
            "weight",
            "customOrder",
            "lastUpdateTime",
            "respondTime",
            "header",
            "searchUrl",
            "enabledCookieJar",
            "concurrentRate",
        )
    private val blocked =
        setOf(
            "jsLib",
            "loginUrl",
            "loginUi",
            "loginCheckJs",
            "coverDecodeJs",
            "eventListener",
            "customButton",
        )

    fun read(input: InputStream, checkCancelled: () -> Unit = {}): List<LegadoImportedSource> {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var emptyReads = 0
        while (true) {
            checkCancelled()
            val count =
                try {
                    input.read(buffer)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    throw LegadoFileException("read")
                }
            if (count < 0) break
            if (count == 0) {
                if (++emptyReads > 16) throw LegadoFileException("stream_no_progress")
                continue
            }
            emptyReads = 0
            if (bytes.size() + count > MAX_BYTES) throw LegadoFileException("size")
            bytes.write(buffer, 0, count)
        }
        val text =
            try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray()))
                    .toString()
                    .removePrefix("\uFEFF")
            } catch (_: Exception) {
                throw LegadoFileException("encoding")
            }
        checkDepth(text)
        checkCancelled()
        val root =
            try {
                json.parseToJsonElement(text)
            } catch (_: Exception) {
                throw LegadoFileException("json")
            }
        val entries =
            when (root) {
                is JsonObject -> listOf(root)
                is JsonArray -> root.toList()
                else -> throw LegadoFileException("container")
            }
        if (entries.isEmpty() || entries.size > MAX_SOURCES) {
            throw LegadoFileException("source_count")
        }
        val parsed = entries.map {
            checkCancelled()
            source(it)
        }
        val duplicates =
            parsed.mapNotNull { it.key }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        return parsed.map {
            if (it.key !in duplicates) {
                it
            } else {
                copyWithIssue(
                    it,
                    LegadoImportIssue("bookSourceUrl", "duplicate_in_file", true),
                )
            }
        }
    }

    private fun source(raw: JsonElement): LegadoImportedSource {
        val issues = mutableListOf<LegadoImportIssue>()
        fun issue(field: String, reason: String, blocking: Boolean = true) {
            issues += LegadoImportIssue(field.take(128), reason, blocking)
        }
        val root = raw as? JsonObject
        if (root == null) {
            return LegadoImportedSource(
                "",
                null,
                null,
                null,
                null,
                emptyMap(),
                LegadoHeaders(emptyMap()),
                listOf(LegadoImportIssue("", "source_object", true)),
                null,
            )
        }
        fun text(field: String): String? {
            val value = root[field] ?: return null
            if (value == JsonNull) return null
            if (value !is JsonPrimitive || !value.isString) {
                issue(field, "string_required")
                return null
            }
            return value.content
        }
        val name = text("bookSourceName").orEmpty()
        if (name.isBlank() || name.length > 256) issue("bookSourceName", "name")
        val key = text("bookSourceUrl")?.trim()
        val base = key?.let { value ->
            runCatching { URI(value) }
                .getOrNull()
                ?.takeIf {
                    it.scheme in setOf("http", "https") &&
                        !it.host.isNullOrBlank() &&
                        it.userInfo == null &&
                        it.fragment == null &&
                        value.length <= 8192
                }
        }
        if (base == null) issue("bookSourceUrl", "web_origin")
        val type = root["bookSourceType"] as? JsonPrimitive
        if (type != null && type.intOrNull != 0) issue("bookSourceType", "text_sources_only")
        val headers = parseHeaders(root["header"], "header", ::issue)
        val request =
            text("searchUrl")?.takeIf(String::isNotBlank)?.let {
                request(it, ::issue)
            }
        if (request == null) issue("searchUrl", "search_request")
        val rules = linkedMapOf<String, LegadoRule>()
        sections.forEach { (section, known) ->
            val rawSection = root[section]
            val fields = rawSection as? JsonObject
            if (rawSection != null && rawSection != JsonNull && fields == null) {
                issue(section, "rule_object")
            }
            fields?.forEach fieldLoop@{ (field, value) ->
                val location = section + "." + field
                if (!present(value)) return@fieldLoop
                if (field !in known) {
                    issue(
                        location,
                        "unsupported_field",
                        field in setOf("init", "bookInfoInit", "webJs", "sourceRegex"),
                    )
                } else if (value !is JsonPrimitive || !value.isString) {
                    issue(location, "rule_string")
                } else if (field != "checkKeyWord") {
                    try {
                        rules[location] =
                            LegadoRules.parse(
                                if (field == "replaceRegex") {
                                    "##" + value.content
                                } else {
                                    value.content
                                },
                                list = field in setOf("bookList", "chapterList"),
                            )
                    } catch (failure: LegadoRuleException) {
                        issue(location, failure.capability)
                    }
                }
            }
        }
        required.filterNot(rules::containsKey).forEach { issue(it, "required_rule") }
        root.forEach { (field, value) ->
            if (!present(value) || field in sections || field in metadata) return@forEach
            issue(field, "unsupported_capability", field in blocked)
        }
        return LegadoImportedSource(
            name.take(256),
            key,
            key?.let {
                UUID.nameUUIDFromBytes(("legado-v1:" + it).toByteArray()).toString()
            },
            hash(canonical(root).toString()),
            request,
            rules,
            headers,
            issues.distinct(),
            root,
        )
    }

    internal fun request(
        raw: String,
        issue: (String, String, Boolean) -> Unit,
    ): LegadoRequestTemplate? {
        if (raw.length > 131_072) {
            issue("searchUrl", "request_size", true)
            return null
        }
        val boundary = Regex(""",\s*\{""").find(raw)
        val address = if (boundary == null) raw else raw.substring(0, boundary.range.first)
        val options =
            if (boundary == null) {
                JsonObject(emptyMap())
            } else {
                try {
                    json.parseToJsonElement(raw.substring(raw.indexOf('{', boundary.range.first)))
                        as? JsonObject ?: throw IllegalArgumentException()
                } catch (_: Exception) {
                    issue("searchUrl", "url_options", true)
                    return null
                }
            }
        val method =
            (options["method"] as? JsonPrimitive)?.contentOrNull?.uppercase(Locale.ROOT) ?: "GET"
        if (method !in setOf("GET", "POST")) issue("searchUrl.method", "http_method", true)
        val charset = (options["charset"] as? JsonPrimitive)?.contentOrNull ?: "utf-8"
        try {
            java.nio.charset.Charset.forName(charset)
        } catch (_: Exception) {
            issue("searchUrl.charset", "charset", true)
        }
        val body =
            options["body"]?.let {
                if (it !is JsonPrimitive || !it.isString) {
                    issue("searchUrl.body", "body_string", true)
                    null
                } else {
                    it.content
                }
            }
        options.forEach { (name, value) ->
            if (name !in setOf("method", "charset", "headers", "body") && present(value)) {
                issue("searchUrl." + name, "unsupported_url_option", true)
            }
        }
        val templates = Regex("""\{\{(.*?)}}""", RegexOption.DOT_MATCHES_ALL)
        val expressions =
            templates.findAll(address + body.orEmpty()).map { it.groupValues[1].trim() }.toList()
        val requiresScript = expressions.any { it !in setOf("key", "page") }
        expressions
            .filter { it !in setOf("key", "page") }
            .forEach {
                try {
                    LegadoRules.parse("@js:" + it)
                } catch (failure: LegadoRuleException) {
                    issue("searchUrl", failure.capability, true)
                }
            }
        val replaced = templates.replace(address, "1")
        if (replaced.contains("{{") || replaced.contains("}}")) {
            issue("searchUrl", "url_template", true)
        }
        try {
            val uri = URI(replaced)
            if (
                uri.isAbsolute &&
                (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank())
            ) {
                issue("searchUrl", "web_request", true)
            }
            if (uri.rawAuthority != null && (uri.userInfo != null || uri.host.isNullOrBlank())) {
                issue("searchUrl", "inline_credentials", true)
            }
        } catch (_: Exception) {
            issue("searchUrl", "web_request", true)
        }
        return LegadoRequestTemplate(
            address,
            method,
            body,
            charset,
            parseHeaders(options["headers"], "searchUrl.headers", issue),
            requiresScript,
        )
    }

    private fun parseHeaders(
        raw: JsonElement?,
        field: String,
        issue: (String, String, Boolean) -> Unit,
    ): LegadoHeaders {
        if (!present(raw)) return LegadoHeaders(emptyMap())
        val parsed =
            if (raw is JsonPrimitive && raw.isString) {
                try {
                    json.parseToJsonElement(raw.content)
                } catch (_: Exception) {
                    null
                }
            } else {
                raw
            }
        val headers = parsed as? JsonObject
        if (headers == null || headers.size > 100) {
            issue(field, "header_object", true)
            return LegadoHeaders(emptyMap())
        }
        val values = linkedMapOf<String, String>()
        val names = mutableSetOf<String>()
        headers.forEach { (name, value) ->
            if (
                !name.matches(Regex("[A-Za-z0-9-]{1,64}")) ||
                !names.add(name.lowercase(Locale.ROOT)) ||
                name.lowercase(Locale.ROOT) in setOf("host", "content-length", "connection") ||
                value !is JsonPrimitive ||
                !value.isString ||
                value.content.length > 8192 ||
                value.content.any { it == '\r' || it == '\n' || it == '\u0000' }
            ) {
                issue(field, "header_value", true)
            } else {
                values[name] = value.content
            }
        }
        return LegadoHeaders(values)
    }

    private fun present(value: JsonElement?): Boolean =
        value != null &&
            value != JsonNull &&
            value != JsonPrimitive("") &&
            value != JsonPrimitive(false) &&
            value != JsonArray(emptyList()) &&
            value != JsonObject(emptyMap())

    private fun canonical(value: JsonElement): JsonElement =
        when (value) {
            is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonical(it.value) })
            is JsonArray -> JsonArray(value.map(::canonical))
            else -> value
        }

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") {
            "%02x".format(it)
        }

    private fun copyWithIssue(source: LegadoImportedSource, issue: LegadoImportIssue) =
        LegadoImportedSource(
            source.name,
            source.key,
            source.uuid,
            source.fingerprint,
            source.request,
            source.rules,
            source.headers,
            source.issues + issue,
            source.copyOriginalForLocalStorage(),
        )

    /**
     * A bounded structural scan precedes the recursive JSON parser. String brackets do not count.
     */
    private fun checkDepth(text: String) {
        var quote = false
        var escaped = false
        var depth = 0
        var separators = 0
        for (character in text) {
            if (escaped) {
                escaped = false
                continue
            }
            if (quote && character == '\\') {
                escaped = true
                continue
            }
            if (character == '"') {
                quote = !quote
            } else if (!quote && character in "[{") {
                if (++depth > 64) throw LegadoFileException("nesting")
            } else if (!quote && character in "]}") {
                depth--
            } else if (!quote && character == ',') {
                if (++separators > 20_000) throw LegadoFileException("document_complexity")
            }
        }
    }
}
