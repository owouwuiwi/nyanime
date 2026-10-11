package it.lorelore.nyanime.novel

import kotlinx.serialization.Serializable

/** Installation-local ownership record. Never part of a portable book, backup or cloud journal. */
@Serializable
internal data class NovelDocument(
    val bookId: String,
    val contentVersion: String,
    val originalFile: String? = null,
    val publicationFile: String? = null,
    val coverFile: String? = null,
    val verifiedAt: Long,
) {
    init {
        require(bookId.matches(Regex("b_[a-f0-9]{64}")))
        require(contentVersion.isNotBlank() && contentVersion.length <= 256 && verifiedAt >= 0)
        require(listOfNotNull(originalFile, publicationFile, coverFile).isNotEmpty())
        listOfNotNull(originalFile, publicationFile, coverFile).forEach { path ->
            require(path.length <= 512 && path.matches(Regex("[a-zA-Z0-9_./-]+")))
            require(path.startsWith("books/$bookId/"))
            require(path.split('/').none { it.isBlank() || it == "." || it == ".." })
        }
    }
}
