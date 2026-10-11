package eu.kanade.tachiyomi.data.rin

internal object RinUpdateSchedule {
    enum class Plan { IDLE, APPLY_CACHED, REFRESH }

    private const val INTERVAL = 6 * 60 * 60 * 1000L

    fun plan(now: Long, checked: Long, knownUpdates: Boolean, force: Boolean, refreshed: Boolean?): Plan = when {
        refreshed != null -> Plan.APPLY_CACHED
        force || checked <= 0 || now < checked || now - checked >= INTERVAL -> Plan.REFRESH
        knownUpdates -> Plan.APPLY_CACHED
        else -> Plan.IDLE
    }
}
