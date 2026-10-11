package eu.kanade.tachiyomi.data.news

import android.content.Context
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import nyanime.news.api.NewsHttpClient
import nyanime.news.api.NewsPage
import nyanime.news.api.NewsRequest
import nyanime.news.api.NewsResponse
import nyanime.news.api.NewsSource
import okhttp3.Request
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class NewsRepository(private val context: Context, network: NetworkHelper) {
    val store = NewsStore(context)
    val catalogue = NewsExtensionCatalogue(context, network)
    private val relations = NewsRelations(network, store)
    private val relationLock = Mutex()
    private val personalizationLock = Mutex()
    private val semaphore = Semaphore(3)
    private val requests = NewsRequestGate()
    private val mutableLoading = MutableStateFlow<Set<String>>(emptySet())
    val loading = mutableLoading.asStateFlow()
    private val client = network.client.newBuilder().callTimeout(30, TimeUnit.SECONDS).build()
    val registry = NewsExtensionRegistry(
        context,
        NewsHttpClient { url, headers ->
            check(!Injekt.get<BasePreferences>().downloadedOnly().get()) { "Offline mode" }
            require(NewsRules.webUrl(url))
            val request = Request.Builder().url(url).apply {
                headers.forEach { (name, value) -> header(name, value) }
            }.build()
            client.newCall(request).await().use { response ->
                if (!response.isSuccessful) {
                    throw NewsHttpException(
                        response.code,
                        retryAfter(response.header("Retry-After")),
                    )
                }
                require(NewsRules.webUrl(response.request.url.toString()))
                val body = response.body ?: throw IOException("Empty response")
                val buffer = okio.Buffer()
                val source = body.source()
                while (!source.exhausted()) {
                    val count = source.read(buffer, 8192)
                    if (count < 0) break
                    if (buffer.size > 4 * 1024 * 1024) throw IOException("Response exceeds the news limit")
                }
                NewsResponse(
                    buffer.readString(body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8),
                    response.request.url.toString(),
                    response.headers.toMap(),
                )
            }
        },
    )

    suspend fun initialize() {
        store.load()
        registry.reload(store.state.value.sources)
        NewsRefreshWorker.schedule(
            context,
            store.state.value.sources.values.any {
                it.enabled &&
                    it.alerts != NewsAlerts.OFF
            },
        )
    }

    suspend fun configure(extension: NewsExtension, enabled: Boolean, alerts: NewsAlerts? = null) {
        require(!enabled || extension.compatible)
        store.update { snapshot ->
            val old = snapshot.sources[extension.packageName] ?: NewsSourceSettings()
            snapshot.copy(
                checks = if (
                    enabled &&
                    !old.enabled ||
                    old.alerts == NewsAlerts.OFF &&
                    alerts != null &&
                    alerts != NewsAlerts.OFF
                ) {
                    snapshot.checks - extension.packageName
                } else {
                    snapshot.checks
                },
                sources =
                snapshot.sources +
                    (
                        extension.packageName to old.copy(
                            enabled = enabled,
                            signers = extension.metadata.signers,
                            distribution = extension.metadata.distribution?.id,
                            alerts = alerts ?: old.alerts,
                        )
                        ),
                pendingClassification = snapshot.pendingClassification.filterTo(mutableSetOf()) {
                    snapshot.articles[it]?.source != extension.packageName
                },
            )
        }
        initialize()
    }

    suspend fun refresh(
        force: Boolean = false,
        notificationsOnly: Boolean = false,
        personalLibrary: NewsPersonalLibrary? = null,
    ) = withContext(Dispatchers.IO) { refreshFeeds(force, notificationsOnly, personalLibrary) }

    private suspend fun refreshFeeds(
        force: Boolean,
        notificationsOnly: Boolean,
        personalLibrary: NewsPersonalLibrary?,
    ) = coroutineScope {
        initialize()
        if (Injekt.get<BasePreferences>().downloadedOnly().get()) return@coroutineScope
        val library = personalLibrary ?: if (store.state.value.sources.values.any {
                it.enabled && it.alerts == NewsAlerts.PERSONAL
            }
        ) {
            NewsPersonalTitles().load()
        } else {
            NewsPersonalLibrary()
        }
        registry.state.value.filter { it.source != null }.map { extension ->
            async {
                val id = extension.packageName
                val now = System.currentTimeMillis()
                val check = store.state.value.checks[id] ?: NewsCheck()
                if (notificationsOnly && store.state.value.sources[id]?.alerts == NewsAlerts.OFF) return@async
                if (now < check.retryAt || (!force && now - check.checkedAt < 15 * 60_000)) return@async
                try {
                    guarded(id) { source ->
                        // Recheck after acquiring the per-source lock: concurrent refreshes share the result.
                        val previous = store.state.value
                        if (!force && now - (previous.checks[id]?.checkedAt ?: 0) < 15 * 60_000) return@guarded
                        val page = source.feed(NewsRequest())
                        require(page.articles.size <= 200)
                        val personal = NewsRules.personalIds(previous, library.ids)
                        store.update { current ->
                            val incoming = page.articles.map { StoredNews(id, NewsRules.validate(it), now) }
                            val index = NewsInterestIndex(current, library)
                            val pending = incoming.filter { NewsRules.shouldNotify(it, current, personal, now, index) }
                            val classify = if (current.sources[id]?.alerts == NewsAlerts.PERSONAL) {
                                incoming.filter { NewsRules.eligibleForAlert(it, current, now) && it !in pending }
                            } else {
                                emptyList()
                            }
                            NewsRules.merge(current, id, page.articles, now).copy(
                                checks =
                                current.checks +
                                    (
                                        id to
                                            NewsCheck(
                                                checkedAt = now,
                                                baseline =
                                                current.checks[id]?.baseline?.takeIf { value ->
                                                    value > 0
                                                } ?: now,
                                                nextCursor = page.nextCursor,
                                            )
                                        ),
                                pending = current.pending + pending.map { item -> item.key },
                                pendingClassification = current.pendingClassification + classify.map { it.key },
                            )
                        }
                    }
                } catch (error: Exception) {
                    if (error is CancellationException && error !is TimeoutCancellationException) throw error
                    store.update { snapshot ->
                        val old = snapshot.checks[id] ?: NewsCheck()
                        val attempts = (old.failures + 1).coerceAtMost(8)
                        val delay = (error as? NewsHttpException)?.retryAt?.takeIf { it > now }
                            ?: (now + (60_000L shl attempts).coerceAtMost(6 * 3_600_000))
                        snapshot.copy(
                            checks =
                            snapshot.checks + (id to old.copy(error = true, failures = attempts, retryAt = delay)),
                        )
                    }
                }
            }
        }.awaitAll()
        if (notificationsOnly && library.titles.isNotEmpty()) personalize(library, notificationsOnly = true)
        NewsNotifications(context, this@NewsRepository).deliver()
    }

    suspend fun refreshRelations(roots: Set<nyanime.news.api.NewsCatalogId>) = relationLock.withLock {
        if (!Injekt.get<BasePreferences>().downloadedOnly().get()) {
            semaphore.withPermit { relations.refresh(roots) }
        }
    }

    /** Small, source-neutral enrichment passes. Cached cards and reading never wait for this. */
    suspend fun personalize(
        library: NewsPersonalLibrary,
        notificationsOnly: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        if (library.titles.isEmpty() || Injekt.get<BasePreferences>().downloadedOnly().get()) return@withContext
        if (!personalizationLock.tryLock()) return@withContext
        try {
            refreshRelations(library.ids)
            val snapshot = store.state.value
            val enabled = registry.state.value.filter {
                it.source != null &&
                    (
                        !notificationsOnly || snapshot.sources[it.packageName]?.alerts == NewsAlerts.PERSONAL
                        )
            }.map { it.packageName }.toSet()
            val now = System.currentTimeMillis()
            val index = NewsInterestIndex(snapshot, library)
            val due = snapshot.articles.values.filter {
                it.source in enabled &&
                    it.article.blocks.isEmpty() &&
                    now - (snapshot.metadataChecks[it.key] ?: 0) >= 86_400_000 &&
                    (it.key in snapshot.pendingClassification || index.match(it)?.reliable != true) &&
                    now - it.acquiredAt <= 7 * 86_400_000L
            }.sortedWith(
                compareByDescending<StoredNews> { it.key in snapshot.pendingClassification }
                    .thenByDescending { it.article.publishedAt ?: it.acquiredAt },
            )
                .groupBy { it.source }.values.flatMap { it.take(3) }.take(12)
            coroutineScope {
                due.map { item ->
                    async {
                        try {
                            guarded(item.source) { source ->
                                val current = store.state.value
                                if (current.articles[item.key]?.article?.blocks?.isNotEmpty() == true ||
                                    now - (current.metadataChecks[item.key] ?: 0) < 86_400_000
                                ) {
                                    return@guarded
                                }
                                val result = withTimeoutOrNull(15_000) { source.article(item.article) }
                                    ?: throw IOException("Metadata request timed out")
                                val detail = NewsRules.validate(result)
                                require(detail.id == item.article.id && detail.url == item.article.url)
                                store.update {
                                    NewsRules.merge(it, item.source, listOf(detail), now)
                                        .copy(metadataChecks = it.metadataChecks + (item.key to now))
                                }
                            }
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (_: Exception) {
                            store.update {
                                it.copy(metadataChecks = it.metadataChecks + (item.key to (now - 23 * 3_600_000)))
                            }
                        }
                    }
                }.awaitAll()
            }
            store.update { NewsRules.classifyPending(it, library, System.currentTimeMillis()) }
            NewsNotifications(context, this@NewsRepository).deliver()
        } finally {
            personalizationLock.unlock()
        }
    }

    suspend fun page(id: String, request: NewsRequest, query: String = ""): NewsPage = guarded(id) { source ->
        val page = if (query.isBlank()) source.feed(request) else source.search(query, request)
        require(page.articles.size <= 200)
        page.articles.forEach(NewsRules::validate)
        store.update { NewsRules.merge(it, id, page.articles, System.currentTimeMillis()) }
        page
    }

    suspend fun detail(key: String): StoredNews {
        store.load()
        val item = requireNotNull(store.state.value.articles[key])
        if (item.article.blocks.isNotEmpty()) return item
        return guarded(item.source) { source ->
            val detail = NewsRules.validate(source.article(item.article))
            require(detail.id == item.article.id && detail.url == item.article.url)
            store.update { NewsRules.merge(it, item.source, listOf(detail), System.currentTimeMillis()) }
            requireNotNull(store.state.value.articles[key])
        }
    }

    suspend fun save(key: String, saved: Boolean) {
        if (saved) detail(key)
        store.update {
            it.copy(
                articles = it.articles.mapValues { (id, item) ->
                    if (id ==
                        key
                    ) {
                        item.copy(saved = saved)
                    } else {
                        item
                    }
                },
            )
        }
    }

    suspend fun position(key: String, position: Int, offset: Int) = store.update {
        it.copy(
            articles = it.articles.mapValues { (id, item) ->
                if (id ==
                    key
                ) {
                    item.copy(read = true, position = position.coerceAtLeast(0), offset = offset.coerceAtLeast(0))
                } else {
                    item
                }
            },
        )
    }

    private suspend fun <T> guarded(id: String, block: suspend (NewsSource) -> T): T = withContext(Dispatchers.IO) {
        requests.run(id) {
            val source =
                registry.state.value.firstOrNull { it.packageName == id }?.source
                    ?: throw IOException("Source unavailable")
            semaphore.withPermit {
                mutableLoading.update { it + id }
                try {
                    val result = withTimeoutOrNull(45_000) { Result.success(block(source)) }
                        ?: throw IOException("News request timed out")
                    result.getOrThrow()
                } finally {
                    mutableLoading.update { it - id }
                }
            }
        }
    }

    private fun retryAfter(value: String?): Long =
        value?.toLongOrNull()?.let { System.currentTimeMillis() + it.coerceIn(0, 86_400) * 1000 }
            ?: runCatching {
                ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            }.getOrDefault(0)
}

private class NewsHttpException(val status: Int, val retryAt: Long) : IOException("HTTP $status")
