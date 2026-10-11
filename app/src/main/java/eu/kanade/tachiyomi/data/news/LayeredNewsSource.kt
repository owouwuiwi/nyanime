package eu.kanade.tachiyomi.data.news

import nyanime.news.api.NewsArticle
import nyanime.news.api.NewsRequest
import nyanime.news.api.NewsSource
import nyanime.news.api.layer.NewsLayerV1
import java.util.concurrent.atomic.AtomicBoolean

internal class NewsLayerBinding(
    private val layer: NewsLayerV1,
    private val failed: () -> Unit,
) {
    private val rejected = AtomicBoolean(false)
    val incompatible: Boolean get() = rejected.get()

    suspend fun <T> await(block: suspend NewsLayerV1.() -> T?): T? {
        if (incompatible) return null
        return try {
            layer.block()
        } catch (_: LinkageError) {
            if (rejected.compareAndSet(false, true)) failed()
            null
        }
    }
}

internal class LayeredNewsSource(
    private val original: NewsSource,
    private val bindings: Map<String, NewsLayerBinding>,
) : NewsSource {
    override val name get() = original.name
    override val language get() = original.language
    override val capabilities get() = original.capabilities.let { base ->
        if (base.search || bindings["search"]?.incompatible != false) base else base.copy(search = true)
    }

    override suspend fun feed(request: NewsRequest) =
        bindings["feed"]?.await { feed(request) } ?: original.feed(request)

    override suspend fun article(article: NewsArticle) =
        bindings["article"]?.await { article(article) } ?: original.article(article)

    override suspend fun search(query: String, request: NewsRequest) =
        bindings["search"]?.await { search(query, request) } ?: original.search(query, request)
}
