package eu.kanade.tachiyomi.data.download.anime.ultra

import kotlin.math.min
import kotlin.math.roundToInt

/** Compiles only our bundled mpv shader directives, never shader code supplied by a source. */
internal object UltraShaderGraph {
    val assets = listOf(
        "Anime4K_Clamp_Highlights.glsl",
        "Anime4K_Restore_CNN_VL.glsl",
        "Anime4K_Upscale_CNN_x2_VL.glsl",
        "Anime4K_Restore_CNN_M.glsl",
        "Anime4K_AutoDownscalePre_x2.glsl",
        "Anime4K_AutoDownscalePre_x4.glsl",
        "Anime4K_Upscale_CNN_x2_M.glsl",
    )

    data class Image(val id: Int, val width: Int, val height: Int)
    data class Pass(val directives: Map<String, List<String>>, val code: String)
    data class Node(val output: Image, val inputs: Map<String, Image>, val fragment: String)
    data class Graph(val input: Image, val output: Image, val nodes: List<Node>)

    fun target(width: Int, height: Int): Pair<Int, Int> {
        require(width > 0 && height > 0)
        val scale = min(4.0, min(3840.0 / maxOf(width, height), 2160.0 / minOf(width, height)))
        require(scale >= 1.0) { "Il video supera la risoluzione Ultra supportata" }
        fun even(value: Double) = (value.roundToInt() / 2 * 2).coerceAtLeast(2)
        return even(width * scale) to even(height * scale)
    }

    fun parse(text: String): List<Pass> = text.split("//!DESC ").drop(1).map { block ->
        val directives = linkedMapOf<String, MutableList<String>>()
        val code = StringBuilder()
        block.lineSequence().drop(1).forEach { line ->
            if (line.startsWith("//!")) {
                val key = line.removePrefix("//!").substringBefore(' ')
                require(key in setOf("HOOK", "BIND", "SAVE", "WIDTH", "HEIGHT", "WHEN", "COMPONENTS"))
                directives.getOrPut(key) { mutableListOf() }.add(line.substringAfter(' ').trim())
            } else {
                code.appendLine(line)
            }
        }
        require(directives["HOOK"]?.single() in setOf("MAIN", "PREKERNEL"))
        Pass(directives, code.toString())
    }

    fun compile(shaders: List<String>, width: Int, height: Int): Graph {
        val (outWidth, outHeight) = target(width, height)
        val input = Image(0, width, height)
        var main = input
        val saved = mutableMapOf<String, Image>()
        val nodes = mutableListOf<Node>()
        val passes = shaders.flatMap(::parse).sortedBy { if (it.directives["HOOK"]?.single() == "MAIN") 0 else 1 }
        for (pass in passes) {
            val bindings =
                saved +
                    mapOf(
                        "MAIN" to main,
                        "HOOKED" to main,
                        "NATIVE" to input,
                        "OUTPUT" to Image(-1, outWidth, outHeight),
                    )
            fun evaluate(value: String) = expression(value) { name ->
                val image = bindings[name.substringBefore('.')] ?: error("Unknown shader image: $name")
                when (name.substringAfter('.')) {
                    "w" -> image.width.toDouble()
                    "h" -> image.height.toDouble()
                    else -> error("Unknown dimension: $name")
                }
            }
            if (pass.directives["WHEN"]?.single()?.let { evaluate(it) == 0.0 } == true) continue
            val w = pass.directives["WIDTH"]?.single()?.let { evaluate(it).roundToInt() } ?: main.width
            val h = pass.directives["HEIGHT"]?.single()?.let { evaluate(it).roundToInt() } ?: main.height
            require(w in 1..8192 && h in 1..8192)
            val usedNames = pass.directives["BIND"].orEmpty().toMutableSet()
            if ("HOOKED" in usedNames && "MAIN_" in pass.code) usedNames += "MAIN"
            val used = usedNames.filter { Regex("\\b${it}_(?:tex|texOff)\\b").containsMatchIn(pass.code) }
                .associateWith { bindings[it] ?: error("Missing shader binding: $it") }
            val output = Image(nodes.size + 1, w, h)
            nodes += Node(output, used, fragment(pass.code, used))
            val save = pass.directives["SAVE"]?.single()
            if (save == null || save == "MAIN") main = output else saved[save] = output
        }
        // Resample the last CNN result to the requested output dimensions without stretching.
        val output = Image(nodes.size + 1, outWidth, outHeight)
        val inputs = mapOf("HOOKED" to main)
        nodes += Node(output, inputs, fragment("vec4 hook() { return HOOKED_tex(HOOKED_pos); }", inputs))
        return Graph(input, output, nodes)
    }

    fun expression(text: String, variable: (String) -> Double): Double {
        val stack = mutableListOf<Double>()
        for (token in text.trim().split(Regex("\\s+"))) {
            if (token in setOf("+", "-", "*", "/", ">", "<")) {
                require(stack.size >= 2)
                val b = stack.removeAt(stack.lastIndex)
                val a = stack.removeAt(stack.lastIndex)
                stack += when (token) {
                    "+" -> a + b
                    "-" -> a - b
                    "*" -> a * b
                    "/" -> a / b
                    ">" -> if (a > b) 1.0 else 0.0
                    else -> if (a < b) 1.0 else 0.0
                }
            } else {
                stack += token.toDoubleOrNull() ?: variable(token)
            }
        }
        return stack.single().also { require(it.isFinite()) }
    }

    private fun fragment(code: String, bindings: Map<String, Image>): String = buildString {
        appendLine("precision highp float; varying vec2 vTexCoord;")
        bindings.entries.forEachIndexed { index, (name, image) ->
            appendLine("uniform sampler2D uTexture$index;")
            appendLine("#define ${name}_pos vTexCoord")
            appendLine("#define ${name}_size vec2(${image.width}.0, ${image.height}.0)")
            appendLine("#define ${name}_pt (1.0 / ${name}_size)")
            appendLine("#define ${name}_tex(p) texture2D(uTexture$index, p)")
            appendLine("#define ${name}_texOff(p) ${name}_tex(vTexCoord + (p) * ${name}_pt)")
        }
        appendLine(code)
        appendLine("void main() { gl_FragColor = hook(); }")
    }
}
