package it.lorelore.nyanime.legado

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

internal object LegadoJsonPath {
    fun select(root: JsonElement, path: String): List<JsonElement> {
        require(path.startsWith("$") && path.length <= 512) { "A JSON path starts with $" }
        var current = listOf(root)
        var index = 1
        while (index < path.length) {
            val key: String
            var wildcard = false
            when (path[index]) {
                '.' -> {
                    val start = ++index
                    while (index < path.length && path[index] !in ".[") index++
                    key = path.substring(start, index)
                    require(key.isNotBlank()) { "Empty JSON property" }
                    wildcard = key == "*"
                }
                '[' -> {
                    index++
                    while (index < path.length && path[index].isWhitespace()) index++
                    require(index < path.length) { "Unclosed JSON path" }
                    if (path[index] == '\'' || path[index] == '"') {
                        val quote = path[index++]
                        val text = StringBuilder()
                        var closed = false
                        while (index < path.length) {
                            val character = path[index++]
                            if (character == quote) {
                                closed = true
                                break
                            }
                            if (character != '\\') {
                                text.append(character)
                            } else {
                                require(index < path.length) { "Unclosed JSON escape" }
                                text.append(
                                    when (val escaped = path[index++]) {
                                        '\\',
                                        '\'',
                                        '"',
                                        '/',
                                        -> escaped
                                        'n' -> '\n'
                                        'r' -> '\r'
                                        't' -> '\t'
                                        'b' -> '\b'
                                        'f' -> '\u000C'
                                        'u' -> {
                                            require(index + 4 <= path.length) {
                                                "Invalid JSON Unicode escape"
                                            }
                                            val code =
                                                path.substring(index, index + 4).toIntOrNull(16)
                                                    ?: error("Invalid JSON Unicode escape")
                                            index += 4
                                            code.toChar()
                                        }
                                        else -> error("Unsupported JSON escape")
                                    },
                                )
                            }
                        }
                        require(closed) { "Unclosed JSON property" }
                        key = text.toString()
                    } else {
                        val start = index
                        while (index < path.length && path[index] != ']') index++
                        key = path.substring(start, index).trim()
                        wildcard = key == "*"
                        require(
                            wildcard ||
                                key.toIntOrNull()?.let {
                                    it >= 0
                                } == true,
                        ) {
                            "Use a quoted JSON property or array index"
                        }
                    }
                    while (index < path.length && path[index].isWhitespace()) index++
                    require(index < path.length && path[index] == ']') { "Unclosed JSON path" }
                    index++
                }
                else -> error("Unsupported JSON path at $index")
            }
            current = buildList {
                fun append(value: JsonElement?) {
                    if (value == null) return
                    require(size < 2000) { "JSON selection too large" }
                    add(value)
                }
                current.forEach { value ->
                    when {
                        wildcard && value is JsonArray -> value.forEach(::append)
                        wildcard && value is JsonObject -> value.values.forEach(::append)
                        value is JsonObject -> append(value[key])
                        value is JsonArray -> key.toIntOrNull()?.let { append(value.getOrNull(it)) }
                        else -> Unit
                    }
                }
            }
        }
        return current
    }
}
