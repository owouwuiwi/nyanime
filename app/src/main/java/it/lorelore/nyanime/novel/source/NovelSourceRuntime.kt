package it.lorelore.nyanime.novel.source

import it.lorelore.nyanime.novel.NovelOnlineBody
import it.lorelore.nyanime.novel.NovelOnlineFetcher
import it.lorelore.nyanime.novel.NovelOnlineImage
import it.lorelore.nyanime.source.novel.NovelResourceRequest
import it.lorelore.nyanime.source.novel.NovelTextFormat
import kotlinx.coroutines.withTimeout

/** A session belongs to one exact installed revision, rechecked around every source operation. */
internal class NovelSourceRuntime(
    private val expected: NovelSourceRegistration,
    private val resolve: suspend () -> NovelSourceRegistration,
    private val imageLoader: suspend (NovelResourceRequest) -> NovelOnlineImage,
) : NovelOnlineFetcher {
    private suspend fun current(): NovelSourceRegistration =
        resolve().also {
            check(
                it.packageName == expected.packageName &&
                    it.providerId == expected.providerId &&
                    it.revision == expected.revision &&
                    it.source.id == expected.source.id,
            ) {
                "Novel source changed during reading"
            }
        }

    override suspend fun chapter(reference: String): NovelOnlineBody =
        withTimeout(30_000) {
            val response = current().source.content(reference)
            current()
            require(response.reference == reference) { "Chapter content identity does not match" }
            val resources = linkedMapOf<String, String>()
            response.resources.forEach {
                val previous = resources.put(it.htmlReference, it.reference)
                require(previous == null || previous == it.reference) {
                    "Conflicting chapter image references"
                }
            }
            NovelOnlineBody(response.body, response.format == NovelTextFormat.HTML, resources)
        }

    override suspend fun image(reference: String): NovelOnlineImage =
        withTimeout(30_000) {
            val request = current().source.resourceRequest(reference)
            val image = imageLoader(request)
            current()
            image
        }
}
