package tachiyomi.macrobenchmark

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.search.LexicalTitleMatcher
import tachiyomi.domain.search.SearchMedium
import tachiyomi.domain.search.SearchTitle
import tachiyomi.domain.search.SymSpellTitleIndex

/** Synthetic runtime corpus: no network, source names or real titles in the benchmark APK. */
@RunWith(AndroidJUnit4::class)
class TitleSearchBenchmark {
    @Test
    fun boundedLocalSearch() {
        val index = SymSpellTitleIndex()
        val matcher = LexicalTitleMatcher()
        System.gc()
        SystemClock.sleep(200)
        val before = Debug.getPss()
        val heapBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        val corpus = (0 until 5_000).map { number ->
            SearchTitle("synthetic:$number", "Synthetic Volume $number", SearchMedium.VIDEO)
        }
        index.replace(corpus)
        System.gc()
        SystemClock.sleep(200)
        val memoryKb = Debug.getPss() - before
        val heapDeltaKb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory() - heapBefore) / 1024
        val samples = (0 until 120).map { sample ->
            val number = sample * 37 % 5_000
            // Include broad, unnumbered queries, which cannot use the edition shortcut.
            val broad = sample % 4 == 0
            val query = if (broad) "Synthetc Volume" else "Synthetc Volume $number"
            val start = SystemClock.elapsedRealtimeNanos()
            val matches = matcher.rank(query, index.candidates(query, SearchMedium.VIDEO))
            assertTrue(if (broad) matches.isNotEmpty() else matches.any { it.item.key == "synthetic:$number" })
            (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
        }.drop(20).sorted()
        val p95 = samples[(samples.size * .95).toInt().coerceAtMost(samples.lastIndex)]
        Log.i("TitleSearchBenchmark", "corpus=5000 p95Ms=$p95 processPssDeltaKb=$memoryKb javaHeapDeltaKb=$heapDeltaKb")
        assertTrue("Local title search p95 was $p95 ms", p95 < 100)
    }
}
