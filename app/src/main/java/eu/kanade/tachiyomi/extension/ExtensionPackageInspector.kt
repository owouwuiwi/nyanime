package eu.kanade.tachiyomi.extension

import android.content.pm.PackageInfo
import android.os.Build
import eu.kanade.domain.extension.ExtensionDistribution
import eu.kanade.domain.extension.ExtensionHomeSupport
import eu.kanade.domain.extension.ExtensionPackageMetadata
import eu.kanade.tachiyomi.data.discovery.ExtensionHomeManifest
import eu.kanade.tachiyomi.util.lang.Hash
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.util.zip.ZipFile

/** Read bounded declarations from the same shared/private APK selected by the loader. No network. */
object ExtensionPackageInspector {
    const val MAX_BYTES = 65_536

    fun readAsset(apk: String, name: String): String? = runCatching {
        ZipFile(apk).use { zip ->
            val entry = zip.getEntry(name) ?: return null
            require(entry.size <= MAX_BYTES)
            zip.getInputStream(entry).use { input ->
                val bytes = input.readBytesBounded()
                String(bytes, Charsets.UTF_8)
            }
        }
    }.getOrNull()

    private fun java.io.InputStream.readBytesBounded(): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            require(count > 0 && output.size() + count <= MAX_BYTES)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    fun homes(apk: String): List<ExtensionHomeManifest> =
        readAsset(apk, ExtensionHomeManifest.ASSET_PATH)?.let(ExtensionHomeManifest::parse).orEmpty()

    @Suppress("DEPRECATION")
    fun inspect(info: PackageInfo, supportedSections: (ExtensionHomeManifest) -> Int): ExtensionPackageMetadata {
        val apk = info.applicationInfo?.sourceDir ?: return ExtensionPackageMetadata()
        val current = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            info.signatures
        }
        val history = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.signingCertificateHistory ?: current
        } else {
            current
        }
        fun hashes(values: Array<android.content.pm.Signature>?) =
            values?.map { Hash.sha256(it.toByteArray()).lowercase() }?.toSet().orEmpty()
        return inspectContracts(apk, supportedSections).copy(signers = hashes(current), signerHistory = hashes(history))
    }

    fun inspectContracts(apk: String, supportedSections: (ExtensionHomeManifest) -> Int): ExtensionPackageMetadata {
        val distributionAsset = runCatching {
            ZipFile(apk).use { it.getEntry(ExtensionDistribution.ASSET_PATH) != null }
        }.getOrDefault(false)
        val rawDistribution = readAsset(apk, ExtensionDistribution.ASSET_PATH)
        val distribution = rawDistribution?.let(ExtensionDistribution::parse)
        val homeAsset = runCatching { ZipFile(apk).use { it.getEntry(ExtensionHomeManifest.ASSET_PATH) != null } }
            .getOrDefault(false)
        val rawHome = readAsset(apk, ExtensionHomeManifest.ASSET_PATH)
        val manifests = rawHome?.let(ExtensionHomeManifest::parse).orEmpty()
        val declared = runCatching {
            Json.parseToJsonElement(rawHome ?: "{}").jsonObject["homes"]?.jsonArray?.size ?: 0
        }.getOrDefault(0)
        val sections = manifests.map { runCatching { supportedSections(it) }.getOrDefault(0) }
        val supported = sections.count { it > 0 }
        val complete = manifests.indices.count { sections[it] == manifests[it].sections.size }
        val state = when {
            !homeAsset -> ExtensionHomeSupport.NONE
            manifests.isEmpty() || supported == 0 -> ExtensionHomeSupport.INCOMPATIBLE
            supported != declared || complete != declared -> ExtensionHomeSupport.PARTIAL
            else -> ExtensionHomeSupport.READY
        }
        return ExtensionPackageMetadata(
            distribution = distribution,
            home = state,
            declaredHomes = declared,
            supportedHomes = supported,
            invalidDistribution = distributionAsset && distribution == null,
        )
    }
}
