package it.lorelore.nyanime.studio

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Declared, lazy subgraph dispatch. No branch can name an undeclared function at runtime. */
object StudioCalls {
    fun repeated(node: StudioNode): Boolean = node.parameters["each"] == "true"

    fun cases(node: StudioNode): Map<String, String> {
        val raw = node.parameters["cases"]?.takeIf(String::isNotBlank) ?: return emptyMap()
        require(raw.length <= 8192) { "Too many branch choices" }
        val choices = StudioDocuments.json.parseToJsonElement(raw) as? JsonObject
            ?: error("Declare branch choices as an object")
        require(choices.size in 1..16 && choices.keys.all { it.length in 1..128 })
        return choices.mapValues { (_, value) -> value.studioText().also { require(it.length in 1..128) } }
    }

    fun targets(node: StudioNode): Set<String> {
        require(node.parameters["each"] == null || node.parameters["each"] in setOf("true", "false"))
        val choices = cases(node)
        if (choices.isNotEmpty()) {
            val path = node.parameters["branchField"].orEmpty().split('.')
            require(path.size in 1..8 && path.all { it.matches(Regex("[a-zA-Z_][a-zA-Z0-9_-]{0,63}")) }) {
                "Choose the field used to select a branch"
            }
        }
        val fallback = node.parameters["function"]?.takeIf(String::isNotBlank)
        return (choices.values + listOfNotNull(fallback)).toSet().also {
            require(it.isNotEmpty() && it.all { id -> id.length in 1..128 }) { "Choose a reusable block" }
        }
    }

    fun select(node: StudioNode, value: JsonElement): String {
        targets(node)
        val choices = cases(node)
        val branch = if (choices.isEmpty()) {
            null
        } else {
            node.parameters.getValue("branchField").split('.').fold(value as JsonElement?) { current, field ->
                (current as? JsonObject)?.get(field)
            }?.studioText()?.let(choices::get)
        }
        return branch ?: node.parameters["function"]?.takeIf(String::isNotBlank)
            ?: error("No declared branch matches this input")
    }
}
