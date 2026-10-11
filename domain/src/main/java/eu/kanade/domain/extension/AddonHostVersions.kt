package eu.kanade.domain.extension

/** Inclusive, publisher-declared host bounds. Names are display labels, never comparison keys. */
data class AddonHostVersions(
    val minimum: Long? = null,
    val maximum: Long? = null,
    val minimumName: String? = null,
    val maximumName: String? = null,
) {
    enum class Status { COMPATIBLE, TOO_OLD, TOO_NEW, INVALID }
    val valid get() = (minimum == null || minimum in 1L..2_100_000_000L) &&
        (maximum == null || maximum in 1L..2_100_000_000L) &&
        (minimum == null || maximum == null || minimum <= maximum) &&
        validName(minimumName, minimum) &&
        validName(maximumName, maximum)
    fun status(hostVersionCode: Long): Status = when {
        !valid || hostVersionCode <= 0 -> Status.INVALID
        minimum != null && hostVersionCode < minimum -> Status.TOO_OLD
        maximum != null && hostVersionCode > maximum -> Status.TOO_NEW
        else -> Status.COMPATIBLE
    }
    fun supports(hostVersionCode: Long) = status(hostVersionCode) == Status.COMPATIBLE
    private fun validName(name: String?, code: Long?) = name == null ||
        (code != null && name.isNotBlank() && name.length <= 64 && name.none { it.isISOControl() })
}
