package eu.kanade.tachiyomi.data.download.anime.ultra

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale

/** Completed short clips are immutable checkpoints. Partial clips are never reused. */
internal class UltraSegments(private val directory: File, val boundariesUs: List<Long>) {
    @Serializable
    private data class Receipt(val bytes: Long, val startUs: Long, val endUs: Long)
    val count get() = boundariesUs.size - 1
    fun file(index: Int) = File(directory, "segment-$index.mp4")
    private fun receipt(index: Int) = File(directory, "segment-$index.json")

    init {
        require(boundariesUs.size >= 2 && boundariesUs.first() == 0L)
        require(boundariesUs.zipWithNext().all { (start, end) -> end > start })
    }

    fun completed(): Int = (0 until count).firstOrNull { index ->
        runCatching {
            val saved = Json.decodeFromString<Receipt>(receipt(index).readText())
            saved.bytes <= 0 ||
                saved.bytes != file(index).length() ||
                saved.startUs != boundariesUs[index] ||
                saved.endUs != boundariesUs[index + 1]
        }.getOrDefault(true)
    } ?: count

    fun commit(index: Int, temporary: File) {
        require(index == completed() && temporary.length() > 0)
        val saved = Receipt(temporary.length(), boundariesUs[index], boundariesUs[index + 1])
        FileOutputStream(temporary, true).use { it.fd.sync() }
        Files.move(
            temporary.toPath(),
            file(index).toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        val marker = File(directory, "receipt.new")
        FileOutputStream(marker).use {
            it.write(Json.encodeToString(saved).toByteArray())
            it.fd.sync()
        }
        Files.move(
            marker.toPath(),
            receipt(index).toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    fun concatFile(): File {
        check(completed() == count)
        // Fixed source-timeline durations prevent frame rounding errors accumulating at clip joins.
        return File(directory, "segments.ffconcat").apply {
            writeText(
                buildString {
                    appendLine("ffconcat version 1.0")
                    for (index in 0 until count) {
                        appendLine("file segment-$index.mp4")
                        appendLine(
                            "duration " +
                                String.format(
                                    Locale.ROOT,
                                    "%.6f",
                                    (boundariesUs[index + 1] - boundariesUs[index]) / 1_000_000.0,
                                ),
                        )
                    }
                },
            )
        }
    }

    companion object {
        /** Use actual presentation timestamps so a frame never straddles two clips. */
        fun plan(sampleTimesUs: List<Long>, durationUs: Long): List<Long> {
            require(durationUs > 0)
            val result = mutableListOf(0L)
            for (time in sampleTimesUs.sorted()) {
                if (time >= result.last() + 2_000_000 && time < durationUs) result += time
            }
            result += durationUs
            return result
        }
    }
}
