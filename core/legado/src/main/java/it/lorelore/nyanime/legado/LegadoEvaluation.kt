package it.lorelore.nyanime.legado

import com.google.re2j.Pattern
import it.lorelore.nyanime.studio.StudioJsonPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.helper.W3CDom
import org.jsoup.nodes.Element
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

/** A row keeps its DOM/JSON context. No reparsing or network lookup is needed for each field. */
class LegadoInput
private constructor(
    internal val element: Element?,
    internal val json: JsonElement?,
    internal val text: String?,
    internal val baseUrl: String,
) {
    override fun toString() = "LegadoInput(private)"

    companion object {
        const val MAX_CHARACTERS = 2 * 1024 * 1024

        fun html(raw: String, baseUrl: String = ""): LegadoInput {
            bounded(raw)
            return htmlElement(Jsoup.parse(raw, baseUrl), baseUrl)
        }

        fun json(raw: String): LegadoInput {
            bounded(raw)
            // Remote rule documents are containers. Do not accept an unquoted bare word as
            // JSON merely because a JsonElement parser can represent it as a primitive.
            if (raw.firstOrNull { !it.isWhitespace() } !in setOf('{', '[')) {
                throw LegadoRuleException("json_input")
            }
            var depth = 0
            var quoted = false
            var escaped = false
            raw.forEach { c ->
                if (escaped) {
                    escaped = false
                } else if (quoted && c == '\\') {
                    escaped = true
                } else if (c == '"') {
                    quoted = !quoted
                } else if (!quoted && c in "[{") {
                    if (++depth > 64) throw LegadoRuleException("json_nesting")
                } else if (!quoted && c in "]}") {
                    depth--
                }
            }
            return try {
                jsonElement(Json.parseToJsonElement(raw))
            } catch (_: Exception) {
                throw LegadoRuleException("json_input")
            }
        }

        fun text(raw: String, baseUrl: String = ""): LegadoInput {
            bounded(raw)
            return LegadoInput(null, null, raw, baseUrl)
        }

        internal fun htmlElement(element: Element, base: String) =
            LegadoInput(element, null, null, base)

        internal fun jsonElement(value: JsonElement) = LegadoInput(null, value, null, "")

        private fun bounded(raw: String) {
            if (raw.length > MAX_CHARACTERS) throw LegadoRuleException("input_size")
        }
    }
}

/**
 * Host port, not an interpreter. The host must check the current definition and execution consent
 * on every call, then use its existing isolated script service. No request/session objects cross
 * it.
 */
fun interface LegadoScript {
    suspend fun evaluate(code: String, result: JsonElement, variables: JsonObject): JsonElement
}

/**
 * Deterministic format semantics; network, persistence, activation and secrets belong elsewhere.
 */
class LegadoEvaluation(private val scripts: LegadoScript? = null) {
    suspend fun values(
        rule: LegadoRule,
        input: LegadoInput,
        variables: JsonObject = JsonObject(emptyMap()),
    ): List<LegadoInput> =
        try {
            Evaluation(variables).run(rule, input)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: LegadoRuleException) {
            throw failure
        } catch (_: Exception) {
            // Parser/library/port exceptions may quote untrusted payloads or private source values.
            throw LegadoRuleException("rule_execution")
        }

    suspend fun strings(
        rule: LegadoRule,
        input: LegadoInput,
        variables: JsonObject = JsonObject(emptyMap()),
    ): List<String> {
        val result = mutableListOf<String>()
        var characters = 0L
        for (value in values(rule, input, variables)) {
            checkActive()
            val text = render(value)
            characters += text.length
            if (characters > MAX_OUTPUT) fail("result_size")
            result += text
        }
        return result
    }

