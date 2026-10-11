package it.lorelore.nyanime.novel

/** Wall-clock changes and imported extreme timestamps must not make local edits overflow. */
fun nextNovelTimestamp(previous: Long, now: Long = System.currentTimeMillis()): Long {
    require(previous >= 0 && now >= 0)
    val next = if (previous == Long.MAX_VALUE) previous else previous + 1
    return maxOf(now, next)
}
