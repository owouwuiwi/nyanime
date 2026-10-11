package tachiyomi.domain.search

import java.util.concurrent.atomic.AtomicInteger

/** Cancellation or a retry never replenishes a query's external request allowance. */
class SearchRequestBudget(maximum: Int) {
    private val remaining = AtomicInteger(maximum.coerceAtLeast(0))
    fun take(): Boolean {
        while (true) {
            val value = remaining.get()
            if (value <= 0) return false
            if (remaining.compareAndSet(value, value - 1)) return true
        }
    }
}