    private inner class Evaluation(private val variables: JsonObject) {
        private var steps = 0

        suspend fun run(rule: LegadoRule, input: LegadoInput, depth: Int = 0): List<LegadoInput> {
            checkActive()
            if (depth > 8 || ++steps > 1024) fail("execution_budget")
            val values =
                when (rule) {
                    is LegadoRule.Select -> select(rule, input)
                    is LegadoRule.Combine -> {
                        if (rule.rules.size !in 1..16) fail("rule_fanout")
                        val branches = mutableListOf<List<LegadoInput>>()
                        for (part in rule.rules) {
                            val result = run(part, input, depth + 1)
                            if (result.isNotEmpty()) {
                                branches += result
                                if (rule.kind == LegadoCombination.FIRST_AVAILABLE) break
                            }
                        }
                        if (rule.kind == LegadoCombination.INTERLEAVE) {
                            // Legacy format stops after the first nonempty branch's length.
                            buildList {
                                for (index in 0 until (branches.firstOrNull()?.size ?: 0)) {
                                    branches.forEach { branch ->
                                        branch.getOrNull(index)?.let {
                                            if (size >= MAX_VALUES) fail("result_count")
                                            add(it)
                                        }
                                    }
                                }
                            }
                        } else {
                            buildList {
                                branches.forEach { branch ->
                                    if (size + branch.size > MAX_VALUES) fail("result_count")
                                    addAll(branch)
                                }
                            }
                        }
                    }
                    is LegadoRule.Replace -> {
                        val items = rule.input?.let { run(it, input, depth + 1) } ?: listOf(input)
                        items
                            .map { LegadoInput.text(replace(render(it), rule), input.baseUrl) }
                            .filter { !it.text.isNullOrEmpty() }
                    }
                    is LegadoRule.Script -> {
                        val service = scripts ?: fail("script_not_authorized")
                        val selected =
                            rule.input?.let { run(it, input, depth + 1) } ?: listOf(input)
                        val data = selected.map { value ->
                            value.json ?: JsonPrimitive(render(value))
                        }
                        val argument = if (data.size == 1) data.single() else JsonArray(data)
                        if (
                            argument.toString().length + variables.toString().length > 180_000 ||
                            rule.code.length > 60_000
                        ) {
                            fail("script_input_size")
                        }
                        val result = service.evaluate(rule.code, argument, variables)
                        checkActive()
                        val rows = if (result is JsonArray) result else listOf(result)
                        if (rows.size > MAX_VALUES) fail("result_count")
                        rows.filter { it != JsonNull }.map { LegadoInput.jsonElement(it) }
                    }
                }
            checkActive()
            if (values.size > MAX_VALUES) fail("result_count")
            var characters = 0L
            values.forEach { value ->
                // DOM subtrees stay shared. Their serialized size is bounded only when
                // materialized.
                if (value.element == null) characters += render(value).length
                if (characters > MAX_OUTPUT) fail("result_size")
            }
            return values
        }

        private suspend fun select(rule: LegadoRule.Select, input: LegadoInput): List<LegadoInput> {
            if (rule.steps.size !in 1..16) fail("selector_chain")
            val result =
                when (rule.mode) {
                    LegadoSelectionMode.JSON -> {
                        val root =
                            input.json
                                ?: try {
                                    LegadoInput.json(render(input)).json!!
                                } catch (_: Exception) {
                                    fail("json_input")
                                }
                        val selected = StudioJsonPath.select(root, rule.steps.single().selector)
                        val rows =
                            if (rule.attribute == "all") {
                                selected.flatMap { if (it is JsonArray) it else listOf(it) }
                            } else {
                                selected
                            }
                        if (rows.size > MAX_VALUES) fail("result_count")
                        rows.filter { it != JsonNull }.map(LegadoInput::jsonElement)
                    }
                    LegadoSelectionMode.XPATH -> xpath(rule.steps.single().selector, input)
                    LegadoSelectionMode.CSS -> {
                        val root = input.element ?: Jsoup.parse(render(input), input.baseUrl)
                        var selected = listOf(root)
                        rule.steps.forEach { declaration ->
                            checkActive()
                            selected = buildList {
                                selected.forEach { parent ->
                                    checkActive()
                                    val candidates =
                                        if (declaration.selector.isBlank()) {
                                            listOf(parent)
                                        } else {
                                            parent.select(declaration.selector)
                                        }
                                    if (candidates.size > MAX_VALUES) fail("result_count")
                                    val filtered = indexes(candidates, declaration)
                                    if (size + filtered.size > MAX_VALUES) fail("result_count")
                                    addAll(filtered)
                                }
                            }
                        }
                        attributes(selected, rule.attribute, input.baseUrl)
                    }
                }
            return if (rule.reverse) result.reversed() else result
        }

        private fun attributes(
            elements: List<Element>,
            attribute: String,
            base: String,
        ): List<LegadoInput> {
            if (attribute == "all") return elements.map { LegadoInput.htmlElement(it, base) }
            if (attribute == "html") {
                // Copy before removing scripts/styles: a later field must see the original row.
                val text = StringBuilder()
                for (element in elements) {
                    val part =
                        element.clone().also { it.select("script,style").remove() }.outerHtml()
                    if (text.length.toLong() + part.length + 1 > MAX_OUTPUT) fail("result_size")
                    if (text.isNotEmpty()) text.append('\n')
                    text.append(part)
                }
                return listOfNotNull(
                    text.toString().takeIf(String::isNotEmpty)?.let { LegadoInput.text(it, base) },
                )
            }
            val values =
                elements
                    .map { element ->
                        when (attribute) {
                            "text" -> element.text()
                            "ownText" -> element.ownText()
                            "textNodes" ->
                                element
                                    .textNodes()
                                    .map { it.text().trim() }
                                    .filter(String::isNotEmpty)
                                    .joinToString("\n")
                            else -> element.attr(attribute)
                        }
                    }
                    .filter(String::isNotEmpty)
            val deduplicated =
                if (attribute in setOf("text", "ownText", "textNodes")) {
                    values
                } else {
                    values.distinct()
                }
            return deduplicated.map { LegadoInput.text(it, base) }
        }

        private suspend fun xpath(path: String, input: LegadoInput): List<LegadoInput> {
            if (path.length > 2048) fail("xpath")
            val html = input.element ?: Jsoup.parse(render(input), input.baseUrl)
            val document =
                W3CDom()
                    .namespaceAware(false)
                    .fromJsoup(Jsoup.parse(html.outerHtml(), input.baseUrl))
            checkActive()
            val selected =
                try {
                    XPathFactory.newInstance()
                        .newXPath()
                        .evaluate(path, document, XPathConstants.NODESET) as NodeList
                } catch (_: Exception) {
                    fail("xpath_result")
                }
            if (selected.length > MAX_VALUES) fail("result_count")
            return (0 until selected.length)
                .map { index ->
                    checkActive()
                    val node = selected.item(index)
                    if (node.nodeType == Node.ELEMENT_NODE) {
                        // W3CDom retains a source-node link; preserve the exact selected HTML
                        // element.
                        val source =
                            node.getUserData(W3CDom.SourceProperty) as? Element
                                ?: fail("xpath_context")
                        LegadoInput.htmlElement(source, input.baseUrl)
                    } else {
                        LegadoInput.text(node.nodeValue.orEmpty(), input.baseUrl)
                    }
                }
                .filter { it.element != null || !it.text.isNullOrEmpty() }
        }

        private suspend fun replace(value: String, rule: LegadoRule.Replace): String {
            if (
                value.length > MAX_OUTPUT ||
                rule.pattern.length > 256 ||
                rule.replacement.length > 131_072
            ) {
                fail("regex_size")
            }
            val matcher = Pattern.compile(rule.pattern).matcher(value)
            val buffer = StringBuffer()
            while (matcher.find()) {
                checkActive()
                val start = matcher.start()
                try {
                    matcher.appendReplacement(buffer, rule.replacement)
                } catch (_: Exception) {
                    fail("regex_replacement")
                }
                if (buffer.length > MAX_OUTPUT) fail("result_size")
                if (rule.firstOnly) return buffer.substring(start)
            }
            if (rule.firstOnly) return ""
            matcher.appendTail(buffer)
            if (buffer.length > MAX_OUTPUT) fail("result_size")
            return buffer.toString()
        }
    }

