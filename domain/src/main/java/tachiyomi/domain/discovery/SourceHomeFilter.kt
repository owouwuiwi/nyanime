package tachiyomi.domain.discovery

/** Serializable-by-value controls; extension filter instances never enter UI state or caches. */
data class SourceHomeFilter(
    val name: String,
    val kind: Kind,
    val options: List<String> = emptyList(),
    val defaults: List<String> = emptyList(),
) : java.io.Serializable {
    enum class Kind { SINGLE, MULTIPLE, TEXT }
    fun accepts(values: List<String>) = when (kind) {
        Kind.SINGLE -> values.size == 1 && values.single() in options
        Kind.MULTIPLE -> values.distinct().size == values.size && values.all { it in options }
        Kind.TEXT -> values.size <= 1 && values.all { it.length <= 300 && it.none(Char::isISOControl) }
    }
}
