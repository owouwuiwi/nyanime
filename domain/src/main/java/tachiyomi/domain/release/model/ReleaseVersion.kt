package tachiyomi.domain.release.model

/** Public versions are four numeric components, compared from left to right. */
data class ReleaseVersion private constructor(private val components: List<Int>) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int {
        components.zip(other.components).forEach { (current, candidate) ->
            val comparison = current.compareTo(candidate)
            if (comparison != 0) return comparison
        }
        return 0
    }

    override fun toString(): String = components.joinToString(".")

    companion object {
        private val pattern = Regex("^v?(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)$")
        private val legacyInstalled = Regex("^(v?\\d+\\.\\d+\\.\\d+\\.\\d+)-\\d+$")

        fun parse(value: String?): ReleaseVersion? {
            val match = value?.let(pattern::matchEntire) ?: return null
            val numbers = match.groupValues.drop(1).map { it.toIntOrNull() ?: return null }
            return ReleaseVersion(numbers)
        }

        /** The old Android versionName had a commit suffix; it is never a new public release. */
        fun installed(value: String?): ReleaseVersion? = parse(value) ?: value
            ?.let(legacyInstalled::matchEntire)?.groupValues?.get(1)?.let(::parse)
    }
}
