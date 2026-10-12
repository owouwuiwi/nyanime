package eu.kanade.domain.extension

/** User consent policy; package signatures and extension API compatibility are validated by the loaders. */
internal object ExtensionTrustPolicy {
    suspend fun isTrusted(
        fingerprints: List<String>,
        explicitlyTrusted: suspend () -> Boolean,
    ): Boolean {
        if (fingerprints.isEmpty() || fingerprints.any { it.isBlank() }) return false
        return explicitlyTrusted()
    }
}
