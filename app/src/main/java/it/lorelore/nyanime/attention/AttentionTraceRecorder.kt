package it.lorelore.nyanime.attention

import android.util.Log
import it.lorelore.nyanime.attention.core.AttentionObservation
import it.lorelore.nyanime.attention.core.AttentionPlayback
import it.lorelore.nyanime.attention.core.AttentionTimeline
import it.lorelore.nyanime.attention.core.AttentionTraceChunk
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** Created only in ObserveOnly. Sampling is in memory; bounded writes run off the UI thread. */
internal class AttentionTraceRecorder(private val preferences: AttentionPreferences) {
    private val timeline = AttentionTimeline()
    private val job = SupervisorJob()
    private val reportedError = AtomicBoolean()
    private val scope = CoroutineScope(
        job +
            Dispatchers.IO +
            CoroutineExceptionHandler { _, error ->
                AttentionUi.traceStorageError.value = true
                if (reportedError.compareAndSet(
                        false,
                        true,
                    )
                ) {
                    Log.w("ViewingAttention", "Unable to save local attention intervals", error)
                }
            },
    )
    private var generation = preferences.traceGeneration()
    private var closed = false

    fun tick(now: Long, observation: AttentionObservation?, frame: AttentionPlayback?) {
        if (closed) return
        if (preferences.traceGeneration() != generation) {
            // A deliberate deletion also discards queued and in-memory pre-deletion observations.
            timeline.stop()
            generation = preferences.traceGeneration()
        }
        timeline.tick(now, observation, frame)?.let(::submit)
    }

    private fun submit(chunk: AttentionTraceChunk) {
        if (chunk.incognito) return
        val expectedGeneration = generation
        scope.launch {
            if (preferences.mergeTrace(chunk, expectedGeneration)) {
                AttentionUi.traceStorageError.value = false
                reportedError.set(false)
                AttentionUi.traceChanges.update { it + 1 }
            }
        }
    }

    fun stop() {
        if (!closed && preferences.traceGeneration() == generation) timeline.stop()?.let(::submit)
    }

    fun close() {
        if (closed) return
        closed = true
        if (preferences.traceGeneration() == generation) timeline.stop()?.let(::submit)
        // Complete existing one-shot writes, with no retained loop or scope after deactivation.
        job.complete()
    }
}
