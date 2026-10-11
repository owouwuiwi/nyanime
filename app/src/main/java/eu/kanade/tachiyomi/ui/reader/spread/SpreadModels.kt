package eu.kanade.tachiyomi.ui.reader.spread

import kotlinx.serialization.Serializable
import kotlin.math.min
import kotlin.math.roundToInt

@Serializable
enum class SpreadMode { SINGLE, PAIRS, VERTICAL }

@Serializable
data class SpreadRecord(
    val source: Long,
    val title: String,
    val mode: SpreadMode = SpreadMode.SINGLE,
    val offset: Int = 0,
    val chapterOffsets: Map<String, Int> = emptyMap(),
    val changedAt: Long = 0,
) {
    fun offsetFor(chapter: String) = chapterOffsets[chapter] ?: offset
    fun valid() = title.isNotBlank() &&
        offset in 0..MAX_OFFSET &&
        chapterOffsets.size <= 10_000 &&
        chapterOffsets.all { (key, value) ->
            key.isNotBlank() && value in 0..MAX_OFFSET
        }

    companion object {
        const val MAX_OFFSET = 1_000_000
    }
}

/** Rendering groups retain original page objects and their original indices. */
data class Spread<T>(val pages: List<T>) {
    init {
        require(pages.size in 1..2)
    }
}

object SpreadGrouping {
    fun <T> group(pages: List<T>, offset: Int, standalone: (T) -> Boolean = { false }): List<Spread<T>> {
        val result = ArrayList<Spread<T>>()
        val leading = offset.coerceIn(0, pages.size)
        var index = 0
        while (index < pages.size) {
            val first = pages[index]
            val second = pages.getOrNull(index + 1)
            val paired = index >= leading && !standalone(first) && second != null && !standalone(second)
            result += Spread(if (paired) listOf(first, second!!) else listOf(first))
            index += if (paired) 2 else 1
        }
        return result
    }

    fun standalone(width: Int, height: Int): Boolean =
        width > 0 && height > 0 && (width >= height || height.toLong() > width.toLong() * 3)

    /** The last page cannot complete a chapter while its preceding partner is unavailable. */
    fun <T> progress(group: Spread<T>, ready: (T) -> Boolean): T? =
        group.pages.takeWhile(ready).lastOrNull()
}

/** A single centered frame, with adjacent pages sharing the same fitted height. */
data class SpreadBounds(val pageWidths: List<Int>, val height: Int) {
    val width get() = pageWidths.sum()
}

object SpreadLayout {
    /** Decoder geometry includes border cropping and source rotation, unlike file headers. */
    fun displaySize(
        original: Pair<Int, Int>?,
        decoded: Pair<Int, Int>?,
        quarterTurn: Boolean = false,
    ): Pair<Int, Int>? {
        val rendered = decoded?.takeIf { it.first > 0 && it.second > 0 }
        if (rendered != null) return if (quarterTurn) rendered.second to rendered.first else rendered
        return original?.takeIf { it.first > 0 && it.second > 0 }
    }

    fun panLimit(viewport: Int, content: Int, zoom: Float): Float =
        ((content.toDouble() * zoom - viewport) / 2).coerceAtLeast(0.0).toFloat()

    fun fit(width: Int, height: Int?, pages: List<Pair<Int, Int>?>): SpreadBounds {
        require(pages.size in 1..2 && width >= pages.size && (height == null || height > 0))
        val aspects = pages.map { size ->
            size?.takeIf { it.first > 0 && it.second > 0 }
                ?.let { it.first.toDouble() / it.second } ?: (2.0 / 3.0)
        }
        val sum = aspects.sum()
        val preciseHeight = min(width / sum, height?.toDouble() ?: Double.MAX_VALUE)
        val fittedHeight = preciseHeight.roundToInt().coerceAtLeast(1)
        val fittedWidth = (preciseHeight * sum).roundToInt().coerceIn(pages.size, width)
        val firstWidth = (fittedWidth * aspects.first() / sum).roundToInt()
            .coerceIn(1, fittedWidth - pages.size + 1)
        return SpreadBounds(
            if (pages.size == 1) listOf(fittedWidth) else listOf(firstWidth, fittedWidth - firstWidth),
            fittedHeight,
        )
    }
}
