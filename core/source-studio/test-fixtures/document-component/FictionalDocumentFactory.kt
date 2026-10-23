package it.lorelore.nyanime.fixture.documents

import it.lorelore.nyanime.source.document.ResolvedSourceDocument
import it.lorelore.nyanime.source.document.SourceDocumentNetwork
import it.lorelore.nyanime.source.document.SourceDocumentRequest
import it.lorelore.nyanime.source.document.SourceDocumentResolver
import it.lorelore.nyanime.source.novel.NovelChapter
import it.lorelore.nyanime.source.novel.NovelContent
import it.lorelore.nyanime.source.novel.NovelDetails
import it.lorelore.nyanime.source.novel.NovelSearchPage
import it.lorelore.nyanime.source.novel.NovelSearchRequest
import it.lorelore.nyanime.source.novel.NovelSource
import it.lorelore.nyanime.source.novel.NovelSourceFactory
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/** Compiled into a separate fixture DEX asset, never the host or instrumentation classpath. */
class FictionalDocumentFactory : NovelSourceFactory {
    override fun createNovelSources(): List<NovelSource> = listOf(FictionalDocumentSource())
}

private class FictionalDocumentSource : NovelSource, SourceDocumentResolver {
    override val id = 800101L
    override val name = "Fictional documents"
    override val lang = "all"
    override val documentResolverId = "fixture.document"
    override val documentResolverName = "Fictional documents"
    override val documentResolverHosts = setOf("fixture.invalid")
    override suspend fun search(request: NovelSearchRequest): NovelSearchPage = error("Unused")
    override suspend fun details(reference: String): NovelDetails = error("Unused")
    override suspend fun chapters(reference: String): List<NovelChapter> = error("Unused")
    override suspend fun content(chapterReference: String): NovelContent = error("Unused")
    override suspend fun resolveDocument(reference: String, network: SourceDocumentNetwork): ResolvedSourceDocument {
        if (reference == "loop") while (true) Thread.yield()
        if (reference.startsWith("private:")) {
            val privateFileDenied = !File(reference.removePrefix("private:")).canRead()
            val networkDenied = try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", 1), 1000) }
                false
            } catch (failure: Exception) {
                failure is SecurityException ||
                    failure.message.orEmpty().let {
                        it.contains("Permission denied", true) || it.contains("EACCES") || it.contains("EPERM")
                    }
            }
            return ResolvedSourceDocument(
                reference,
                "Isolation",
                "text/plain",
                "$privateFileDenied:$networkDenied".toByteArray(),
            )
        }
        val response = network.request(
            SourceDocumentRequest(
                if (reference == "forbidden-origin") {
                    "https://undeclared.invalid/document"
                } else {
                    "https://fixture.invalid/document"
                },
            ),
        )
        require(response.status == 200)
        return ResolvedSourceDocument(reference, "Fictional chapter", "text/plain", response.body)
    }
}
