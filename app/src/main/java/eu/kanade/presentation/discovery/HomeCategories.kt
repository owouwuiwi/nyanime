package eu.kanade.presentation.discovery

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tachiyomi.domain.discovery.SourceHomeGroup

data class HomeCategory(val id: String?, val title: String) {
    val orderKey: String get() = id?.let { "source:$it" } ?: "default"
}

object HomeCategories {
    fun choices(homes: List<SourceHomeGroup>, savedOrder: String): List<HomeCategory> {
        val defaults = (if (homes.any { it.primary }) emptyList() else listOf(HomeCategory(null, "Anime"))) +
            homes.sortedWith(compareByDescending<SourceHomeGroup> { it.primary }.thenBy { it.title })
                .map { HomeCategory(it.id, it.title) }
        val positions = decodeOrder(savedOrder).withIndex().associate { it.value to it.index }
        return defaults.sortedWith(compareBy<HomeCategory> { positions[it.orderKey] ?: Int.MAX_VALUE })
    }

    fun moveFirst(savedOrder: String, category: HomeCategory): String {
        val order = decodeOrder(savedOrder).filterNot { it == category.orderKey }
        return Json.encodeToString(listOf(category.orderKey) + order)
    }

    fun next(current: String?, choices: List<HomeCategory>): String? {
        if (choices.isEmpty()) return current
        val index = choices.indexOfFirst { it.id == current }
        return choices[(index + 1) % choices.size].id
    }

    private fun decodeOrder(value: String): List<String> = runCatching {
        Json.decodeFromString<List<String>>(value).distinct()
    }.getOrDefault(emptyList())
}

/** Fills the first row before the second; extra categories remain available on further pages. */
fun categoryPages(widths: List<Float>, availableWidth: Float, gap: Float): List<List<List<Int>>> {
    if (widths.isEmpty()) return emptyList()
    val limit = availableWidth.coerceAtLeast(1f)
    val safeWidths = widths.map { it.coerceAtMost(limit) }
    val pages = mutableListOf<List<List<Int>>>()
    var next = 0
    while (next < widths.size) {
        val rows = mutableListOf<List<Int>>()
        repeat(2) {
            if (next >= widths.size) return@repeat
            val row = mutableListOf<Int>()
            var used = 0f
            while (next < widths.size) {
                val itemWidth = safeWidths[next]
                val required = itemWidth + if (row.isEmpty()) 0f else gap
                if (row.isNotEmpty() && used + required > limit) break
                row += next++
                used += required
            }
            rows += row
        }
        pages += rows
    }
    return pages
}
