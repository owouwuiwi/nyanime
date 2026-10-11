package eu.kanade.tachiyomi.data.community

/** The personal channel shares crypto and library contracts, never the social identity or outbox. */
internal object PersonalSyncPolicy {
    const val VAULT = "personal-sync.identity"
    const val KEY_ALIAS = "nyanime.personal-sync.local.v1"
    const val DATABASE = "personal-sync-v1.db"
    const val PURPOSE = "personal-sync"

    fun capture(configured: Boolean, incognito: Boolean) = configured && !incognito

    fun address(value: String) = listOf(
        "nyanime.sync.v1:",
        "nyanime.sync.index.v1:",
        "nyanime.sync.checkpoint.v1:",
    ).any { value.startsWith(it) }

    fun event(event: NostrEvent, owner: String): Boolean = when (event.kind) {
        1059 -> event.tag("p") == owner
        30078 -> event.pubkey == owner && address(event.tag("d").orEmpty())
        else -> false
    }

    fun command(type: String) = type in setOf(
        "device.offer",
        "device.ready",
        "device.take",
        "device.ack",
        "device.presence",
    )

    /** First-device seeding must not bury resumable items behind completed history. */
    fun deliveryPriority(record: SyncRecord, now: Long): Int = if (
        !record.edits.containsAll(SyncField.entries) ||
        !record.seen &&
        record.history > 0 ||
        record.history >= now - 15_000
    ) {
        2
    } else {
        0
    }

    fun resume(records: List<SyncRecord>): List<SyncRecord> = records.asSequence()
        .filter { !it.deleted && !it.seen && it.history > 0 && it.ref.itemUrl.isNotEmpty() }
        .sortedByDescending { it.history }
        .distinctBy { Triple(it.ref.manga, it.ref.source, it.ref.titleUrl) }
        .take(12)
        .toList()
}
