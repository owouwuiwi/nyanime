package it.lorelore.nyanime.novel

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.security.MessageDigest
import java.util.UUID

/** Book identities belong to the novel store, never to manga pages or chapter numbers. */
object NovelIdentity {
    fun local(contentHash: String): String {
        require(contentHash.matches(Regex("[a-f0-9]{64}")))
        return digest("novel-local-v1:$contentHash")
    }

    fun remote(sourceId: Long, reference: String): String {
        require(sourceId != 0L && reference.isNotBlank() && reference.length <= 8192)
        return digest("novel-remote-v1:$sourceId:$reference")
    }

    private fun digest(value: String): String =
        "b_" +
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
}

@Serializable
enum class NovelFormat {
    EPUB,
    TXT,
    ONLINE,
}

@Serializable
data class NovelOrigin(val sourceId: Long, val reference: String) {
    init {
        require(sourceId != 0L && reference.isNotBlank() && reference.length <= 8192)
    }
}

@Serializable
data class NovelBook(
    val id: String,
    val title: String,
    val authors: List<String> = emptyList(),
    val description: String = "",
    val format: NovelFormat,
    val contentVersion: String,
    val origin: NovelOrigin? = null,
    @Transient val originalFile: String? = null,
    @Transient val publicationFile: String? = null,
    @Transient val coverFile: String? = null,
    val textEncoding: String? = null,
    val favorite: Boolean = true,
    val importedAt: Long,
    val modifiedAt: Long,
) {
    init {
        require(id.matches(Regex("b_[a-f0-9]{64}")))
        require(title.isNotBlank() && title.length <= 2048)
        require(authors.size <= 100 && authors.all { it.length <= 1024 })
        require(description.length <= 65536)
        require(contentVersion.isNotBlank() && contentVersion.length <= 256)
        require(textEncoding == null || textEncoding.length <= 128)
        require(importedAt >= 0 && modifiedAt >= 0)
        require(format != NovelFormat.ONLINE || origin != null)
        listOfNotNull(originalFile, publicationFile, coverFile).forEach { path ->
            require(path.length <= 512 && path.matches(Regex("[a-zA-Z0-9_./-]+")))
            require(
                !path.startsWith('/') &&
                    path.split('/').none { it.isEmpty() || it == "." || it == ".." },
            )
        }
    }
}

/**
 * Readium locator JSON is retained verbatim; displayed pagination is never persisted as identity.
 */
@Serializable
data class NovelPosition(
    val bookId: String,
    val contentVersion: String,
    val locatorJson: String,
    val updatedAt: Long,
    val totalProgression: Double? = null,
    val chapterReference: String? = null,
    val resourceVersion: String? = null,
) {
    init {
        require(bookId.matches(Regex("b_[a-f0-9]{64}")))
        require(contentVersion.isNotBlank() && contentVersion.length <= 256)
        require(locatorJson.length in 2..65536 && updatedAt >= 0)
        val point = NovelJson.parseToJsonElement(locatorJson)
        require(point is JsonObject)
        require(
            totalProgression == null || totalProgression.isFinite() && totalProgression in 0.0..1.0,
        )
        require(chapterReference == null || chapterReference.length <= 8192)
        require(
            chapterReference == null ||
                (point["href"] as? JsonPrimitive)?.contentOrNull?.let {
                    NovelAnchors.chapter(it) == NovelAnchors.chapter(chapterReference)
                } == true,
        )
        require(resourceVersion == null || resourceVersion.matches(Regex("[a-f0-9]{64}")))
    }
}

@Serializable
enum class NovelAnnotationKind {
    BOOKMARK,
    HIGHLIGHT,
    NOTE,
}

@Serializable
data class NovelAnnotation(
    val id: String = UUID.randomUUID().toString(),
    val bookId: String,
    val contentVersion: String,
    val locatorJson: String,
    val kind: NovelAnnotationKind,
    val quote: String = "",
    val note: String = "",
    val color: Long = 0xFFFFCF62,
    val createdAt: Long,
    val modifiedAt: Long,
    val deleted: Boolean = false,
    val chapterReference: String? = null,
    val resourceVersion: String? = null,
) {
    init {
        require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false))
        require(bookId.matches(Regex("b_[a-f0-9]{64}")))
        require(contentVersion.isNotBlank() && contentVersion.length <= 256)
        require(locatorJson.length in 2..65536)
        val point = NovelJson.parseToJsonElement(locatorJson)
        require(point is JsonObject)
        require(quote.length <= 8192 && note.length <= 65536)
        require(color in 0..0xFFFFFFFF && createdAt >= 0 && modifiedAt >= 0)
        require(chapterReference == null || chapterReference.length <= 8192)
        require(
            chapterReference == null ||
                (point["href"] as? JsonPrimitive)?.contentOrNull?.let {
                    NovelAnchors.chapter(it) == NovelAnchors.chapter(chapterReference)
                } == true,
        )
        require(resourceVersion == null || resourceVersion.matches(Regex("[a-f0-9]{64}")))
    }
}

@Serializable
enum class NovelTheme {
    DEVICE,
    LIGHT,
    DARK,
    PAPER,
}

@Serializable
enum class NovelAlignment {
    START,
    JUSTIFY,
}

@Serializable
data class NovelAppearance(
    val fontFamily: String = "serif",
    val fontSize: Double = 1.0,
    val lineHeight: Double = 1.5,
    val margins: Double = 1.0,
    val alignment: NovelAlignment = NovelAlignment.START,
    val theme: NovelTheme = NovelTheme.DEVICE,
    val scroll: Boolean = false,
    val publisherStyles: Boolean = false,
    val brightness: Float? = null,
) {
    init {
        require(fontFamily in setOf("serif", "sans-serif", "monospace"))
        require(fontSize.isFinite() && fontSize in 0.6..3.0)
        require(lineHeight.isFinite() && lineHeight in 0.8..3.0)
        require(margins.isFinite() && margins in 0.0..3.0)
        require(brightness == null || brightness.isFinite() && brightness in 0f..1f)
    }
}

internal val NovelJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    // Explicit nulls clear previous known fields during a forward-compatible merge.
    explicitNulls = true
}
