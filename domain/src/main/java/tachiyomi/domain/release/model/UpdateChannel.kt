package tachiyomi.domain.release.model

enum class UpdateChannel(val key: String) {
    RECOMMENDED("recommended"),
    INCLUDING_PREVIEWS("preview"),
    ;

    companion object {
        fun fromKey(value: String) = entries.firstOrNull { it.key == value } ?: RECOMMENDED
    }
}
