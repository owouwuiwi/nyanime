package nyanime.news.api

import kotlinx.serialization.Serializable

/** News ABI v1. Implementations are loaded only after the APK signer is explicitly trusted. */
interface NewsSourceFactory {
    fun create(http: NewsHttpClient): NewsSource
}

interface NewsSource {
    val name: String
    val language: String
    val capabilities: NewsCapabilities
    suspend fun feed(request: NewsRequest): NewsPage
    suspend fun article(article: NewsArticle): NewsArticle
    suspend fun search(query: String, request: NewsRequest): NewsPage = error("Search is not supported")
}

/** The host supplies networking, cancellation and response limits. No Android dependency in the ABI. */
fun interface NewsHttpClient {
    suspend fun get(url: String, headers: Map<String, String>): NewsResponse
}

data class NewsResponse(val body: String, val finalUrl: String, val headers: Map<String, String>)

data class NewsCapabilities(
    val search: Boolean = false,
    val media: Set<NewsMedium> = emptySet(),
    val categories: List<NewsCategory> = emptyList(),
)

data class NewsCategory(val id: String, val label: String)

data class NewsRequest(
    val cursor: String? = null,
    val medium: NewsMedium? = null,
    val category: String? = null,
)

data class NewsPage(val articles: List<NewsArticle>, val nextCursor: String? = null)

@Serializable
enum class NewsMedium { ANIME, MANGA }

/** Only direct catalog references belong here. A guessed title match is never an identity. */
@Serializable
data class NewsCatalogId(val provider: String, val value: String, val medium: NewsMedium)

@Serializable
data class NewsTopic(
    val id: String,
    val title: String,
    val catalogIds: Set<NewsCatalogId> = emptySet(),
)

@Serializable
data class NewsArticle(
    val id: String,
    val url: String,
    val title: String,
    val publisher: String,
    val language: String,
    /** UTC epoch milliseconds, or null if the publisher supplied no verifiable publication time. */
    val publishedAt: Long? = null,
    val modifiedAt: Long? = null,
    val author: String? = null,
    val imageUrl: String? = null,
    val excerpt: String? = null,
    val media: Set<NewsMedium> = emptySet(),
    val categories: Set<String> = emptySet(),
    val topics: List<NewsTopic> = emptyList(),
    val blocks: List<NewsBlock> = emptyList(),
    val fullText: Boolean = false,
    val attribution: String? = null,
)

/** Text is sanitized Markdown; media remain links and are never executed by the reader. */
@Serializable
data class NewsBlock(
    val kind: NewsBlockKind,
    val text: String = "",
    val url: String? = null,
    val caption: String? = null,
)

@Serializable
enum class NewsBlockKind { TEXT, HEADING, IMAGE, MEDIA, QUOTE }
