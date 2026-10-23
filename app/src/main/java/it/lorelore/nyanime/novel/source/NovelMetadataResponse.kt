package it.lorelore.nyanime.novel.source

import it.lorelore.nyanime.source.novel.NovelChapter
import it.lorelore.nyanime.source.novel.NovelDetails
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

internal data class NovelMetadataResponse(
    val registration: NovelSourceRegistration,
    val details: NovelDetails,
    val ordinaryChapters: List<NovelChapter>?,
)

/** A resumable index preserves the shared timeout of existing ordinary source calls. */
internal suspend fun requestNovelMetadata(
    sourceId: Long,
    reference: String,
    resolve: suspend (Long) -> NovelSourceRegistration,
    onDetails: (NovelDetails) -> Unit = {},
): NovelMetadataResponse = withTimeout(45_000) {
    val registration = resolve(sourceId)
    val details = registration.source.details(reference)
    currentCoroutineContext().ensureActive()
    require(details.entry.reference == reference) { "Novel details identity does not match" }
    val current = resolve(sourceId)
    check(
        current.source.id == registration.source.id &&
            current.providerId == registration.providerId &&
            current.packageName == registration.packageName &&
            current.revision == registration.revision,
    ) { "Novel source changed" }
    onDetails(details)
    NovelMetadataResponse(
        registration,
        details,
        if (registration.source is NovelChapterPreparation) null else registration.source.chapters(reference),
    )
}
