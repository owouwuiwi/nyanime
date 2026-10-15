package eu.kanade.tachiyomi.data.rin

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory
import eu.kanade.tachiyomi.data.discovery.ExtensionHomeFilters
import eu.kanade.tachiyomi.data.discovery.MangaHomeFilters
import eu.kanade.tachiyomi.extension.ExtensionPackageInspector
import eu.kanade.tachiyomi.extension.anime.model.AnimeExtension
import eu.kanade.tachiyomi.extension.anime.model.AnimeLoadResult
import eu.kanade.tachiyomi.extension.manga.model.MangaExtension
import eu.kanade.tachiyomi.extension.manga.model.MangaLoadResult
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.MangaSource
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.util.system.ChildFirstPathClassLoader
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.zip.ZipFile

object RinLoader {
    fun path(
        context: Context,
        packageName: String,
    ): String? = RinStorage(context).run { record(packageName)?.let { file(it).absolutePath } }

    fun instantiate(context: Context, record: RinRecord): List<Any> = RinLoading.guard {
        val storage = RinStorage(context)
        require(eu.kanade.tachiyomi.extension.ExtensionPackageConsent.trusted(record)) { "Execution consent required" }
        val manifest = eu.kanade.tachiyomi.extension.ExtensionPackageArchive.verify(
            context,
            storage.file(record),
            record,
        )
        require(manifest == record.manifest && RinRepository.compatible(manifest, record.format))
        val loader = ChildFirstPathClassLoader(
            storage.file(record).absolutePath,
            null,
            context.classLoader,
            addon =
            manifest.medium in setOf("addon", "guide"),
        )
        if (manifest.medium == "news") {
            require(
                Class.forName(manifest.entryPoints.single(), false, loader).getDeclaredConstructor().newInstance()
                    is nyanime.news.api.NewsSourceFactory,
            )
            return@guard emptyList()
        }
        if (manifest.medium == "tv") {
            require(
                Class.forName(
                    manifest.entryPoints.single(),
                    false,
                    loader,
                ).getDeclaredConstructor().newInstance() is nyanime.tv.api.TvSourceFactory,
            )
            return@guard emptyList()
        }
        if (manifest.medium == "guide") {
            require(
                Class.forName(
                    manifest.entryPoints.single(),
                    false,
                    loader,
                ).getDeclaredConstructor().newInstance() is nyanime.addon.api.RinEpisodeGuideProvider,
            )
            return@guard emptyList()
        }
        if (manifest.medium == "addon") {
            require(
                Class.forName(
                    manifest.entryPoints.single(),
                    false,
                    loader,
                ).getDeclaredConstructor().newInstance() is nyanime.addon.api.RinAddon,
            )
            return@guard emptyList()
        }
        val baseSources = manifest.entryPoints.flatMap {
            when (val instance = Class.forName(it, false, loader).getDeclaredConstructor().newInstance()) {
                is AnimeSourceFactory -> instance.createSources()
                is SourceFactory -> instance.createSources()
                is AnimeSource -> listOf(instance)
                is MangaSource -> listOf(instance)
                else -> error("Invalid RIN entry point")
            }
        }
        val sources = eu.kanade.tachiyomi.extension.ExtensionOptionalCapabilities.attach(
            loader,
            manifest.optionalFactories,
            baseSources,
        )
        require(
            sources.all {
                if (manifest.medium == "anime") it is AnimeSource else it is MangaSource
            },
        )
        val ids = sources.map {
            when (it) {
                is AnimeSource -> it.id
                is MangaSource -> it.id
                else -> error("Invalid RIN source")
            }
        }
        require(
            ids.isNotEmpty() &&
                ids.size == ids.distinct().size &&
                (
                    record.format == "apk" &&
                        manifest.sources.isEmpty() ||
                        ids.toSet() == manifest.sources.map { it.id }.toSet()
                    ),
        )
        sources
    }

