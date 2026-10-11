package eu.kanade.tachiyomi.data.download.anime.ultra

import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal object UltraMuxer {
    suspend fun remux(
        context: android.content.Context,
        segments: File,
        original: Uri,
        destination: File,
    ): Unit = suspendCancellableCoroutine { continuation ->
        val source = if (original.scheme == "content") {
            FFmpegKitConfig.getSafParameterForRead(context, original)
        } else {
            requireNotNull(original.path)
        }
        val session = FFmpegKit.executeWithArgumentsAsync(
            arrayOf(
                "-y", "-nostdin", "-f", "concat", "-safe", "1", "-i", segments.absolutePath, "-i", source,
                "-map", "0:v:0", "-map", "1:a?", "-map", "1:s?", "-map", "1:t?",
                "-map_metadata", "1", "-map_chapters", "1", "-c", "copy", destination.absolutePath,
            ),
            { result ->
                if (continuation.isActive) {
                    if (ReturnCode.isSuccess(result.returnCode)) {
                        continuation.resume(Unit)
                    } else {
                        continuation.resumeWithException(
                            IllegalStateException("Impossibile conservare tutte le tracce originali"),
                        )
                    }
                }
            },
        )
        continuation.invokeOnCancellation { FFmpegKit.cancel(session.sessionId) }
    }
}
