package eu.kanade.tachiyomi.data.updater.delta

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class DeltaRecord(
    val baseSha256: String,
    val baseSize: Long,
    val targetSha256: String,
    val targetSize: Long,
    val patchSha256: String,
    val patchSize: Long,
    val patch: String,
    val target: String,
) {
    fun worthwhile() = patchSize > 0 && patchSize <= targetSize * 4 / 5 && targetSize - patchSize >= 1_048_576
}

internal data class DeltaManifest(val versionName: String, val versionCode: Long, val patches: List<DeltaRecord>) {
    fun select(target: String, baseSize: Long, baseHash: String): DeltaRecord? =
        patches.singleOrNull { it.target == target && it.baseSize == baseSize && it.baseSha256 == baseHash }

    companion object {
        const val FILE_NAME = "Nyanime-Delta-v1.json"
        const val MAX_BYTES = 256 * 1024L
        private val version = Regex("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+")
        private val hash = Regex("[a-f0-9]{64}")
        private val apk =
            Regex(
                "Nyanime-Production-([0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+)-(universal|arm64-v8a|armeabi-v7a|x86|x86_64)\\.apk",
            )

        fun parse(text: String, packageName: String, targetName: String): DeltaManifest {
            require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
            var depth = 0
            var quoted = false
            var escaped = false
            text.forEach { character ->
                if (quoted) {
                    if (escaped) {
                        escaped = false
                    } else {
                        when (character) {
                            '\\' -> escaped = true
                            '"' -> quoted = false
                        }
                    }
                } else {
                    when (character) {
                        '"' -> quoted = true
                        '{', '[' -> {
                            depth++
                            require(depth <= 8)
                        }
                        '}', ']' -> {
                            depth--
                            require(depth >= 0)
                        }
                    }
                }
            }
            require(depth == 0 && !quoted)
            val root = Json.parseToJsonElement(text).jsonObject
            require(root.keys == setOf("schema", "applicationId", "versionName", "versionCode", "patches"))
            require(root.number("schema") == 1L && root.text("applicationId") == packageName)
            val name = root.text("versionName")
            val code = root.number("versionCode")
            require(version.matches(name) && code in 1..2_100_000_000L)
            require(apk.matchEntire(targetName)?.groupValues?.get(1) == name)
            val records = root.getValue("patches") as JsonArray
            require(records.size in 1..15)
            val patches = records.map { element ->
                val record = element.jsonObject
                require(
                    record.keys == setOf(
                        "baseSha256",
                        "baseSize",
                        "targetSha256",
                        "targetSize",
                        "patchSha256",
                        "patchSize",
                        "patch",
                        "target",
                    ),
                )
                DeltaRecord(
                    record.text("baseSha256"),
                    record.number("baseSize"),
                    record.text("targetSha256"),
                    record.number("targetSize"),
                    record.text("patchSha256"),
                    record.number("patchSize"),
                    record.text("patch"),
                    record.text("target"),
                ).also {
                    require(listOf(it.baseSha256, it.targetSha256, it.patchSha256).all(hash::matches))
                    require(
                        listOf(it.baseSize, it.targetSize, it.patchSize).all { size ->
                            size in
                                1..DeltaPatch.MAX_APK_SIZE
                        },
                    )
                    val match = requireNotNull(apk.matchEntire(it.target))
                    require(match.groupValues[1] == name)
                    val expectedPatch = "Nyanime-Delta-${it.baseSha256.take(16)}-$name-${match.groupValues[2]}.nyadelta"
                    require(it.patch == expectedPatch && it.worthwhile())
                }
            }
            require(patches.map { it.patch }.distinct().size == patches.size)
            require(patches.map { it.baseSha256 to it.target }.distinct().size == patches.size)
            require(
                patches.groupBy { it.target }.values.all { group ->
                    group.map { it.targetSha256 to it.targetSize }.distinct().size ==
                        1
                },
            )
            return DeltaManifest(name, code, patches)
        }

        private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.let {
            require(it.isString)
            it.content
        }

        private fun JsonObject.number(key: String) = getValue(key).jsonPrimitive.let {
            require(!it.isString)
            it.content.toLong()
        }
    }
}
