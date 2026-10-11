package eu.kanade.tachiyomi.data.community

/** A message names the exact owner-signed roster, not just a locally incremented number. */
internal object GroupPolicy {
    fun newer(previous: PrivateGroup?, next: PrivateGroup): Boolean = previous == null ||
        previous.owner == next.owner &&
        compareValuesBy(next, previous, { it.revision }, { it.fingerprint() }) > 0

    fun accepts(roster: PrivateGroup, message: NostrEvent, self: String): Boolean {
        val tag = message.tags.firstOrNull { it.firstOrNull() == "nyanime-group" } ?: return false
        return roster.valid() &&
            !roster.closed &&
            self in roster.members &&
            message.pubkey in roster.members &&
            tag.getOrNull(1) == roster.id &&
            tag.getOrNull(2) == roster.revision.toString() &&
            tag.getOrNull(3) == roster.fingerprint() &&
            (message.values("p") + message.pubkey).toSet() == roster.members.toSet()
    }
}
