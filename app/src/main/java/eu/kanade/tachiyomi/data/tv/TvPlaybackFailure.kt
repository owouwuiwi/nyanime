package eu.kanade.tachiyomi.data.tv

/** Transport/decoder facts only. A status code never implies a particular provider or country. */
enum class TvPlaybackFailure(val retryable: Boolean) {
    UNKNOWN(true),
    CONNECTION(true),
    ADDRESS(false),
    ACCESS(false),
    UNAVAILABLE(false),
    UNSUPPORTED(false),
    TLS(false),
    ;

    companion object {
        private val httpStatus = Regex("HTTP (?:error|status(?: code)?)[ :]+([1-5][0-9]{2})", RegexOption.IGNORE_CASE)

        /** Consume native text in memory; callers log only the resulting enum, never a URL or header. */
        fun classify(message: String?): TvPlaybackFailure? {
            if (message == null || message.length > 16_384) return null
            val text = message.lowercase()
            val code = httpStatus.find(message)?.groupValues?.get(1)?.toIntOrNull()
            return when {
                code in setOf(401, 403, 451) -> ACCESS
                code in setOf(404, 410) -> UNAVAILABLE
                code == 408 || code == 429 || code != null && code in 500..599 -> CONNECTION
                text.contains("temporary failure in name resolution") -> CONNECTION
                listOf(
                    "name or service not known",
                    "nodename nor servname",
                    "no such host",
                    "host not found",
                    "failed to resolve hostname",
                ).any(text::contains) -> ADDRESS
                listOf(
                    "certificate verify failed",
                    "certificate verification failed",
                    "server certificate verification failed",
                ).any(text::contains) -> TLS
                listOf(
                    "unknown format",
                    "unrecognized file format",
                    "unsupported format",
                    "unsupported codec",
                    "no video or audio streams selected",
                ).any(text::contains) -> UNSUPPORTED
                listOf(
                    "connection refused",
                    "connection reset",
                    "network is unreachable",
                    "no route to host",
                    "timed out",
                    "timeout",
                ).any(text::contains) -> CONNECTION
                else -> null
            }
        }
    }
}
