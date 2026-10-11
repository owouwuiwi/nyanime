package eu.kanade.tachiyomi.data.discovery

import tachiyomi.domain.discovery.HomeSectionProvider

class LocalHomeSections(
    private val create: (Boolean, Set<Long>?) -> HomeSectionProvider<LocalHomeItem>,
) {
    val resume = create(true, null)
    val updates = create(false, null)
    fun resume(sourceId: Long) = create(true, setOf(sourceId))
    fun updates(sourceId: Long) = create(false, setOf(sourceId))
    fun resume(sourceIds: Set<Long>) = create(true, sourceIds)
    fun updates(sourceIds: Set<Long>) = create(false, sourceIds)
}
