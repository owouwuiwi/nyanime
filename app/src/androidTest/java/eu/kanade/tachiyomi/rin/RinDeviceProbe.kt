package eu.kanade.tachiyomi.rin

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory
import eu.kanade.tachiyomi.data.rin.RinArchive
import eu.kanade.tachiyomi.data.rin.RinEntry
import eu.kanade.tachiyomi.data.rin.RinLoader
import eu.kanade.tachiyomi.data.rin.RinMigrationState
import eu.kanade.tachiyomi.data.rin.RinRepository
import eu.kanade.tachiyomi.data.rin.RinStorage
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.MangaSource
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.util.system.ChildFirstPathClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.zip.ZipFile

/** Opt-in real-device ABI verification; no catalogue or source identity is embedded in tests. */
class RinDeviceProbe : Instrumentation() {
    private var arguments = Bundle()
    override fun onCreate(arguments: Bundle?) {
        this.arguments = arguments ?: Bundle()
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val output = Bundle()
        try {
            runBlocking(Dispatchers.IO) {
                val bundles = context.assets.list("bundles").orEmpty().filter { it.endsWith(".rin") }
                if (bundles.isNotEmpty()) {
                    val signer = requireNotNull(arguments.getString("signer"))
                    val repository = requireNotNull(arguments.getString("repository"))
                    for (name in bundles) {
                        val file = File.createTempFile("rin-abi-", ".tmp", targetContext.cacheDir)
                        val original = File.createTempFile("rin-original-", ".apk", targetContext.cacheDir)
                        try {
                            context.assets.open("bundles/$name").use { input ->
                                file.outputStream().use { input.copyTo(it) }
                            }
                            context.assets.open("originals/${name.removeSuffix(".rin")}.apk").use { input ->
                                original.outputStream().use { input.copyTo(it) }
                            }
                            original.setReadOnly()
                            val hash = RinArchive.sha256(file.readBytes())
                            val record = RinStorage(targetContext).stage(file, signer, hash, repository)
                            val sources = RinLoader.instantiate(targetContext, record)
                            require(RinLoader.icon(targetContext, record) != null)
                            val loader =
                                ChildFirstPathClassLoader(original.absolutePath, null, targetContext.classLoader)
                            val previous = record.manifest.entryPoints.flatMap {
                                when (
                                    val instance = Class.forName(
                                        it,
                                        false,
                                        loader,
                                    ).getDeclaredConstructor().newInstance()
                                ) {
                                    is AnimeSourceFactory -> instance.createSources()
                                    is SourceFactory -> instance.createSources()
                                    is AnimeSource -> listOf(instance)
                                    is MangaSource -> listOf(instance)
                                    else -> error("Invalid baseline entry point")
                                }
                            }
                            fun signature(source: Any): String = when (source) {
                                is AnimeSource -> "${source.id}|${source.name}|${source.lang}"
                                is MangaSource -> "${source.id}|${source.name}|${source.lang}"
                                else -> error("Invalid source")
                            }
                            require(previous.map(::signature) == sources.map(::signature))
                            sources.zip(previous).forEach { (current, old) ->
                                val filters = when (current) {
                                    is AnimeSource -> current.getFilterList().map { it.name }
                                    is CatalogueSource -> current.getFilterList().map { it.name }
                                    else -> emptyList()
                                }
                                val originalFilters = when (old) {
                                    is AnimeSource -> old.getFilterList().map { it.name }
                                    is CatalogueSource -> old.getFilterList().map { it.name }
                                    else -> emptyList()
                                }
                                require(filters == originalFilters)
                            }
                            val homeAssets = record.manifest.hashes.keys.count { it.endsWith("/home-v1.json") }
                            require(homeAssets > 0)
                            if (arguments.getString("network") == "true") {
                                val count = withTimeout(60_000) {
                                    when (val source = sources.first()) {
                                        is AnimeSource -> {
                                            val titles = source.getPopularAnime(1).animes
                                            require(titles.isNotEmpty())
                                            val title = titles.first()
                                            require(
                                                source.getSearchAnime(
                                                    1,
                                                    title.title.take(40),
                                                    source.getFilterList(),
                                                ).animes.isNotEmpty(),
                                            )
                                            val update = source.getAnimeEpisodeUpdate(
                                                title,
                                                emptyList(),
                                                fetchDetails = true,
                                                fetchEpisodes = true,
                                            )
                                            require(update.anime.title.isNotBlank())
                                            require(update.episodes.isNotEmpty())
                                            update.episodes.size
                                        }
                                        is CatalogueSource -> {
                                            val titles = source.getPopularManga(1).mangas
                                            require(titles.isNotEmpty())
                                            val title = titles.first()
                                            require(
                                                source.getSearchManga(
                                                    1,
                                                    title.title.take(40),
                                                    source.getFilterList(),
                                                ).mangas.isNotEmpty(),
                                            )
                                            require(source.getMangaDetails(title).title.isNotBlank())
                                            val chapters = source.getChapterList(title)
                                            require(chapters.isNotEmpty())
                                            require(source.getPageList(chapters.first()).isNotEmpty())
                                            chapters.size
                                        }
                                        else -> error("Invalid catalogue")
                                    }
                                }
                                sendStatus(
                                    0,
                                    Bundle().apply {
                                        putString(
                                            "stream",
                                            "${record.manifest.packageName}: live catalogue, search and details passed; entries=$count\n",
                                        )
                                    },
                                )
                            }
                            val entry =
                                RinEntry(record.manifest, "https://example.invalid/probe.rin", hash, signer, repository)
                            require(RinRepository.get(targetContext).trusted(entry))
                            sendStatus(
                                0,
                                Bundle().apply {
                                    putString(
                                        "stream",
                                        "${record.manifest.packageName}: identical IDs and filters; Home assets=$homeAssets; icon retained\n",
                                    )
                                },
                            )
                        } finally {
                            file.delete()
                            original.delete()
                        }
                    }
                    output.putString(
                        "stream",
                        "${bundles.size} signed RIN bundles passed device ABI and original APK comparison",
                    )
                    return@runBlocking
                }
                val theme = arguments.getString("theme")
                val appearance = androidx.preference.PreferenceManager.getDefaultSharedPreferences(targetContext)
                val probeState = targetContext.getSharedPreferences("rin_probe", 0)
                if (theme != null) {
                    if (!probeState.contains("original_theme")) {
                        check(
                            probeState.edit().putString(
                                "original_theme",
                                appearance.getString("pref_theme_mode_key", "SYSTEM"),
                            ).commit(),
                        )
                    }
                    val selected = if (theme == "restore") probeState.getString("original_theme", "SYSTEM")!! else theme
                    require(selected in setOf("LIGHT", "DARK", "SYSTEM"))
                    check(appearance.edit().putString("pref_theme_mode_key", selected).commit())
                    if (theme == "restore") probeState.edit().clear().commit()
                }
                if (arguments.getString("resetMigration") == "true") {
                    check(targetContext.getSharedPreferences("rin_migration", 0).edit().remove("pending").commit())
                }
                val file = File.createTempFile("rin-device-", ".tmp", targetContext.cacheDir)
                try {
                    context.assets.open("device-probe.rin").use { input ->
                        file.outputStream().use { input.copyTo(it) }
                    }
                    val signer = requireNotNull(arguments.getString("signer"))
                    val repository = requireNotNull(arguments.getString("repository"))
                    val hash = RinArchive.sha256(file.readBytes())
                    val record = RinStorage(targetContext).stage(file, signer, hash, repository)
                    val sources = RinLoader.instantiate(targetContext, record)
                    val entry =
                        RinEntry(
                            record.manifest,
                            arguments.getString("url") ?: "https://example.invalid/device-probe.rin",
                            hash,
                            signer,
                            repository,
                        )
                    require(RinRepository.get(targetContext).trusted(entry))
                    if (arguments.getString("activate") == "true") {
                        RinRepository.get(targetContext).activate(record)
                    }
                    if (arguments.getString("migration") == "true") {
                        val state =
                            RinMigrationState(phase = arguments.getString("phase") ?: "intro", entries = listOf(entry))
                        check(
                            targetContext.getSharedPreferences("rin_migration", 0).edit()
                                .putString("pending", RinArchive.json.encodeToString(state)).commit(),
                        )
                    }
                    output.putString(
                        "stream",
                        "RIN authenticated and instantiated: ${sources.size} sources; exact IDs preserved",
                    )
                } finally {
                    file.delete()
                }
            }
            finish(Activity.RESULT_OK, output)
        } catch (error: Throwable) {
            output.putString("stream", android.util.Log.getStackTraceString(error))
            finish(Activity.RESULT_CANCELED, output)
        }
    }
}