    private fun <T> indexes(values: List<T>, rule: LegadoSelection): List<T> {
        fun normalized(value: Int): Int? {
            val result = if (value < 0) values.size.toLong() + value else value.toLong()
            return result.takeIf { it >= 0 && it < values.size }?.toInt()
        }
        rule.indexes?.let {
            return it.mapNotNull(::normalized).distinct().map(values::get)
        }
        val slice = rule.slice
        if (slice != null) {
            if (slice.size !in 2..3 || values.isEmpty()) return emptyList()
            fun endpoint(value: Int?, default: Int): Long =
                value?.let { if (it < 0) values.size.toLong() + it else it.toLong() }
                    ?: default.toLong()
            val start = endpoint(slice[0], 0)
            val end = endpoint(slice[1], values.lastIndex)
            if ((start < 0 && end < 0) || (start >= values.size && end >= values.size)) {
                return emptyList()
            }
            val first = start.coerceIn(0, values.lastIndex.toLong()).toInt()
            val last = end.coerceIn(0, values.lastIndex.toLong()).toInt()
            val rawStep = slice.getOrNull(2) ?: 1
            if (rawStep == 0) fail("selector_slice")
            val step =
                if (rawStep > 0) {
                    rawStep
                } else {
                    (values.size.toLong() + rawStep).takeIf { it > 0 }?.toInt() ?: 1
                }
            val range = if (first <= last) first..last step step else first downTo last step step
            return range.map(values::get)
        }
        val excluded = rule.exclusions.mapNotNull(::normalized).toSet()
        return values.filterIndexed { index, _ -> index !in excluded }
    }

    companion object {
        const val MAX_VALUES = 2000
        const val MAX_OUTPUT = LegadoInput.MAX_CHARACTERS

        private suspend fun checkActive() = currentCoroutineContext().ensureActive()

        private fun fail(code: String): Nothing = throw LegadoRuleException(code)

        private fun render(value: LegadoInput): String =
            value.element?.outerHtml()
                ?: value.json?.let { if (it is JsonPrimitive) it.content else it.toString() }
                ?: value.text.orEmpty()
    }
}
