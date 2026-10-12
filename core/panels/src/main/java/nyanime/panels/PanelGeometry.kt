package nyanime.panels

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object PanelGeometry {
    fun resize(rect: PanelRect, x: Float, y: Float, corner: Int): PanelRect {
        val pointX = x.coerceIn(0f, 1f)
        val pointY = y.coerceIn(0f, 1f)
        val next = when (corner) {
            0 -> rect.copy(left = min(pointX, rect.right - .002f), top = min(pointY, rect.bottom - .002f))
            1 -> rect.copy(right = max(pointX, rect.left + .002f), top = min(pointY, rect.bottom - .002f))
            2 -> rect.copy(right = max(pointX, rect.left + .002f), bottom = max(pointY, rect.top + .002f))
            3 -> rect.copy(left = min(pointX, rect.right - .002f), bottom = max(pointY, rect.top + .002f))
            else -> rect
        }
        return next.takeIf { it.valid() } ?: rect
    }

    /** Join only matching clipped borders of overlapping tiles, not arbitrary overlapping panels. */
    fun stitch(tiles: List<Pair<PanelTile, List<PanelRect>>>, width: Int, height: Int): List<PanelRect> {
        val results = mutableListOf<Pair<PanelRect, Boolean>>()
        for ((tile, boxes) in tiles) {
            for (box in boxes) {
                val mapped = tile.map(box, width, height)
                val clippedTop = tile.y > 0 && box.top < .06f
                val clippedBottom = tile.y + tile.height < height && box.bottom > .94f
                val previous = if (clippedTop) {
                    results.indexOfLast { (rect, clipped) ->
                        clipped &&
                            rect.intersection(mapped) > 0 &&
                            abs(rect.left - mapped.left) < .04f &&
                            abs(rect.right - mapped.right) < .04f
                    }
                } else {
                    -1
                }
                if (previous >= 0) {
                    val old = results[previous].first
                    results[previous] = PanelRect(
                        min(old.left, mapped.left),
                        min(old.top, mapped.top),
                        max(old.right, mapped.right),
                        max(old.bottom, mapped.bottom),
                    ) to clippedBottom
                } else {
                    results += mapped to clippedBottom
                }
            }
        }
        return deduplicate(results.map { it.first })
    }

    fun tiles(width: Int, height: Int): List<PanelTile> {
        require(width > 0 && height > 0)
        if (height.toDouble() / width < 2.4) return listOf(PanelTile(0, 0, width, height))
        val tileHeight = min(height, width.toLong().times(2).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        val step = max(1, (tileHeight * 0.8f).toInt())
        val result = mutableListOf<PanelTile>()
        var y = 0
        while (true) {
            result += PanelTile(0, y, width, min(tileHeight, height - y))
            require(result.size <= 256) { "Page exceeds the bounded analysis budget" }
            if (y + tileHeight >= height) break
            y = min(y + step, height - tileHeight)
        }
        return result
    }

    fun deduplicate(rectangles: List<PanelRect>): List<PanelRect> {
        val accepted = mutableListOf<PanelRect>()
        for (rect in rectangles.filter { it.valid() }.sortedByDescending { it.area }) {
            if (accepted.none { other ->
                    val overlap = rect.intersection(other)
                    overlap / min(rect.area, other.area) > 0.82f
                }
            ) {
                accepted += rect
            }
        }
        return accepted.take(256)
    }

    /** Recursively split gutters before ordering; a tall panel cannot scramble adjacent rows. */
    fun order(rectangles: List<PanelRect>, direction: PanelDirection): List<PanelRect> {
        val values = rectangles.filter { it.valid() }
        if (direction == PanelDirection.VERTICAL) return values.sortedWith(compareBy({ it.top }, { it.left }))
        fun sort(input: List<PanelRect>, depth: Int): List<PanelRect> {
            if (input.size <= 1) return input
            if (depth < 32) {
                for (horizontal in listOf(true, false)) {
                    val ordered = input.sortedBy { if (horizontal) it.top else it.left }
                    var end = if (horizontal) ordered.first().bottom else ordered.first().right
                    for (index in 1 until ordered.size) {
                        val start = if (horizontal) ordered[index].top else ordered[index].left
                        if (start - end > 0.006f) {
                            val first = sort(ordered.take(index), depth + 1)
                            val second = sort(ordered.drop(index), depth + 1)
                            return if (!horizontal &&
                                direction == PanelDirection.RTL
                            ) {
                                second + first
                            } else {
                                first + second
                            }
                        }
                        end = max(end, if (horizontal) ordered[index].bottom else ordered[index].right)
                    }
                }
            }
            // No separating gutter: build stable rows without an inconsistent pairwise comparator.
            val remaining = input.sortedBy { it.top }.toMutableList()
            val result = mutableListOf<PanelRect>()
            while (remaining.isNotEmpty()) {
                val first = remaining.removeAt(0)
                val row = remaining.filter {
                    abs(it.top - first.top) <= min(it.height, first.height) * 0.3f
                }
                remaining.removeAll(row.toSet())
                val items = row + first
                result += if (direction == PanelDirection.RTL) {
                    items.sortedByDescending { it.right }
                } else {
                    items.sortedBy { it.left }
                }
            }
            return result
        }
        return sort(values, 0)
    }
}
