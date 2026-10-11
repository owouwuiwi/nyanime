package it.lorelore.nyanime.legado

import com.google.re2j.Pattern
import it.lorelore.nyanime.studio.StudioJsonPath
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import org.jsoup.Jsoup
import javax.xml.xpath.XPathFactory

@Serializable
enum class LegadoSelectionMode {
    CSS,
    XPATH,
    JSON,
}

@Serializable
enum class LegadoCombination {
    FIRST_AVAILABLE,
    CONCATENATE,
    INTERLEAVE,
}

@Serializable
data class LegadoSelection(
    val selector: String,
    val indexes: List<Int>? = null,
    val exclusions: Set<Int> = emptySet(),
    val slice: List<Int?>? = null,
)

@Serializable
sealed interface LegadoRule {
    @Serializable
    data class Select(
        val mode: LegadoSelectionMode,
        val steps: List<LegadoSelection>,
        val attribute: String = "text",
        val reverse: Boolean = false,
    ) : LegadoRule

    @Serializable
    data class Combine(val kind: LegadoCombination, val rules: List<LegadoRule>) : LegadoRule

    @Serializable
    data class Replace(
        val input: LegadoRule?,
        val pattern: String,
        val replacement: String,
        val firstOnly: Boolean = false,
    ) : LegadoRule

    @Serializable data class Script(val input: LegadoRule?, val code: String) : LegadoRule
}

/** No source text, URL or credential is copied into a validation exception. */
class LegadoRuleException(val capability: String) :
    IllegalArgumentException("Unsupported or invalid source rule: " + capability)

/** Original format adapter. Parsing has no network, script execution, Android or user session. */
object LegadoRules {
    fun parse(raw: String, list: Boolean = false): LegadoRule {
        if (raw.isBlank() || raw.length > 131_072) fail("rule_size")
        return parsePart(raw.trim(), list, 0)
    }

    private fun parsePart(raw: String, list: Boolean, depth: Int): LegadoRule {
        if (depth > 8) fail("rule_nesting")
        if (raw.contains("@put:") || raw.contains("@get:")) fail("mutable_variables")
        val js = marker(raw, "@js:")
        if (js >= 0) {
            return script(
                raw.substring(0, js).takeIf(String::isNotBlank)?.let {
                    parsePart(it, list, depth + 1)
                },
                raw.substring(js + 4),
            )
        }
        val tag = marker(raw, "<js>")
        if (tag >= 0) {
            if (!raw.endsWith("</js>") || raw.indexOf("<js>", tag + 4) >= 0) fail("script_chain")
            return script(
                raw.substring(0, tag).takeIf(String::isNotBlank)?.let {
                    parsePart(it, list, depth + 1)
                },
                raw.substring(tag + 4, raw.length - 5),
            )
        }
        if (raw.contains("{{") || raw.contains("}}")) fail("dynamic_selector_template")
        val replacement = marker(raw, "##")
        if (replacement >= 0) {
            val pieces = raw.substring(replacement + 2).split("##", limit = 4)
            val firstOnly = pieces.size == 3 && pieces[2] == "#"
            if (pieces.size > 3 || (pieces.size == 3 && !firstOnly)) fail("regex_chain")
            val pattern = pieces[0]
            if (pattern.isEmpty() || pattern.length > 256) fail("regex_size")
            try {
                Pattern.compile(pattern)
            } catch (_: Exception) {
                fail("regex_syntax")
            }
            return LegadoRule.Replace(
                raw.substring(0, replacement).takeIf(String::isNotBlank)?.let {
                    parsePart(it, list, depth + 1)
                },
                pattern,
                pieces.getOrElse(1) { "" },
                firstOnly,
            )
        }
        val operators =
            listOf(
                "||" to LegadoCombination.FIRST_AVAILABLE,
                "&&" to LegadoCombination.CONCATENATE,
                "%%" to LegadoCombination.INTERLEAVE,
            )
        if (operators.count { marker(raw, it.first) >= 0 } > 1) fail("mixed_combination_operators")
        if (list && raw.startsWith("-") && operators.any { marker(raw, it.first) >= 0 }) {
            fail("combined_list_reversal")
        }
        operators.forEach { (operator, combination) ->
            val parts = split(raw, operator)
            if (parts.size > 1) {
                if (parts.size > 16) fail("rule_fanout")
                return LegadoRule.Combine(
                    combination,
                    parts.map { part ->
                        val inherited = explicitPrefix(raw)
                        val own = explicitPrefix(part)
                        if (inherited != null && own != null && !own.equals(inherited, true)) {
                            fail("mixed_selection_modes")
                        }
                        parsePart(
                            if (inherited != null && own == null) inherited + part else part,
                            list,
                            depth + 1,
                        )
                    },
                )
            }
        }
        var text = raw
        val reverse = list && text.startsWith("-")
        if (reverse) text = text.substring(1)
        if (text.startsWith(":") || text.startsWith("+")) fail("all_in_one_regex")
        if (text.startsWith("@json:", true) || text.startsWith("$")) {
            val path = if (text.startsWith("@json:", true)) text.substring(6) else text
            try {
                StudioJsonPath.select(JsonNull, path)
            } catch (_: Exception) {
                fail("json_path")
            }
            return LegadoRule.Select(
                LegadoSelectionMode.JSON,
                listOf(LegadoSelection(path)),
                attribute = if (list) "all" else "text",
                reverse = reverse,
            )
        }
        if (text.startsWith("@xpath:", true) || text.startsWith("//")) {
            val path = if (text.startsWith("@xpath:", true)) text.substring(7) else text
            if (path.length > 2048 || !path.startsWith("/")) fail("xpath")
            try {
                XPathFactory.newInstance().newXPath().compile(path)
            } catch (_: Exception) {
                fail("xpath")
            }
            return LegadoRule.Select(
                LegadoSelectionMode.XPATH,
                listOf(LegadoSelection(path)),
                attribute = if (list) "all" else "text",
                reverse = reverse,
            )
        }
        val css = text.startsWith("@css:", true)
        if (css) text = text.substring(5)
        val tokens = split(text, "@").toMutableList()
        var attribute = if (list) "all" else "text"
        if (tokens.isNotEmpty()) {
            val tail = tokens.last()
            if (
                tail in ATTRIBUTES ||
                (!list && tokens.size > 1 && tail.matches(Regex("[A-Za-z_][A-Za-z0-9_:-]*")))
            ) {
                attribute = tokens.removeAt(tokens.lastIndex)
            }
        }
        if (tokens.size > 16) fail("selector_chain")
        val steps =
            tokens
                .filter(String::isNotBlank)
                .map { selection(it, css) }
                .ifEmpty { listOf(LegadoSelection("")) }
        return LegadoRule.Select(LegadoSelectionMode.CSS, steps, attribute, reverse)
    }

