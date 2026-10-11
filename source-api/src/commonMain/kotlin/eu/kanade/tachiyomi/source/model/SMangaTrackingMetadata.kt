package eu.kanade.tachiyomi.source.model

/** Optional, transient metadata provided by an extension when loading manga details. */
interface SMangaTrackingMetadata {
    var trackingMetadata: String?
}
