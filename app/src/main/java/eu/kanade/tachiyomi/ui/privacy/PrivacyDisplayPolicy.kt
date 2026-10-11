package eu.kanade.tachiyomi.ui.privacy

/** User intent only: independent from windows, hardware and presentation. */
data class PrivacyDisplayPolicy(
    val enabled: Boolean,
    val selectedAreas: Set<PrivacyArea>,
    val temporaryOverrides: Map<PrivacyArea, Boolean> = emptyMap(),
    val onlyInIncognito: Boolean = false,
    val incognito: Boolean = false,
    val scopeIncognito: Map<PrivacyArea, Boolean> = emptyMap(),
) {
    fun canRequest(area: PrivacyArea, scope: PrivacyArea = area): Boolean =
        temporaryOverrides[scope] ?: (enabled && area in selectedAreas)

    fun permits(area: PrivacyArea, scope: PrivacyArea = area, incognitoContext: Boolean? = null): Boolean =
        temporaryOverrides[scope] ?: (
            enabled &&
                area in selectedAreas &&
                (!onlyInIncognito || (incognitoContext ?: scopeIncognito[scope] ?: incognito))
            )

    fun withTemporaryOverride(scope: PrivacyArea, enabled: Boolean?): PrivacyDisplayPolicy = copy(
        temporaryOverrides = when (enabled) {
            null -> temporaryOverrides - scope
            else -> temporaryOverrides + (scope to enabled)
        },
    )

    fun withIncognitoContext(scope: PrivacyArea, incognito: Boolean?): PrivacyDisplayPolicy = copy(
        scopeIncognito = when (incognito) {
            null -> scopeIncognito - scope
            else -> scopeIncognito + (scope to incognito)
        },
    )
}