    private fun script(input: LegadoRule?, code: String): LegadoRule.Script {
        if (code.isBlank() || code.length > 60_000) fail("script_size")
        if (Regex("""\b(java|Packages|android|JavaImporter)\s*[.(]""").containsMatchIn(code)) {
            fail("java_android_bridge")
        }
        return LegadoRule.Script(input, code)
    }

    private fun selection(raw: String, css: Boolean): LegadoSelection {
        if (raw.length > 2048) fail("selector_size")
        // Jsoup's regex pseudo-selectors use a different regex engine. Do not silently bypass
        // the bounded RE2 contract of this adapter.
        if (Regex(":matches[A-Za-z]*\\s*\\(", RegexOption.IGNORE_CASE).containsMatchIn(raw)) {
            fail("css_regex")
        }
        var token = raw
        var indexes: List<Int>? = null
        var exclusions = emptySet<Int>()
        var slice: List<Int?>? = null
        val suffix = Regex("""\[((?:!?-?\d+)(?::[-\d]*){0,2})]$""").find(token)
        if (suffix != null) {
            val value = suffix.groupValues[1]
            token = token.substring(0, suffix.range.first)
            val parts = value.removePrefix("!").split(":")
            if (value.startsWith("!")) {
                exclusions = parts.map { it.toIntOrNull() ?: fail("selector_exclusion") }.toSet()
            } else if (parts.size == 1) {
                indexes = listOf(parts.single().toIntOrNull() ?: fail("selector_index"))
            } else {
                slice = parts.map { part ->
                    if (part.isBlank()) null else part.toIntOrNull() ?: fail("selector_slice")
                }
                if (slice.size == 3 && slice[2] == 0) fail("selector_slice")
            }
        }
        if (!css) {
            val excluded = token.indexOf('!')
            if (excluded >= 0) {
                if (exclusions.isNotEmpty()) fail("selector_exclusion")
                exclusions =
                    token
                        .substring(excluded + 1)
                        .split(":")
                        .map { it.toIntOrNull() ?: fail("selector_exclusion") }
                        .toSet()
                token = token.substring(0, excluded)
            }
            val indexed = Regex("""\.-?\d+$""").find(token)
            if (indexed != null) {
                if (indexes != null || slice != null) fail("selector_index")
                indexes =
                    listOf(
                        token.substring(indexed.range.first + 1).toIntOrNull()
                            ?: fail("selector_index"),
                    )
                token = token.substring(0, indexed.range.first)
            }
            token =
                when {
                    token.startsWith("class.") -> "." + name(token.substring(6))
                    token.startsWith("id.") -> "#" + name(token.substring(3))
                    token.startsWith("tag.") -> name(token.substring(4))
                    token == "children" -> fail("children_selector")
                    token.startsWith("text.") -> fail("text_selector")
                    else -> token
                }
        }
        try {
            Jsoup.parse("<body/>").select(token.ifBlank { "body" })
        } catch (_: Exception) {
            fail("css_selector")
        }
        return LegadoSelection(token, indexes, exclusions, slice)
    }

    private fun name(value: String): String =
        value.takeIf { it.matches(Regex("[A-Za-z0-9_-]+")) } ?: fail("selector_name")

    private fun explicitPrefix(value: String): String? =
        listOf("@css:", "@json:", "@xpath:").firstOrNull { value.startsWith(it, true) }

    /** Operators inside CSS/XPath quotes and brackets are data, not rule delimiters. */
    private fun marker(text: String, marker: String): Int {
        var quote: Char? = null
        var escaped = false
        var brackets = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (escaped) {
                escaped = false
                i++
                continue
            }
            if (c == '\\') {
                escaped = true
                i++
                continue
            }
            if (quote != null) {
                if (c == quote) quote = null
            } else if (c == '\'' || c == '"') {
                quote = c
            } else if (c == '[' || c == '(') {
                brackets++
            } else if (c == ']' || c == ')') {
                brackets--
            } else if (brackets == 0 && text.startsWith(marker, i)) {
                return i
            }
            i++
        }
        return -1
    }

    private fun split(text: String, delimiter: String): List<String> {
        val result = mutableListOf<String>()
        var remaining = text
        while (true) {
            val point = marker(remaining, delimiter)
            if (point < 0) return result + remaining
            if (result.size >= 31) fail("rule_fanout")
            result += remaining.substring(0, point)
            remaining = remaining.substring(point + delimiter.length)
        }
    }

    private fun fail(capability: String): Nothing = throw LegadoRuleException(capability)

    private val ATTRIBUTES = setOf("text", "textNodes", "ownText", "html", "all", "href", "src")
}
