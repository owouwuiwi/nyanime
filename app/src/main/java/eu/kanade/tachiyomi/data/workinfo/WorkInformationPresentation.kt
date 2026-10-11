package eu.kanade.tachiyomi.data.workinfo

/** Display-only composition: it never writes back to the source title or its progress. */
data class WorkDescription(val text: String?, val fromCatalogue: Boolean)

object WorkInformationPresentation {
    fun description(source: String?, catalogue: WorkInformation?): WorkDescription {
        if (!source.isNullOrBlank()) return WorkDescription(source, false)
        val text = catalogue?.synopsis?.takeIf { it.isNotBlank() }
        return WorkDescription(text, text != null)
    }

    fun details(
        catalogue: WorkInformation,
        identity: WorkIdentity,
        hasSourceStatus: Boolean,
    ): WorkInformation = catalogue.copy(
        work = catalogue.work.copy(
            year = identity.year ?: catalogue.work.year,
            format = identity.format ?: catalogue.work.format,
        ),
        // The source status is already visible in the existing title header.
        status = catalogue.status.takeUnless { hasSourceStatus },
        imdbId = when {
            identity.imdbId == null -> catalogue.imdbId
            catalogue.imdbId == null || catalogue.imdbId == identity.imdbId -> identity.imdbId
            else -> null // Contradictory IMDb identifiers must not produce a misleading outbound link.
        },
    )
}
