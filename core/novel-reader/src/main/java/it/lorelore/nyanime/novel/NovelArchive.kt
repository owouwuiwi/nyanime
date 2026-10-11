package it.lorelore.nyanime.novel

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Portable metadata only. Document paths, book bytes, responses, cookies and caches have no fields
 * here.
 */
@Serializable
data class NovelRecord(
    val kind: String,
    val key: String,
    val bookId: String? = null,
    val updatedAt: Long,
    val payload: JsonElement,
) {
    init {
        require(kind.matches(Regex("[a-z][a-z0-9_-]{0,39}")))
        require(key.isNotBlank() && key.length <= 256 && key.none(Char::isISOControl))
        require(bookId == null || bookId.matches(Regex("b_[a-f0-9]{64}")))
        // Leave room for the record envelope inside the existing512KiB Cloud field budget.
        require(updatedAt >= 0 && payload.toString().toByteArray(Charsets.UTF_8).size <= 480 * 1024)
        when (kind) {
            "book" -> {
                val book = NovelJson.decodeFromJsonElement<NovelBook>(payload)
                require(key == book.id && bookId == book.id && updatedAt == book.modifiedAt)
                require((payload as JsonObject).keys.none { it in PRIVATE_BOOK_FIELDS })
                val origin = book.origin
                if (origin != null) {
                    require(
                        book.format == NovelFormat.ONLINE &&
                            book.id == NovelIdentity.remote(origin.sourceId, origin.reference),
                    )
                } else {
                    val fingerprint = book.contentVersion.substringBefore(':')
                    require(
                        book.format != NovelFormat.ONLINE &&
                            book.id == NovelIdentity.local(fingerprint),
                    )
                }
            }
            "position" -> {
                val point = NovelJson.decodeFromJsonElement<NovelPosition>(payload)
                require(
                    key == point.bookId && bookId == point.bookId && updatedAt == point.updatedAt,
                )
            }
            "annotation" -> {
                val note = NovelJson.decodeFromJsonElement<NovelAnnotation>(payload)
                require(key == note.id && bookId == note.bookId && updatedAt == note.modifiedAt)
            }
            "appearance" -> {
                require(key == (bookId ?: "global"))
                if (payload != JsonNull) NovelJson.decodeFromJsonElement<NovelAppearance>(payload)
            }
        }
    }

    companion object {
        internal val PRIVATE_BOOK_FIELDS = setOf("originalFile", "publicationFile", "coverFile")
    }
}

@Serializable
data class NovelArchive(
    val version: Int = 1,
    val records: List<NovelRecord> = emptyList(),
) {
    init {
        require(version == 1) { "Unsupported novel metadata archive version" }
        require(records.size <= 200_000)
        require(records.map { it.kind to it.key }.distinct().size == records.size) {
            "Duplicate novel record"
        }
    }

    companion object {
        const val MAX_BYTES = 32 * 1024 * 1024

        fun decode(text: String): NovelArchive {
            require(text.length <= MAX_BYTES && text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
            var depth = 0
            var quoted = false
            var escaped = false
            for (character in text) {
                if (quoted) {
                    if (escaped) {
                        escaped = false
                    } else if (character == '\\') {
                        escaped = true
                    } else if (character == '"') {
                        quoted = false
                    }
                } else {
                    when (character) {
                        '"' -> quoted = true
                        '{',
                        '[',
                        -> require(++depth <= 64) { "Novel metadata nesting exceeds its limit" }
                        '}',
                        ']',
                        -> require(--depth >= 0)
                    }
                }
            }
            require(depth == 0 && !quoted)
            return NovelJson.decodeFromString(text)
        }
    }

    fun encode(): String =
        NovelJson.encodeToString(this).also {
            require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        }
}