    fun anime(context: Context, record: RinRecord): AnimeLoadResult = loadOrDefault(AnimeLoadResult.Error) {
        val m = record.manifest
        require(!m.nsfw || Injekt.get<SourcePreferences>().showNsfwSource().get())
        require(m.medium == "anime" && m.extensionLib in listOf(14.0, 16.0, 17.0))
        val sources = if (record.enabled &&
            eu.kanade.tachiyomi.extension.ExtensionPackageConsent.trusted(record)
        ) {
            instantiate(context, record).map { it as AnimeSource }
        } else {
            emptyList()
        }
        val metadata = ExtensionPackageInspector.inspectContracts(
            path(context, m.packageName) ?: RinStorage(context).file(record).absolutePath,
        ) { home ->
            sources.singleOrNull { it.name == home.source.name && it.lang == home.source.lang }
                ?.let { ExtensionHomeFilters.sections(home, it.getFilterList()).size } ?: 0
        }.copy(signers = record.publisherSigners, signerHistory = record.publisherHistory)
        AnimeLoadResult.Success(
            AnimeExtension.Installed(
                m.name, m.packageName, m.versionName, m.versionCode, m.extensionLib,
                sources.map { it.lang }.distinct().singleOrNull() ?: "all", m.nsfw, m.torrent,
                null, sources, icon(context, record), isShared = record.isShared, metadata = metadata,
            ),
        )
    }

    fun manga(context: Context, record: RinRecord): MangaLoadResult = loadOrDefault(MangaLoadResult.Error) {
        val m = record.manifest
        require(!m.nsfw || Injekt.get<SourcePreferences>().showNsfwSource().get())
        require(m.medium == "manga" && m.extensionLib in listOf(1.4, 1.5, 1.6))
        val sources = if (record.enabled &&
            eu.kanade.tachiyomi.extension.ExtensionPackageConsent.trusted(record)
        ) {
            instantiate(context, record).map { it as MangaSource }
        } else {
            emptyList()
        }
        val metadata = ExtensionPackageInspector.inspectContracts(
            RinStorage(context).file(record).absolutePath,
        ) { home ->
            sources.filterIsInstance<CatalogueSource>().singleOrNull {
                it.name == home.source.name &&
                    it.lang == home.source.lang
            }
                ?.let { MangaHomeFilters.sections(home, it.getFilterList()).size } ?: 0
        }.copy(signers = record.publisherSigners, signerHistory = record.publisherHistory)
        MangaLoadResult.Success(
            MangaExtension.Installed(
                m.name, m.packageName, m.versionName, m.versionCode, m.extensionLib,
                sources.filterIsInstance<CatalogueSource>().map { it.lang }.distinct().singleOrNull() ?: "all",
                m.nsfw, null, sources, icon(context, record), isShared = record.isShared, metadata = metadata,
            ),
        )
    }

    private fun <T> loadOrDefault(fallback: T, block: () -> T): T = try {
        RinLoading.guard(block)
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: Exception) {
        logcat(LogPriority.ERROR, error) { "RIN implementation could not be loaded" }
        fallback
    }

    fun icon(context: Context, record: RinRecord): Drawable? = runCatching {
        if (record.format == "apk") {
            val info = eu.kanade.tachiyomi.extension.ExtensionPackageArchive.inspect(
                context,
                RinStorage(context).file(record),
            ).info
            return info.applicationInfo?.loadIcon(context.packageManager)
        }
        ZipFile(RinStorage(context).file(record)).use { zip ->
            val entry = zip.getEntry("assets/nyanime/rin-icon") ?: return null
            require(entry.size in 1..1_048_576)
            val bytes = zip.getInputStream(entry).use { RinRepository.boundedRead(it, 1_048_576) }
            val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, dimensions)
            require(dimensions.outWidth in 1..2048 && dimensions.outHeight in 1..2048)
            val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            BitmapDrawable(context.resources, bitmap)
        }
    }.getOrNull()
}
