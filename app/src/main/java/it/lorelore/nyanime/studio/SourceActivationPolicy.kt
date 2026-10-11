package it.lorelore.nyanime.studio

internal data class TelegramActivationRequirement(
    val owner: SourceAccessOwner,
    val name: String,
    val channels: Set<String>,
)

/** Pure authorization decision, independent of Android, package loading and the login interface. */
internal object SourceActivationPolicy {
    fun approved(
        requirements: List<TelegramActivationRequirement>,
        account: Long?,
        accountRevision: String?,
        grants: List<TelegramSourceGrant>,
    ): Boolean {
        if (requirements.isEmpty()) return true
        if (account == null || accountRevision.isNullOrBlank()) return false
        return requirements.all { requirement ->
            val grant = grants.singleOrNull { it.owner == requirement.owner }
            grant != null &&
                grant.approved &&
                grant.account == account &&
                grant.accountRevision == accountRevision &&
                grant.channels.keys == requirement.channels
        }
    }
}
