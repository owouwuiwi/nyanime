package nyanime.news.api.layer

import nyanime.news.api.NewsArticle
import nyanime.news.api.NewsHttpClient
import nyanime.news.api.NewsPage
import nyanime.news.api.NewsRequest
import nyanime.news.api.NewsSource

/** A layer receives the original trusted source and the host's bounded news HTTP client. */
interface NewsLayerFactoryV1 {
    fun attach(source: NewsSource, http: NewsHttpClient): NewsLayerV1
}

/** A null result delegates the operation to the original source. */
interface NewsLayerV1 {
    suspend fun feed(request: NewsRequest): NewsPage? = null
    suspend fun article(article: NewsArticle): NewsArticle? = null
    suspend fun search(query: String, request: NewsRequest): NewsPage? = null
}
