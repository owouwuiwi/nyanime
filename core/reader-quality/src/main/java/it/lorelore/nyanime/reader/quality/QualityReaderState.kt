package it.lorelore.nyanime.reader.quality

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Transient diagnostics and comparison only; contains no image, URL or reading position. */
object QualityReaderState {
    enum class Status { WORKING, READY, ORIGINAL_FALLBACK, MEMORY_FALLBACK, OFF }
    private val mutableStatus = MutableStateFlow<Map<Long, Status>>(emptyMap())
    val status = mutableStatus.asStateFlow()
    private val mutableOriginal = MutableStateFlow<Set<Long>>(emptySet())
    val original = mutableOriginal.asStateFlow()
    private val mutableRetries = MutableStateFlow<Map<Long, Long>>(emptyMap())
    val retries = mutableRetries.asStateFlow()

    fun retry(mangaId: Long) {
        mutableRetries.update { old ->
            LinkedHashMap(old).apply {
                val revision = remove(mangaId) ?: 0L
                put(mangaId, revision + 1)
                while (size > 32) remove(keys.first())
            }
        }
    }

    fun report(mangaId: Long?, value: Status) {
        mangaId ?: return
        mutableStatus.update { old ->
            val next = LinkedHashMap(old)
            next.remove(mangaId)
            next[mangaId] = value
            while (next.size > 32) next.remove(next.keys.first())
            next
        }
    }

    fun showOriginal(mangaId: Long, show: Boolean) {
        mutableOriginal.update { if (show) it + mangaId else it - mangaId }
    }
}
