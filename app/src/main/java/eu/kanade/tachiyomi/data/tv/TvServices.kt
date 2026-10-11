package eu.kanade.tachiyomi.data.tv

import android.content.Context
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.rin.RinArchive
import eu.kanade.tachiyomi.data.rin.RinLoading
import eu.kanade.tachiyomi.data.rin.RinRecord
import eu.kanade.tachiyomi.data.rin.RinRepository
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.util.system.ChildFirstPathClassLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import nyanime.tv.api.TvHttpClient
import nyanime.tv.api.TvHttpResponse
import nyanime.tv.api.TvRules
import nyanime.tv.api.TvSourceFactory
import okhttp3.Request
import okio.Buffer
import tachiyomi.core.common.preference.PreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Created on demand. Installing a TV extension does not start any catalogue/stream scan. */
class TvServices(context: Context, network: NetworkHelper, preferences: PreferenceStore) {
    private val ready = kotlinx.coroutines.flow.MutableStateFlow(false)
    val initialized = ready.asStateFlow()
    val registry = TvRegistry()
    val personal = TvPreferences(preferences)
    val repository =
        TvRepository(registry, personal, allowAdult = { Injekt.get<SourcePreferences>().showNsfwSource().get() })
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sourcePreferences = Injekt.get<SourcePreferences>()
    val adultContent = sourcePreferences.showNsfwSource().changes().stateIn(
        scope,
        SharingStarted.Eagerly,
        sourcePreferences.showNsfwSource().get(),
    )
    private val rin = RinRepository.get(context)
    private val client = network.client.newBuilder().callTimeout(25, TimeUnit.SECONDS).build()
    private val loaded = mutableMapOf<String, List<TvRegistration>>()

    init {
        scope.launch {
            combine(rin.installed, adultContent) { records, adult ->
                records to adult
            }.collectLatest { (records, adult) ->
                val available = mutableListOf<TvRegistration>()
                val failures = mutableSetOf<String>()
                records.filter {
                    it.enabled &&
                        it.manifest.medium == "tv" &&
                        (!it.manifest.nsfw || adult) &&
                        RinRepository.compatible(it.manifest)
                }.forEach { record ->
                    try {
                        val registered = loaded.getOrPut(record.sha256) { load(context.applicationContext, record) }
                        RinLoading.guard { registry.validate(registered) }
                        available += registered
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        failures += record.manifest.packageName
                    }
                }
                loaded.keys.retainAll(records.filter { it.enabled }.map { it.sha256 }.toSet())
                // Never choose one publisher silently when opaque source identities collide.
                val collisions = available.groupBy { it.source.id }.filterValues { it.size > 1 }.keys
                failures += available.filter { it.source.id in collisions }.map { it.packageName }
                val usable = available.filter { it.source.id !in collisions }
                if (usable.size > 128) {
                    failures += usable.map { it.packageName }
                    registry.replace(emptyList(), failures)
                } else {
                    registry.replace(usable, failures)
                }
                ready.value = true
            }
        }
    }

    private fun load(context: Context, record: RinRecord): List<TvRegistration> {
        val manifest = record.manifest
        require(manifest.extensionLib == 1.0 && manifest.entryPoints.size == 1)
        val file = rin.storage.file(record)
        require(RinArchive.verify(file, record.signer, record.sha256) == manifest)
        val loader = ChildFirstPathClassLoader(file.absolutePath, null, context.classLoader)
        val factory = RinLoading.guard {
            Class.forName(
                manifest.entryPoints.single(),
                false,
                loader,
            ).getDeclaredConstructor().newInstance() as TvSourceFactory
        }
        val cache = TvDiskCache(File(context.cacheDir, "tv/${RinArchive.sha256(manifest.packageName.toByteArray())}"))
        val permits = Semaphore(4)
        val http = TvHttpClient { url, headers ->
            permits.withPermit {
                check(!Injekt.get<BasePreferences>().downloadedOnly().get()) { "Offline mode" }
                require(TvRules.webUrl(url) && headers.size <= 32)
                require(
                    headers.all { (key, value) ->
                        key.length in 1..200 &&
                            value.length <= 8192 &&
                            (key + value).none(Char::isISOControl)
                    },
                )
                val request = Request.Builder().url(url).apply {
                    headers.forEach { (key, value) -> header(key, value) }
                }.build()
                client.newCall(request).await().use { response ->
                    require(TvRules.webUrl(response.request.url.toString()))
                    val body = response.body
                    val buffer = Buffer()
                    body?.source()?.let { source ->
                        while (!source.exhausted()) {
                            if (source.read(buffer, 8192) < 0) break
                            if (buffer.size > 12 * 1024 * 1024) throw IOException("TV response exceeds limit")
                        }
                    }
                    TvHttpResponse(
                        buffer.readString(body?.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8),
                        response.request.url.toString(),
                        response.headers.toMap(),
                        response.code,
                    )
                }
            }
        }
        val sources = RinLoading.guard { factory.create(http, cache) }
        require(sources.map { it.id }.toSet() == manifest.sources.map { it.id }.toSet())
        require(sources.map { it.id }.distinct().size == sources.size)
        return sources.map { TvRegistration(it, cache, manifest.packageName) }.also { registered ->
            RinLoading.guard { registry.validate(registered) }
        }
    }
}
