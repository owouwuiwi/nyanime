package eu.kanade.tachiyomi.data.download.anime.ultra

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.DefaultGlObjectsProvider
import androidx.media3.effect.DefaultVideoFrameProcessor
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.EncoderSelector
import androidx.media3.transformer.EncoderUtil
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/** Local-only export. Never silently reduces the shader preset or the output resolution. */
@UnstableApi
internal object UltraExporter {
    data class Media(val width: Int, val height: Int, val durationMs: Long)

    suspend fun segmentPlan(context: Context, input: Uri, durationMs: Long): List<Long> = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, input, emptyMap())
            val track = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            extractor.selectTrack(track)
            val times = ArrayList<Long>()
            do {
                currentCoroutineContext().ensureActive()
                if (extractor.sampleTime >= 0) times += extractor.sampleTime
            } while (extractor.advance())
            UltraSegments.plan(times, durationMs * 1000)
        } finally {
            extractor.release()
        }
    }

    fun inspect(context: Context, uri: Uri): Media = MediaMetadataRetriever().use { reader ->
        reader.setDataSource(context, uri)
        fun number(key: Int) = reader.extractMetadata(key)?.toLongOrNull() ?: 0L
        val rotation = number(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
        val w = number(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH).toInt()
        val h = number(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT).toInt()
        val duration = number(MediaMetadataRetriever.METADATA_KEY_DURATION)
        require(w > 0 && h > 0 && duration > 0) { "Impossibile leggere il video originale" }
        if (rotation == 90L || rotation == 270L) Media(h, w, duration) else Media(w, h, duration)
    }

    suspend fun export(
        context: Context,
        input: Uri,
        output: File,
        startUs: Long,
        endUs: Long,
        control: UltraProcessingControl,
        progress: suspend (Int) -> Unit,
    ): Media {
        require(input.scheme in setOf("file", "content")) { "Ultra richiede un download locale" }
        val original = withContext(Dispatchers.IO) { inspect(context, input) }
        val (width, height) = UltraShaderGraph.target(original.width, original.height)
        control.begin()
        val thread = HandlerThread("Ultra-export", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        val dispatcher = Handler(thread.looper).asCoroutineDispatcher("Ultra-export")
        try {
            withContext(dispatcher) {
                val complete = CompletableDeferred<Unit>()
                val objects = DefaultGlObjectsProvider()
                val gl3 = object : GlObjectsProvider by objects {
                    override fun createEglContext(display: EGLDisplay, version: Int, attributes: IntArray): EGLContext =
                        objects.createEglContext(display, 3, attributes)
                }
                val frameProcessor = DefaultVideoFrameProcessor.Factory.Builder()
                    .setGlObjectsProvider(gl3)
                    .setSdrWorkingColorSpace(DefaultVideoFrameProcessor.WORKING_COLOR_SPACE_ORIGINAL)
                    .build()
                val encoder = DefaultEncoderFactory.Builder(context)
                    .setEnableFallback(false)
                    .setVideoEncoderSelector { mime ->
                        com.google.common.collect.ImmutableList.copyOf(
                            EncoderSelector.DEFAULT.selectEncoderInfos(mime).filter {
                                EncoderUtil.isHardwareAccelerated(it, mime)
                            },
                        )
                    }
                    .setRequestedVideoEncoderSettings(
                        VideoEncoderSettings.Builder()
                            .setBitrate((width.toLong() * height * 6).coerceIn(8_000_000, 60_000_000).toInt())
                            .setEncoderPerformanceParameters(12, 1)
                            .build(),
                    )
                    .build()
                val transformer = Transformer.Builder(context)
                    .setLooper(thread.looper)
                    .setMaxDelayBetweenMuxerSamplesMs(120_000)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setEncoderFactory(encoder)
                    .setVideoFrameProcessorFactory(frameProcessor)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (exportResult.videoFrameCount > 0) {
                                complete.complete(Unit)
                            } else {
                                complete.completeExceptionally(
                                    IllegalStateException("Ultra non ha prodotto fotogrammi"),
                                )
                            }
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException,
                        ) {
                            complete.completeExceptionally(exportException)
                        }
                    })
                    .build()
                val clip = MediaItem.Builder().setUri(input).setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionUs(startUs)
                        .setEndPositionUs(endUs)
                        .build(),
                ).build()
                val item = EditedMediaItem.Builder(clip)
                    .setRemoveAudio(true)
                    .setEffects(Effects(emptyList(), listOf(UltraGlEffect(control))))
                    .build()
                try {
                    transformer.start(item, output.absolutePath)
                    val holder = ProgressHolder()
                    while (!complete.isCompleted) {
                        val state = transformer.getProgress(holder)
                        progress(if (state == Transformer.PROGRESS_STATE_AVAILABLE) holder.progress else -1)
                        delay(500)
                    }
                    complete.await()
                } finally {
                    control.stop()
                    withContext(NonCancellable) { transformer.cancel() }
                }
            }
        } finally {
            thread.quitSafely()
        }
        val result = withContext(Dispatchers.IO) { inspect(context, Uri.fromFile(output)) }
        require(result.width == width && result.height == height) { "Risoluzione Ultra non rispettata" }
        require(
            kotlin.math.abs(result.durationMs - (endUs - startUs) / 1000) <= 100,
        ) { "Esportazione Ultra incompleta" }
        return result
    }
}
