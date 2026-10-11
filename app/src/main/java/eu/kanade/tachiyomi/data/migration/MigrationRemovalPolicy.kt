package eu.kanade.tachiyomi.data.migration

internal object MigrationRemovalPolicy {
    fun allowed(complete: Boolean, transactionPending: Boolean, verifiedPreviousApp: Boolean): Boolean =
        complete && !transactionPending && verifiedPreviousApp

    fun shouldOffer(allowed: Boolean, handled: Boolean): Boolean = allowed && !handled
}
