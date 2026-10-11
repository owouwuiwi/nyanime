package nyanime.telegram.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** Optional source-owned portability declaration; absence never means "copy every preference". */
@Serializable
data class SyncPreferenceContract(val version: Int = 1, val settings: List<SyncPreferenceRule>) {
    fun validate(sourceIds: Set<Long>) {
        require(version == 1 && settings.size <= 256)
        require(settings.map { it.source to it.key }.distinct().size == settings.size)
        settings.forEach {
            require(it.source != 0L && it.source in sourceIds)
            require(it.key.length in 1..256 && it.key.none(Char::isISOControl))
            require(it.type in setOf("boolean", "int", "long", "float", "string", "strings"))
            require(it.maxLength in 1..8192)
            require(it.minimum == null || it.minimum.isFinite())
            require(it.maximum == null || it.maximum.isFinite())
            require(it.minimum == null || it.maximum == null || it.minimum <= it.maximum)
        }
    }
    companion object {
        const val ASSET = "assets/nyanime/sync-v1.json"
    }
}

@Serializable
data class SyncPreferenceRule(
    val source: Long,
    val key: String,
    val type: String,
    val credential: Boolean = false,
    val maxLength: Int = 8192,
    val minimum: Double? = null,
    val maximum: Double? = null,
) {
    fun validate(value: JsonElement?) {
        if (value == null || value == JsonNull) return
        when (type) {
            "boolean" -> require(value is JsonPrimitive && value.booleanOrNull != null)
            "int" -> require(value is JsonPrimitive && value.intOrNull != null)
            "long" -> require(value is JsonPrimitive && value.longOrNull != null)
            "float" -> require(value is JsonPrimitive && value.doubleOrNull?.isFinite() == true)
            "string" -> require(value is JsonPrimitive && value.isString && value.content.length <= maxLength)
            "strings" -> require(
                value is JsonArray &&
                    value.size <= 256 &&
                    value.all {
                        it is JsonPrimitive && it.isString && it.content.length <= maxLength
                    },
            )
            else -> error("Unsupported portable preference")
        }
        if (type in setOf("int", "long", "float")) {
            val number = (value as JsonPrimitive).doubleOrNull ?: error("Invalid number")
            require(minimum == null || number >= minimum)
            require(maximum == null || number <= maximum)
        }
    }
}
