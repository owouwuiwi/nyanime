package it.lorelore.nyanime.novel

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Notes survive changes, but an old quotation must not be applied to different chapter bytes. */
object NovelAnchors {
    enum class State {
        CURRENT,
        UNVERIFIED,
        CHANGED,
    }

    fun chapter(href: String): String = href.substringBefore('#').substringBefore('?')

    fun state(
        book: NovelBook,
        annotation: NovelAnnotation,
        chapters: Map<String, String>,
    ): State {
        if (annotation.bookId != book.id || annotation.contentVersion != book.contentVersion) {
            return State.CHANGED
        }
        if (book.format != NovelFormat.ONLINE) return State.CURRENT
        val previous = annotation.resourceVersion ?: return State.UNVERIFIED
        val reference =
            annotation.chapterReference
                ?: runCatching {
                    (NovelJson.parseToJsonElement(annotation.locatorJson) as? JsonObject)
                        ?.get("href")
                        ?.jsonPrimitive
                        ?.contentOrNull
                }
                    .getOrNull()
                ?: return State.CHANGED
        val href = chapter(reference)
        if (!href.matches(Regex("chapters/[a-f0-9]{64}\\.xhtml"))) return State.CHANGED
        val current = chapters[href] ?: return State.UNVERIFIED
        return if (previous == current) State.CURRENT else State.CHANGED
    }
}
