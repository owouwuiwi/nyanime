package eu.kanade.tachiyomi.data.reading

data class ReadingPoint(val x: Float, val y: Float)

/** Coordinate system of the original page, independent of reading direction or device viewport. */
enum class ReadingPageLayout {
    Full,
    Left,
    Right,
    Clockwise,
    CounterClockwise,
    RightAboveLeft,
    LeftAboveRight,
    ;

    val stacked get() = this == RightAboveLeft || this == LeftAboveRight

    fun original(x: Float, y: Float): ReadingPoint = when (this) {
        Full -> ReadingPoint(x, y)
        Left -> ReadingPoint(x / 2, y)
        Right -> ReadingPoint(0.5f + x / 2, y)
        Clockwise -> ReadingPoint(y, 1 - x)
        CounterClockwise -> ReadingPoint(1 - y, x)
        RightAboveLeft -> ReadingPoint(x / 2 + if (y < 0.5f) 0.5f else 0f, if (y < 0.5f) y * 2 else (y - 0.5f) * 2)
        LeftAboveRight -> ReadingPoint(x / 2 + if (y < 0.5f) 0f else 0.5f, if (y < 0.5f) y * 2 else (y - 0.5f) * 2)
    }

    fun displayed(point: ReadingPoint): ReadingPoint? {
        val (x, y) = point
        return when (this) {
            Full -> point
            Left -> if (x <= 0.5f) ReadingPoint(x * 2, y) else null
            Right -> if (x >= 0.5f) ReadingPoint((x - 0.5f) * 2, y) else null
            Clockwise -> ReadingPoint(1 - y, x)
            CounterClockwise -> ReadingPoint(y, 1 - x)
            RightAboveLeft -> ReadingPoint(if (x < 0.5f) x * 2 else (x - 0.5f) * 2, y / 2 + if (x < 0.5f) 0.5f else 0f)
            LeftAboveRight -> ReadingPoint(if (x < 0.5f) x * 2 else (x - 0.5f) * 2, y / 2 + if (x < 0.5f) 0f else 0.5f)
        }
    }
}

/** Reduce long gestures progressively, retaining endpoints instead of truncating a drawn line. */
internal fun compactReadingPoints(points: MutableList<ReadingPoint>) {
    if (points.size < 96) return
    val reduced = points.filterIndexed { index, _ -> index % 2 == 0 || index == points.lastIndex }
    points.clear()
    points.addAll(reduced)
}
