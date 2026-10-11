package eu.kanade.tachiyomi.data.news

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import dalvik.system.PathClassLoader
import eu.kanade.domain.extension.ExtensionPackageMetadata
import eu.kanade.tachiyomi.extension.ExtensionPackageInspector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nyanime.news.api.NewsHttpClient
import nyanime.news.api.NewsSource
import nyanime.news.api.NewsSourceFactory

data class NewsExtension(
    val packageName: String,
    val label: String,
    val version: String,
    val versionCode: Long,
    val metadata: ExtensionPackageMetadata,
    val compatible: Boolean,
    val trusted: Boolean,
    val source: NewsSource? = null,
    val failed: Boolean = false,
)

/** Inspection never instantiates foreign code. Enablement requires a signer-bound local trust decision. */
class NewsExtensionRegistry(private val context: Context, private val http: NewsHttpClient) {
    private val mutex = Mutex()
    private val mutable = MutableStateFlow<List<NewsExtension>>(emptyList())
    val state = mutable.asStateFlow()

    @Suppress("DEPRECATION")
    suspend fun reload(settings: Map<String, NewsSourceSettings>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val flags = PackageManager.GET_CONFIGURATIONS or PackageManager.GET_META_DATA or
                PackageManager.GET_SIGNATURES or
                if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else 0
            val installed = context.packageManager.getInstalledPackages(flags)
                .filter { info -> info.reqFeatures?.any { it.name == FEATURE } == true }
            mutable.value = installed.map { info ->
                val metadata = ExtensionPackageInspector.inspect(info) { 0 }
                val apk = requireNotNull(info.applicationInfo)
                val api = apk.metaData?.getInt(API, 0)
                val factory = apk.metaData?.getString(FACTORY)
                val compatible = api == 1 && !factory.isNullOrBlank() && !metadata.invalidDistribution
                val config = settings[info.packageName]
                val trusted = config != null &&
                    config.signers.isNotEmpty() &&
                    metadata.signers.isNotEmpty() &&
                    (
                        config.signers == metadata.signers ||
                            config.signers.size == 1 &&
                            metadata.signers.size == 1 &&
                            metadata.signerHistory.containsAll(config.signers)
                        ) &&
                    config.distribution == metadata.distribution?.id
                val old = mutable.value.firstOrNull {
                    it.packageName == info.packageName &&
                        it.versionCode == PackageInfoCompat.getLongVersionCode(info) &&
                        it.metadata.signers == metadata.signers &&
                        it.metadata.distribution == metadata.distribution
                }
                val result = if (compatible && trusted && config?.enabled == true) {
                    runCatching { old?.source ?: instantiate(info, requireNotNull(factory)) }
                } else {
                    null
                }
                NewsExtension(
                    info.packageName,
                    context.packageManager.getApplicationLabel(apk).toString(),
                    info.versionName.orEmpty(),
                    PackageInfoCompat.getLongVersionCode(info),
                    metadata,
                    compatible,
                    trusted,
                    source = result?.getOrNull(),
                    failed = result?.isFailure == true,
                )
            }.sortedBy { it.label }
        }
    }

    private fun instantiate(info: PackageInfo, name: String): NewsSource {
        val apk = requireNotNull(info.applicationInfo)
        val className = if (name.startsWith('.')) info.packageName + name else name
        val loader = PathClassLoader(apk.sourceDir, context.classLoader)
        val factory = Class.forName(
            className,
            false,
            loader,
        ).getDeclaredConstructor().newInstance() as NewsSourceFactory
        return factory.create(http).also {
            require(it.name.isNotBlank() && it.name.length <= 128 && it.language.length in 2..16)
            require(it.capabilities.categories.size <= 100)
            require(
                it.capabilities.categories.map { category -> category.id }.distinct().size ==
                    it.capabilities.categories.size,
            )
        }
    }

    companion object {
        const val FEATURE = "nyanime.newsextension"
        const val API = "nyanime.news.api"
        const val FACTORY = "nyanime.news.factory"
    }
}
