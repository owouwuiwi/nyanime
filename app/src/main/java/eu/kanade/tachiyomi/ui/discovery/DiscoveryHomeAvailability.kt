package eu.kanade.tachiyomi.ui.discovery

import tachiyomi.domain.discovery.SourceHomeAccess
import tachiyomi.domain.discovery.SourceHomeGroup
import tachiyomi.domain.discovery.SourceHomeGroupAccess
import tachiyomi.domain.discovery.SourceHomeListing

/** An optional extension may add a Home, but must never advertise itself before it is available. */
data class DiscoveryHomeAvailability(
    val loading: Boolean = true,
    val homes: List<SourceHomeGroup> = emptyList(),
) {
    fun selectedHome(
        requested: String?,
    ): String? = if (loading) {
        null
    } else {
        requested?.takeIf { id -> homes.any { it.id == id } }
            ?: homes.filter { it.primary }.minByOrNull { it.id }?.id
    }

    // Keep a restored selection pending until extension initialization has actually finished.
    fun reconcileSelection(requested: String?): String? = if (loading) {
        requested
    } else {
        requested?.takeIf { id -> homes.any { it.id == id } }
    }

    val unavailable: Boolean get() = !loading && homes.isEmpty()

    fun shouldLeaveSourcePage(current: Boolean): Boolean = unavailable && current

    companion object {
        // Earlier versions saved a Boolean here. Never cast that state to a String after an update.
        fun restoreSelection(value: Any?): String? = (value as? String)?.takeIf { it.isNotBlank() }
        fun from(access: SourceHomeGroupAccess) = DiscoveryHomeAvailability(access.loading, listOfNotNull(access.group))
        fun from(access: SourceHomeAccess) = DiscoveryHomeAvailability(
            loading = access.loading,
            homes = SourceHomeListing(access.loading, listOfNotNull(access.source)).groups.takeUnless {
                access.loading
            }.orEmpty(),
        )

        fun from(listing: SourceHomeListing) = DiscoveryHomeAvailability(listing.loading, listing.groups)
    }
}
