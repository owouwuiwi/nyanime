package eu.kanade.tachiyomi.data.extensionstore

/** Supported catalogue languages, independent of extensions and installed packages. */
object StoreLanguages {
    val options = listOf("it", "en")

    fun normalize(selected: Set<String>): Set<String> =
        selected.intersect(options.toSet()).ifEmpty { setOf("it") }

    fun toggle(selected: Set<String>, language: String): Set<String> {
        val current = normalize(selected)
        if (language !in options) return current
        return if (language in current) {
            (current - language).ifEmpty { current }
        } else {
            current + language
        }
    }
}
