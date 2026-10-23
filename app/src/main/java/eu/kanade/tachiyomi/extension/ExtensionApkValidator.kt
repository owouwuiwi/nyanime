package eu.kanade.tachiyomi.extension

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import com.android.apksig.ApkVerifier
import eu.kanade.domain.extension.AddonHostVersions
import eu.kanade.domain.extension.ExtensionPackageMetadata
import eu.kanade.domain.extension.ExtensionUpdatePolicy
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.addon.AddonRegistry
import java.io.File

/** Verification precedes every installer backend, including loading APKs privately. */
object ExtensionApkValidator {
    enum class Kind(val feature: String) {
        ANIME("tachiyomi.animeextension"),
        MANGA("tachiyomi.extension"),
        NEWS("nyanime.newsextension"),
        NOVEL("nyanime.novelextension"),
        TV("nyanime.tvextension"),
        GUIDE("nyanime.guideextension"),
        ADDON("nyanime.addon"),
    }
    data class Expected(
        val packageName: String,
        val versionCode: Long,
        val signer: String?,
        val distributionId: String?,
        val repository: String? = null,
        val installedVersion: Long? = null,
        val anime: Boolean = false,
        val libVersion: Double,
        val installed: ExtensionPackageMetadata? = null,
        val approvedManualRepository: String? = null,
        val kind: Kind = if (anime) Kind.ANIME else Kind.MANGA,
        val addonHostVersions: AddonHostVersions? = null,
        val equivalentApkHash: String? = null,
        val associatedOrigin: Boolean = false,
    )

    @Suppress("DEPRECATION")
    fun validate(context: Context, file: File, expected: Expected): Boolean = runCatching {
        val verification = ApkVerifier.Builder(file)
            .setMinCheckedPlatformVersion(Build.VERSION.SDK_INT)
            .setMaxCheckedPlatformVersion(Build.VERSION.SDK_INT)
            .build().verify()
        require(verification.isVerified)
        val flags =
            PackageManager.GET_CONFIGURATIONS or PackageManager.GET_SIGNATURES or PackageManager.GET_META_DATA or
                (if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else 0)
        val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags) ?: return false
        info.applicationInfo?.sourceDir = file.absolutePath
        info.applicationInfo?.publicSourceDir = file.absolutePath
        require(info.packageName == expected.packageName)
        require(PackageInfoCompat.getLongVersionCode(info) == expected.versionCode)
        val feature = expected.kind.feature
        require(info.reqFeatures?.any { it.name == feature } == true)
        val api = if (expected.kind == Kind.ADDON) {
            info.applicationInfo?.metaData?.getInt("nyanime.addon.api")?.takeIf { it > 0 }?.toDouble()
        } else if (expected.kind in setOf(Kind.TV, Kind.GUIDE, Kind.NOVEL)) {
            info.applicationInfo?.metaData?.getInt("nyanime.${expected.kind.name.lowercase()}.api")?.takeIf {
                it > 0
            }?.toDouble()
        } else if (expected.kind == Kind.NEWS) {
            info.applicationInfo?.metaData?.getInt("nyanime.news.api")?.takeIf { it > 0 }?.toDouble()
        } else if (expected.kind == Kind.ANIME) {
            info.applicationInfo?.metaData?.getInt("aniyomix.extensionLib")?.takeUnless { it == 0 }?.toDouble()
                ?: info.versionName?.substringBeforeLast('.')?.toDoubleOrNull()
        } else {
            info.versionName?.substringBeforeLast('.')?.toDoubleOrNull()
        }
        require(api == expected.libVersion)
        if (expected.kind == Kind.ADDON) {
            val bounds = AddonRegistry.hostVersions(info.applicationInfo?.metaData)
            require(bounds.supports(BuildConfig.VERSION_CODE.toLong()))
            require(expected.addonHostVersions == null || bounds == expected.addonHostVersions)
        }
        require(
            expected.installedVersion == null ||
                expected.versionCode > expected.installedVersion ||
                expected.versionCode == expected.installedVersion &&
                expected.equivalentApkHash != null &&
                ExtensionPackageArchive.sha256(file) == expected.equivalentApkHash,
        )
        val metadata = ExtensionPackageInspector.inspect(info) { it.sections.size }
        require(!metadata.invalidDistribution)
        require(expected.distributionId == metadata.distribution?.id)
        if (metadata.distribution?.updatePolicy == "repository") {
            require(metadata.distribution?.repository == expected.repository)
        }
        val signer = expected.signer?.lowercase()
        if (signer != null) {
            require(signer.matches(Regex("[a-f0-9]{64}")))
            require(signer in metadata.signerHistory || signer in metadata.signers)
        }
        expected.installed?.let { old ->
            require(old.signers.isNotEmpty())
            if (old.signers.size > 1 || metadata.signers.size > 1) {
                require(old.signers == metadata.signers)
            } else {
                require(metadata.signerHistory.containsAll(old.signers) || metadata.signers == old.signers)
            }
            require(old.distribution == null || old.distribution?.id == metadata.distribution?.id)
            if (old.distribution?.updatePolicy == "manual") {
                require(expected.repository == expected.approvedManualRepository)
                require(
                    expected.associatedOrigin ||
                        ExtensionUpdatePolicy.permitsManualTransition(old, metadata, expected.approvedManualRepository),
                )
            }
            require(!old.hasHomeDeclaration || metadata.hasHomeDeclaration)
        }
        true
    }.getOrDefault(false)
}
