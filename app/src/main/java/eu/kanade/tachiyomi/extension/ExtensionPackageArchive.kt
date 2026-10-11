package eu.kanade.tachiyomi.extension

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import com.android.apksig.ApkVerifier
import eu.kanade.tachiyomi.data.rin.RinArchive
import eu.kanade.tachiyomi.data.rin.RinManifest
import eu.kanade.tachiyomi.data.rin.RinRecord
import java.io.File
import java.util.zip.ZipFile

/** Inspect authenticated Android packages without loading extension code. Legacy RIN is read-only compatibility. */
object ExtensionPackageArchive {
    const val ASSET = "assets/nyanime/package-v1.json"
    const val MAX_BYTES = 512L * 1024 * 1024

    @Suppress("DEPRECATION")
    val FLAGS = PackageManager.GET_CONFIGURATIONS or PackageManager.GET_META_DATA or
        PackageManager.GET_SIGNATURES or PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or
        PackageManager.GET_PROVIDERS or PackageManager.GET_RECEIVERS or PackageManager.MATCH_DISABLED_COMPONENTS or
        (if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else 0)

    data class Inspected(val info: PackageInfo, val manifest: RinManifest, val requiresAndroid: Boolean)

    fun isApk(file: File): Boolean = ZipFile(file).use {
        it.getEntry("AndroidManifest.xml") != null &&
            it.getEntry("META-INF/rin.sig") == null
    }

