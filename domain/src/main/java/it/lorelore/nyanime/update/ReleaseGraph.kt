package it.lorelore.nyanime.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.util.PriorityQueue

/** An immutable release closure. Hashes identify signed APK bytes, never just a version label. */
@Serializable
data class ReleaseGraph(
    val schema: Int,
    val applicationId: String,
    val versionName: String,
    val versionCode: Long,
    val targets: List<String>,
    val packages: List<Package>,
    val patches: List<Patch>,
) {
    @Serializable
    data class Package(
        val sha256: String,
        val size: Long,
        val versionName: String,
        val versionCode: Long,
        val abi: String,
        val url: String? = null,
    )

    @Serializable
    data class Patch(val from: String, val to: String, val sha256: String, val size: Long, val url: String)

    data class Route(val base: Package, val steps: List<Patch>, val downloadBytes: Long) {
        val targetHash get() = steps.lastOrNull()?.to ?: base.sha256
    }

    /** Bounded shortest path, including the price of downloading a complete checkpoint. */
    fun route(
        supportedAbis: List<String>,
        availableHashes: Set<String>,
        excludedPatches: Set<String> = emptySet(),
        excludedBases: Set<String> = emptySet(),
    ): Route? {
        val byHash = packages.associateBy { it.sha256 }
        val compatible = (supportedAbis + "universal").toSet()
        val ends = targets.filter { byHash.getValue(it).abi in compatible }.toSet()
        val outgoing = patches.filter { it.sha256 !in excludedPatches }.groupBy { it.from }
        val comparator = compareBy<Route> { it.downloadBytes }
            .thenBy { it.steps.size }
            .thenBy { compatibleOrder(byHash.getValue(it.targetHash).abi, supportedAbis) }
            .thenBy { it.base.sha256 }
        val queue = PriorityQueue(comparator)
        packages.filter { it.abi in compatible && it.sha256 !in excludedBases }.forEach {
            if (it.sha256 in availableHashes || it.url != null) {
                queue.add(Route(it, emptyList(), if (it.sha256 in availableHashes) 0 else it.size))
            }
        }
        // Distances include hop count: a cheaper long route must not hide a shorter feasible one.
        val distances = mutableMapOf<Pair<String, Int>, Long>()
        while (queue.isNotEmpty()) {
            val route = queue.remove()
            val key = route.targetHash to route.steps.size
            if (distances.getOrDefault(key, Long.MAX_VALUE) <= route.downloadBytes) continue
            distances[key] = route.downloadBytes
            if (route.targetHash in ends) return route
            if (route.steps.size == MAX_STEPS) continue
            outgoing[route.targetHash].orEmpty().forEach { edge ->
                queue.add(route.copy(steps = route.steps + edge, downloadBytes = route.downloadBytes + edge.size))
            }
        }
        return null
    }

    fun validate(repository: String, expectedPackage: String, expectedVersion: String): ReleaseGraph {
        require(schema == 2 && applicationId == expectedPackage && versionName == expectedVersion)
        require(VERSION.matches(versionName) && versionCode > 0)
        require(packages.size in 1..MAX_PACKAGES && patches.size <= MAX_PATCHES && targets.size in 1..5)
        require(packages.map { it.sha256 }.distinct().size == packages.size && targets.distinct() == targets)
        val byHash = packages.associateBy { it.sha256 }
        packages.forEach { item ->
            require(HASH.matches(item.sha256) && item.size in 1..MAX_APK_SIZE)
            require(VERSION.matches(item.versionName) && item.versionCode in 1..versionCode && item.abi in ABIS)
            require(compareVersions(item.versionName, versionName) <= 0)
            if (item.versionCode == versionCode) require(item.versionName == versionName)
            item.url?.let { url ->
                require(assetUrl(repository, item.versionName, apkName(item.versionName, item.abi)) == url)
            }
        }
        require(
            packages.groupBy { it.versionCode }.values.all { group ->
                group.map { it.versionName }.distinct().size ==
                    1
            },
        )
        require(
            packages.groupBy { it.versionName }.values.all { group ->
                group.map { it.versionCode }.distinct().size ==
                    1
            },
        )
        val selected = targets.map { byHash.getValue(it) }
        require(selected.all { it.versionName == versionName && it.versionCode == versionCode })
        require(selected.map { it.abi }.distinct().size == selected.size)
        require(patches.map { it.sha256 }.distinct().size == patches.size)
        require(patches.map { it.from to it.to }.distinct().size == patches.size)
        patches.forEach { edge ->
            val from = byHash.getValue(edge.from)
            val to = byHash.getValue(edge.to)
            require(from.abi == to.abi && from.versionCode < to.versionCode)
            require(compareVersions(from.versionName, to.versionName) < 0)
            require(HASH.matches(edge.sha256) && edge.size in 1..MAX_APK_SIZE)
            require(edge.url == assetUrl(repository, to.versionName, patchName(from.sha256, to.versionName, to.abi)))
        }
        // Every advertised target must be installable from a retained complete base.
        selected.forEach { target ->
            val isolated = copy(targets = listOf(target.sha256))
            require(isolated.route(listOf(target.abi), emptySet()) != null) { "Unreachable release target" }
        }
        return this
    }

    companion object {
        const val FILE_NAME = "Nyanime-Release-v2.json"
        const val MAX_BYTES = 512 * 1024
        const val MAX_APK_SIZE = 1_073_741_824L
        const val MAX_PACKAGES = 160
        const val MAX_PATCHES = 320
        const val MAX_STEPS = 8
        val ABIS = setOf("universal", "arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        private val VERSION = Regex("[0-9]{1,9}\\.[0-9]{1,9}\\.[0-9]{1,9}\\.[0-9]{1,9}")
        private val HASH = Regex("[a-f0-9]{64}")
        private val json = Json { explicitNulls = false }

        fun parse(text: String, repository: String, expectedPackage: String, expectedVersion: String): ReleaseGraph {
            require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
            // Bound nesting before asking the JSON decoder to allocate anything.
            var depth = 0
            var quoted = false
            var escaped = false
            text.forEach { char ->
                if (quoted) {
                    when {
                        escaped -> escaped = false
                        char == '\\' -> escaped = true
                        char == '"' -> quoted = false
                    }
                } else {
                    when (char) {
                        '"' -> quoted = true
                        '{', '[' -> {
                            depth++
                            require(depth <= 5)
                        }
                        '}', ']' -> depth--
                    }
                }
            }
            return json.decodeFromString<ReleaseGraph>(text).validate(repository, expectedPackage, expectedVersion)
        }

        fun descriptorVersion(url: String, repository: String): String? = runCatching {
            val uri = URI(url)
            require(uri.scheme == "https" && uri.host == "github.com" && uri.port == -1)
            require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
            val parts = uri.rawPath.removePrefix("/$repository/releases/download/v").split('/')
            require(parts.size == 2 && VERSION.matches(parts[0]) && parts[1] == FILE_NAME)
            require(url == assetUrl(repository, parts[0], FILE_NAME))
            parts[0]
        }.getOrNull()

        fun assetUrl(repository: String, version: String, name: String) =
            "https://github.com/$repository/releases/download/v$version/$name"

        fun apkName(version: String, abi: String) = "Nyanime-Production-$version-$abi.apk"
        fun patchName(
            hash: String,
            version: String,
            abi: String,
        ) = "Nyanime-Delta-${hash.take(16)}-$version-$abi.nyadelta"

        private fun compareVersions(left: String, right: String): Int =
            left.split('.').map(String::toLong).zip(right.split('.').map(String::toLong))
                .firstOrNull { it.first != it.second }?.let { it.first.compareTo(it.second) } ?: 0

        private fun compatibleOrder(abi: String, supported: List<String>) =
            supported.indexOf(abi).takeIf { it >= 0 } ?: supported.size
    }
}
