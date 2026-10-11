package tachiyomi.domain.search

import kotlinx.serialization.json.Json

/** A corrupt disposable cache is discarded, never confused with library or tracking data. */
class TitleCacheCodec(private val json: Json = Json { ignoreUnknownKeys = true }) {
    fun decode(raw: String): List<SearchTitle> = runCatching {
        require(raw.length <= MAX_BYTES)
        json.decodeFromString<List<SearchTitle>>(raw).takeLast(CAPACITY).mapNotNull(::sanitize)
    }.getOrDefault(emptyList())

    fun encode(items: Collection<SearchTitle>): String {
        val records = ArrayDeque<SearchTitle>()
        var bytes = 2 // JSON array brackets.
        for (item in items.takeLastBounded().asReversed().mapNotNull(::sanitize)) {
            val size = json.encodeToString(item).toByteArray(Charsets.UTF_8).size + if (records.isEmpty()) 0 else 1
            if (bytes + size > MAX_BYTES) break
            records.addFirst(item)
            bytes += size
        }
        return json.encodeToString(records.toList())
    }

    fun sanitize(item: SearchTitle): SearchTitle? {
        if (item.key.length !in 1..128 ||
            !validName(item.title) ||
            item.origin.length > 32 ||
            item.key.contains("://") ||
            item.origin.contains("://") ||
            item.key.any(Char::isISOControl) ||
            item.origin.any(Char::isISOControl)
        ) {
            return null
        }
        return item.copy(aliases = item.aliases.filter(::validName).distinct().take(15))
    }

    private fun validName(value: String) = value.isNotBlank() &&
        value.length <= 256 &&
        !value.contains("://") &&
        value.none(Char::isISOControl)

    private fun Collection<SearchTitle>.takeLastBounded() = toList().takeLast(CAPACITY)

    companion object {
        const val CAPACITY = 5_000
        const val MAX_BYTES = 3_000_000
    }
}
