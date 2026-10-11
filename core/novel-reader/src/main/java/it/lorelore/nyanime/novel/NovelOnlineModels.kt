package it.lorelore.nyanime.novel

import kotlinx.serialization.Serializable
import java.security.MessageDigest

/** Site rules and opaque references are supplied by a source, never inferred by the reader. */
@Serializable
data class NovelOnlineChapter(
    val reference: String,
    val title: String,
    val number: Double? = null,
    val group: String? = null,
    val publishedAt: Long? = null,
) {
    init {
        require(reference.isNotBlank() && reference.length <= 8192 && title.length <= 2048)
        require(number == null || number.isFinite() && number >= 0)
        require(group == null || group.length <= 1024)
        require(publishedAt == null || publishedAt >= 0)
    }

    val href: String
        get() = "chapters/${NovelOnlineIdentity.digest(reference)}.xhtml"
}

/** Installation-local index: not embedded into the portable book or a Cloud record. */
@Serializable
data class NovelOnlineDocument(
    val version: Int = 1,
    val bookId: String,
    val origin: NovelOrigin,
    val chapters: List<NovelOnlineChapter>,
    val language: String? = null,
    val updatedAt: Long,
    val coverReference: String? = null,
    val alternativeTitles: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val status: String? = null,
) {
    init {
        require(version == 1 && bookId == NovelIdentity.remote(origin.sourceId, origin.reference))
        require(chapters.isNotEmpty() && chapters.size <= 10_000)
        require(chapters.map { it.reference }.distinct().size == chapters.size)
        require(language == null || language.length <= 35)
        require(updatedAt >= 0)
        require(coverReference == null || coverReference.length <= 8192)
        require(alternativeTitles.size <= 100 && alternativeTitles.all { it.length <= 2048 })
        require(genres.size <= 200 && genres.all { it.length <= 256 })
        require(status == null || status.length <= 64)
    }
}

data class NovelOnlineBody(
    val body: String,
    val html: Boolean,
    val resources: Map<String, String> = emptyMap(),
) {
    init {
        require(body.length <= NovelHtmlPolicy.MAX_HTML_BYTES && resources.size <= 200)
        require(
            resources.all { (htmlRef, sourceRef) ->
                htmlRef.isNotBlank() &&
                    htmlRef.length <= 8192 &&
                    sourceRef.isNotBlank() &&
                    sourceRef.length <= 8192
            },
        )
    }
}

object NovelOnlineIdentity {
    fun digest(value: String): String = digest(value.toByteArray(Charsets.UTF_8))

    fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Chapter order, titles and additions cannot move a structural anchor into another work. */
    fun edition(origin: NovelOrigin): String =
        "online-v1:${digest("${origin.sourceId}:${origin.reference}")}"

    fun resourceHref(reference: String): String = "assets/${digest(reference)}.image"
}