    fun inspect(context: Context, file: File): Inspected {
        require(file.length() in 1..MAX_BYTES)
        val verification = ApkVerifier.Builder(file).setMinCheckedPlatformVersion(Build.VERSION.SDK_INT)
            .setMaxCheckedPlatformVersion(Build.VERSION.SDK_INT).build().verify()
        require(verification.isVerified) { "Invalid APK signature" }
        val info = requireNotNull(context.packageManager.getPackageArchiveInfo(file.absolutePath, FLAGS))
        val app = requireNotNull(info.applicationInfo)
        app.sourceDir = file.absolutePath
        app.publicSourceDir = file.absolutePath
        val metadata = ExtensionPackageInspector.inspect(info) { 0 }
        require(metadata.signers.isNotEmpty() && !metadata.invalidDistribution)
        val features = info.reqFeatures.orEmpty().map { it.name }.toSet()
        val medium = when {
            "tachiyomi.animeextension" in features -> "anime"
            "tachiyomi.extension" in features -> "manga"
            "nyanime.newsextension" in features -> "news"
            "nyanime.tvextension" in features -> "tv"
            "nyanime.guideextension" in features -> "guide"
            "nyanime.addon" in features -> "addon"
            else -> error("Unsupported extension family")
        }
        val apiKey = when (medium) {
            "anime" -> "aniyomix.extensionLib"
            "news" -> "nyanime.news.api"
            "tv" -> "nyanime.tv.api"
            "guide" -> "nyanime.guide.api"
            "addon" -> "nyanime.addon.api"
            else -> "nyanime.extension.api"
        }
        val api = app.metaData?.getInt(apiKey, 0)?.takeIf { it > 0 }?.toDouble()
            ?: info.versionName?.substringBeforeLast('.')?.toDoubleOrNull()
        val factoryKey = when (medium) {
            "anime" -> "tachiyomi.animeextension.class"
            "manga" -> "tachiyomi.extension.class"
            else -> "nyanime.$medium.factory"
        }
        val entryPoints = app.metaData?.getString(factoryKey)?.split(';')?.map { name ->
            name.trim().let { if (it.startsWith('.')) info.packageName + it else it }
        }.orEmpty()
        val declared = declaration(file)
        val manifest = declared ?: RinManifest(
            if (medium in setOf("anime", "manga")) 1 else 2,
            info.packageName,
            app.metaData?.getString("aniyomix.name")
                ?: context.packageManager.getApplicationLabel(
                    app,
                ).toString().substringAfter("Aniyomi: ").substringAfter("Tachiyomi: "),
            PackageInfoCompat.getLongVersionCode(info), info.versionName.orEmpty(), medium,
            requireNotNull(api), entryPoints, emptyList(),
            nsfw =
            app.metaData?.getInt(
                if (medium ==
                    "anime"
                ) {
                    "tachiyomi.animeextension.nsfw"
                } else {
                    "tachiyomi.extension.nsfw"
                },
            ) ==
                1 ||
                medium == "anime" &&
                (app.metaData?.getInt("aniyomix.contentWarning", 0) ?: 0) > 0,
            torrent = app.metaData?.getBoolean("aniyomix.torrent", false) == true ||
                app.metaData?.getInt("tachiyomi.animeextension.torrent", 0) == 1,
            hashes = emptyMap(),
            optionalFactories = app.metaData?.getString("nyanime.optional.factory")?.split(';')?.map {
                it.trim().let { name -> if (name.startsWith('.')) info.packageName + name else name }
            }.orEmpty(),
        )
        require(
            manifest.packageName == info.packageName &&
                manifest.medium == medium &&
                manifest.versionCode == PackageInfoCompat.getLongVersionCode(info) &&
                manifest.versionName == info.versionName &&
                manifest.extensionLib == api,
        )
        require(app.minSdkVersion <= Build.VERSION.SDK_INT)
        require(
            manifest.optionalFactories.size <= 8 &&
                manifest.optionalFactories.all {
                    it.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_$]*)+"))
                },
        )
        require(manifest.name.length in 1..200 && manifest.versionCode > 0 && manifest.hostVersions.valid)
        require(
            manifest.sources.isEmpty() &&
                medium in setOf("anime", "manga") ||
                manifest.validSourceIdentities() ||
                medium == "addon" &&
                entryPoints.isEmpty(),
        )
        require(manifest.entryPoints == entryPoints || medium == "addon" && entryPoints.isEmpty())
        require(
            manifest.entryPoints.size <= 32 &&
                manifest.entryPoints.all {
                    it.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_$]*)+"))
                },
        )
        val requiresAndroid = app.metaData?.getBoolean("nyanime.extension.requires_android", false) == true ||
            medium == "addon" &&
            entryPoints.isEmpty() ||
            info.activities.orEmpty().isNotEmpty() ||
            info.services.orEmpty().isNotEmpty() ||
            info.providers.orEmpty().isNotEmpty() ||
            info.receivers.orEmpty().isNotEmpty() ||
            app.className?.let { it != "android.app.Application" } == true
        require(requiresAndroid || manifest.entryPoints.isNotEmpty())
        return Inspected(info, manifest, requiresAndroid)
    }

    fun verify(context: Context, file: File, record: RinRecord): RinManifest {
        if (record.format != "apk") return RinArchive.verify(file, record.signer, record.sha256)
        require(sha256(file) == record.sha256)
        val inspected = inspect(context, file)
        val metadata = ExtensionPackageInspector.inspect(inspected.info) { 0 }
        require(metadata.signers == record.publisherSigners)
        val manifest = if (inspected.manifest.sources.isEmpty() &&
            record.manifest.medium in setOf("anime", "manga", "news")
        ) {
            inspected.manifest.copy(sources = record.manifest.sources)
        } else {
            inspected.manifest
        }
        require(manifest == record.manifest)
        return manifest
    }

    fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(bytes)
                if (count < 0) break
                digest.update(bytes, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    internal fun declaration(file: File): RinManifest? {
        val present = ZipFile(file).use { it.getEntry(ASSET) != null }
        if (!present) return null
        val raw = requireNotNull(ExtensionPackageInspector.readAsset(file.absolutePath, ASSET)) {
            "Invalid extension package declaration"
        }
        return RinArchive.json.decodeFromString<RinManifest>(raw)
    }
}
