package it.lorelore.nyanime.source.novel

/** Optional capability: no anime, manga or existing extension interface is changed. */
interface NovelSource {
    val id: Long
    val name: String
    val lang: String
    val isNsfw: Boolean
        get() = false

    suspend fun search(request: NovelSearchRequest): NovelSearchPage

    suspend fun details(reference: String): NovelDetails

    suspend fun chapters(reference: String): List<NovelChapter>

    suspend fun content(chapterReference: String): NovelContent

    /**
     * Resolve only resources explicitly returned by this source; credentials remain session data.
     */
    suspend fun resourceRequest(reference: String): NovelResourceRequest =
        NovelResourceRequest(reference)
}

interface NovelSourceFactory {
    fun createNovelSources(): List<NovelSource>
}

data class NovelSearchRequest(
    val query: String,
    /** Opaque, independently retained for every source; null starts a new search. */
    val cursor: String? = null,
    val filters: Map<String, String> = emptyMap(),
) {
    init {
        require(query.length <= 2048 && (cursor == null || cursor.length <= 8192))
        require(
            filters.size <= 100 &&
                filters.all { (key, value) -> key.length in 1..256 && value.length <= 8192 },
        )
    }
}

data class NovelSearchPage(val items: List<NovelEntry>, val nextCursor: String? = null) {
    init {
        require(items.size <= 1000 && (nextCursor == null || nextCursor.length <= 8192))
    }
}

data class NovelEntry(
    val reference: String,
    val title: String,
    val cover: String? = null,
    val authors: List<String> = emptyList(),
    val alternativeTitles: List<String> = emptyList(),
) {
    init {
        require(reference.isNotBlank() && reference.length <= 8192)
        require(title.isNotBlank() && title.length <= 2048)
        require(cover == null || cover.length <= 8192)
        require(authors.size <= 100 && authors.all { it.length <= 1024 })
        require(alternativeTitles.size <= 100 && alternativeTitles.all { it.length <= 2048 })
    }
}

enum class NovelStatus {
    UNKNOWN,
    ONGOING,
    COMPLETED,
    HIATUS,
    CANCELLED,
}

data class NovelDetails(
    val entry: NovelEntry,
    val description: String = "",
    val genres: List<String> = emptyList(),
    val status: NovelStatus = NovelStatus.UNKNOWN,
    val language: String? = null,
) {
    init {
        require(description.length <= 65536)
        require(genres.size <= 200 && genres.all { it.length <= 256 })
        require(language == null || language.length <= 35)
    }
}

data class NovelChapter(
    val reference: String,
    val title: String,
    /** Provider-declared number, never guessed from text or EPUB pagination. */
    val number: Double? = null,
    val group: String? = null,
    /** Unknown publication dates remain unknown; discovery time is not a release date. */
    val publishedAt: Long? = null,
) {
    init {
        require(reference.isNotBlank() && reference.length <= 8192 && title.length <= 2048)
        require(number == null || number.isFinite() && number >= 0)
        require(group == null || group.length <= 1024)
        require(publishedAt == null || publishedAt >= 0)
    }
}

enum class NovelTextFormat {
    TEXT,
    HTML,
}

/** Untrusted author HTML is sanitized and materialized before the offline navigator sees it. */
data class NovelContent(
    val reference: String,
    val title: String,
    val body: String,
    val format: NovelTextFormat = NovelTextFormat.HTML,
    val resources: List<NovelResourceReference> = emptyList(),
) {
    init {
        require(reference.isNotBlank() && reference.length <= 8192 && title.length <= 2048)
        require(body.length <= 4 * 1024 * 1024 && resources.size <= 200)
    }
}

/** Maps an input HTML resource URL to a source-owned opaque retrieval reference. */
data class NovelResourceReference(val htmlReference: String, val reference: String) {
    init {
        require(htmlReference.isNotBlank() && htmlReference.length <= 8192)
        require(reference.isNotBlank() && reference.length <= 8192)
    }
}

/** Transient request, never an exported source definition or cloud book payload. */
data class NovelResourceRequest(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
) {
    init {
        require(url.length in 1..8192 && headers.size <= 100)
        require(
            headers.all { (name, value) ->
                name.length in 1..256 &&
                    name.none { it.code <= 32 || it.code >= 127 } &&
                    value.length <= 8192 &&
                    value.none { it == '\r' || it == '\n' || it == '\u0000' }
            },
        )
    }
}
