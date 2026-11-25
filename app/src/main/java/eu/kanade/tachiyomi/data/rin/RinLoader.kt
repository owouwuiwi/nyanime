package eu.kanade.tachiyomi.data.rin

import android.content.Context
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
import it.lorelore.nyanime.source.novel.NovelSource
import it.lorelore.nyanime.source.novel.NovelSourceFactory
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import nyanime.extension.api.layer.AnimeLayerFactoryV1
import nyanime.extension.api.layer.MangaLayerFactoryV1
import nyanime.news.api.layer.NewsLayerFactoryV1
import nyanime.tv.api.layer.TvLayerFactoryV1
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.reflect.Modifier

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
        if (manifest.isLayer) {
            validateLayer(context, storage.file(record).absolutePath, manifest)
            return@guard emptyList()
        }
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
        if (manifest.medium == "novel") {
            val sources = manifest.entryPoints.flatMap { name ->
                when (val instance = Class.forName(name, false, loader).getDeclaredConstructor().newInstance()) {
                    is NovelSourceFactory -> instance.createNovelSources()
                    is NovelSource -> listOf(instance)
                    else -> error("Invalid novel source entry point")
                }
            }
            return@guard it.lorelore.nyanime.novel.source.NovelSourcePackages.verify(manifest, sources)
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

    private fun validateLayer(context: Context, path: String, manifest: RinManifest) {
        val targets = requireNotNull(manifest.mangaLayer).targets
        if (targets.any { "home" in it.capabilities }) {
            require(ExtensionPackageInspector.homes(path).isNotEmpty()) { "Layer Home is missing or invalid" }
        }
        val factories = targets.filter { it.capabilities.any { capability -> capability != "home" } }
            .map { it.medium }.distinct()
        val entryPoint = manifest.entryPoints.singleOrNull()
        if (entryPoint == null) {
            require(factories.isEmpty()) { "This layer requires a factory" }
            return
        }
        val loader = ChildFirstPathClassLoader(path, null, context.classLoader)
        val factory = Class.forName(entryPoint, false, loader)
        require(Modifier.isPublic(factory.modifiers) && !Modifier.isAbstract(factory.modifiers))
        factory.getConstructor()
        for (medium in factories) {
            val expected = when (medium) {
                "manga" -> MangaLayerFactoryV1::class.java
                "anime" -> AnimeLayerFactoryV1::class.java
                "news" -> NewsLayerFactoryV1::class.java
                "tv" -> TvLayerFactoryV1::class.java
                else -> error("Unsupported layer medium")
            }
            require(expected.isAssignableFrom(factory)) { "Incompatible layer factory" }
        }
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
                null, sources,
                icon(
                    context,
                    record,
                ),
                isShared = record.isShared, metadata = metadata, contentRevision = record.sha256,
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
                m.nsfw, null, sources,
                icon(
                    context,
                    record,
                ),
                isShared = record.isShared, metadata = metadata, contentRevision = record.sha256,
            ),
        )
    }

    private fun <T> loadOrDefault(fallback: T, block: () -> T): T = try {
        RinLoading.guard(block)
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: Exception) {
        logcat(LogPriority.ERROR, error) { "Extension implementation could not be loaded" }
        fallback
    }

    fun icon(context: Context, record: RinRecord): Drawable? = runCatching {
        require(record.format == "apk")
        val info = eu.kanade.tachiyomi.extension.ExtensionPackageArchive.inspect(
            context,
            RinStorage(context).file(record),
        ).info
        info.applicationInfo?.loadIcon(context.packageManager)
    }.getOrNull()
}
