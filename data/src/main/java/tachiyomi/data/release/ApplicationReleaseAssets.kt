package tachiyomi.data.release

import it.lorelore.nyanime.update.ReleaseGraph

/** Select application packages only: extension APKs, checksums and source archives are not app updates. */
object ApplicationReleaseAssets {
    fun selectUpdate(
        assets: List<GitHubAsset>,
        supportedAbis: List<String>,
        prefix: String,
        version: String,
        allowGraph: Boolean = true,
    ): String? {
        if (allowGraph && prefix == "Nyanime-Production") {
            assets.firstOrNull {
                it.name == ReleaseGraph.FILE_NAME &&
                    ReleaseGraph.descriptorVersion(it.downloadLink, "owouwuiwi/nyanime") == version.removePrefix("v")
            }?.let { return it.downloadLink }
        }
        return select(assets, supportedAbis, prefix)
    }

    private val abis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
    private val stableTag = Regex("[vr][0-9][A-Za-z0-9.+-]*")
    private val numericName =
        Regex("Nyanime-(\\d+\\.\\d+\\.\\d+\\.\\d+)-(universal|arm64-v8a|armeabi-v7a|x86_64|x86)\\.apk")

    fun select(assets: List<GitHubAsset>, supportedAbis: List<String>, prefix: String = "Nyanime"): String? {
        require(prefix.matches(Regex("[A-Za-z][A-Za-z0-9-]{0,63}")))
        val pattern = if (prefix == "Nyanime") {
            numericName
        } else {
            Regex(
                "${Regex.escape(
                    prefix,
                )}-(\\d+\\.\\d+\\.\\d+\\.\\d+)-(universal|arm64-v8a|armeabi-v7a|x86_64|x86)\\.apk",
            )
        }
        val packages = assets.mapNotNull { asset ->
            val name = asset.name
            val abi = when {
                pattern.matches(name) -> pattern.matchEntire(name)!!.groupValues[2]
                prefix != "Nyanime" -> return@mapNotNull null
                name == "app-universal-preview.apk" -> "universal"
                name.removePrefix("Nyanime-").removeSuffix(".apk").matches(stableTag) &&
                    name.startsWith("Nyanime-") &&
                    name.endsWith(".apk") -> "universal"
                else -> abis.firstOrNull { abi ->
                    name == "app-" + abi + "-preview.apk" ||
                        (
                            name.startsWith("Nyanime-" + abi + "-") &&
                                name.endsWith(".apk") &&
                                name.removePrefix("Nyanime-" + abi + "-").removeSuffix(".apk").matches(stableTag)
                            )
                } ?: return@mapNotNull null
            }
            abi to asset.downloadLink
        }.toMap()
        return supportedAbis.firstNotNullOfOrNull { packages[it] } ?: packages["universal"]
    }
}
