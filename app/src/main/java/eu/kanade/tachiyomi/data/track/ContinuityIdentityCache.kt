package eu.kanade.tachiyomi.data.track

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/** A bounded private cache of source-supplied IDs, never title guesses or temporary stream URLs. */
class ContinuityIdentityCache(
    preferenceStore: PreferenceStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val preference = preferenceStore.getString(Preference.appStateKey("continuity_identities_v1"), "[]")
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Entry(
        val medium: String,
        val source: Long,
        val url: String,
        val anilistId: Long?,
        val malId: Long?,
        val mangaUpdatesId: Long?,
        val verifiedAt: Long,
    ) {
        fun hints() = SourceTrackingHints(anilistId, malId, mangaUpdatesId)
    }

    private val entries = runCatching { json.decodeFromString<List<Entry>>(preference.get()) }
        .getOrDefault(emptyList()).takeLast(128).toMutableList()

    @Synchronized
    internal fun get(medium: String, source: Long, url: String, privateSession: Boolean): SourceTrackingHints? {
        if (privateSession) return null
        return entries.lastOrNull {
            it.medium == medium &&
                it.source == source &&
                it.url == url &&
                now() - it.verifiedAt in 0..6 * 60 * 60 * 1000L
        }?.hints()
    }

    @Synchronized
    internal fun matching(medium: String, id: Long, malId: Long?, privateSession: Boolean): List<Pair<Long, String>> {
        if (privateSession) return emptyList()
        return entries.filter {
            it.medium == medium &&
                now() - it.verifiedAt in 0..6 * 60 * 60 * 1000L &&
                AnimeMangaContinuity.hasSameIdentity(id, malId, it.hints())
        }.map { it.source to it.url }
    }

    @Synchronized
    internal fun remember(
        medium: String,
        source: Long,
        url: String,
        hints: SourceTrackingHints?,
        privateSession: Boolean,
    ) {
        if (privateSession ||
            url.length !in 1..2048 ||
            hints == null ||
            hints.anilistId == null &&
            hints.malId == null
        ) {
            return
        }
        entries.removeAll { it.medium == medium && it.source == source && it.url == url }
        entries += Entry(medium, source, url, hints.anilistId, hints.malId, hints.mangaUpdatesId, now())
        while (entries.size > 128) entries.removeAt(0)
        preference.set(json.encodeToString(entries.toList()))
    }
}
