package eu.kanade.domain.extension.manga.interactor

import android.content.pm.PackageInfo
import androidx.core.content.pm.PackageInfoCompat
import eu.kanade.domain.extension.ExtensionTrustPolicy
import eu.kanade.domain.source.service.SourcePreferences
import tachiyomi.core.common.preference.getAndSet
import uy.kohesive.injekt.api.get

class TrustMangaExtension(
    private val preferences: SourcePreferences,
) {

    suspend fun isTrusted(pkgInfo: PackageInfo, fingerprints: List<String>): Boolean = ExtensionTrustPolicy.isTrusted(
        fingerprints = fingerprints,
    ) {
        val key = "${pkgInfo.packageName}:${PackageInfoCompat.getLongVersionCode(pkgInfo)}:${fingerprints.last()}"
        key in preferences.trustedExtensions().get() ||
            preferences.trustedExtensions().get().any { previous ->
                previous.startsWith(pkgInfo.packageName + ":") && previous.substringAfterLast(':') in fingerprints
            }
    }

    fun trust(pkgName: String, versionCode: Long, signatureHash: String) {
        eu.kanade.domain.extension.ExtensionExecutionTrust(uy.kohesive.injekt.Injekt.get()).approve(
            pkgName,
            eu.kanade.domain.extension.ExtensionPackageMetadata(signers = setOf(signatureHash.lowercase())),
        )
        preferences.trustedExtensions().getAndSet { exts ->
            // Remove previously trusted versions
            val removed = exts.filterNot { it.startsWith("$pkgName:") }.toMutableSet()

            removed.also { it += "$pkgName:$versionCode:$signatureHash" }
        }
    }

    fun revokeAll() {
        preferences.trustedExtensions().delete()
    }
}
