package eu.kanade.tachiyomi.crash

internal data class CrashDetails(val text: String, val truncated: Boolean)

/** Bound the on-screen preview; the shared report retains the original exception. */
internal fun crashDetails(exception: Throwable?): CrashDetails? {
    if (exception == null) return null
    // The crash process receives the original trace as a Throwable message. Its own
    // deserialization frames do not describe the failure and must not replace it.
    val original = if (exception.javaClass == Throwable::class.java && exception.message != null) {
        exception.message.orEmpty()
    } else {
        exception.stackTraceToString()
    }
    if (original.isBlank()) return null
    val bounded = original.take(8_000)
    val lines = bounded.lines()
    return CrashDetails(lines.take(60).joinToString("\n"), original.length > bounded.length || lines.size > 60)
}
