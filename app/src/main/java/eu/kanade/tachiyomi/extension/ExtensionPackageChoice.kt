package eu.kanade.tachiyomi.extension

/** A format is never a publisher identity. Selection cannot authorize a signer replacement. */
object ExtensionPackageChoice {
    data class Candidate(
        val key: String,
        val version: Long,
        val signers: Set<String>,
        val history: Set<String> = signers,
        val compatible: Boolean = true,
    )

    fun samePublisher(current: Set<String>, candidate: Candidate): Boolean = current.isNotEmpty() &&
        (
            current == candidate.signers ||
                current.size == 1 &&
                candidate.signers.size == 1 &&
                candidate.history.containsAll(current)
            )

    fun select(candidates: List<Candidate>, activeKey: String?, pinnedSigners: Set<String>): Candidate? {
        val valid = candidates.filter { it.compatible && samePublisher(pinnedSigners, it) }
        val newest = valid.maxOfOrNull { it.version } ?: return null
        return valid.firstOrNull { it.version == newest && it.key == activeKey }
            ?: valid.firstOrNull { it.version == newest }
    }
}
