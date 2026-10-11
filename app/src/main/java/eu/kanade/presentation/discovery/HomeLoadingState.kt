package eu.kanade.presentation.discovery

import tachiyomi.domain.discovery.SectionState

/** Cached and successful empty results remain content; an error must never look like endless loading. */
internal val SectionState<*>.awaitingContent: Boolean
    get() = data == null && error == null
