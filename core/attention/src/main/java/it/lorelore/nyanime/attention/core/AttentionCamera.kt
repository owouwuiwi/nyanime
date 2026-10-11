package it.lorelore.nyanime.attention.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

/** One private CPU analyzer. No preview, recording, identity recognition or frame persistence. */
class AttentionCamera(
    context: Context,
    private val owner: LifecycleOwner,
    private val rotation: Int,
    private val sensitivity: Float,
    private val classifier: FaceClassifier,
    private val preview: Preview.SurfaceProvider? = null,
    private val onObservation: (AttentionObservation) -> Unit,
    private val onFailure: () -> Unit,
) : AutoCloseable {
    private val application = context.applicationContext
    private val main = ContextCompat.getMainExecutor(context)
    private val targetDisplayId: Int = if (android.os.Build.VERSION.SDK_INT >= 30) {
        (context as? android.app.Activity)?.display?.displayId
    } else {
        @Suppress("DEPRECATION")
        (context as? android.app.Activity)?.windowManager?.defaultDisplay?.displayId
    } ?: android.view.Display.DEFAULT_DISPLAY
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "attention-camera").apply { priority = Thread.MIN_PRIORITY }
    }
    private val closed = AtomicBoolean()
    private val failureReported = AtomicBoolean()
    private val displays = application.getSystemService(DisplayManager::class.java)
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (closed.get() || displayId != targetDisplayId) return
            val nextRotation = displays.getDisplay(displayId)?.rotation ?: return
            if (analysis?.targetRotation != nextRotation) {
                publish(SystemClock.elapsedRealtime(), AttentionSignal.Unknown)
                analysis?.targetRotation = nextRotation
                previewCase?.targetRotation = nextRotation
            }
        }
    }
    private val calibrationRequest = AtomicBoolean()
    private var model: FaceLandmarker? = null
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var previewCase: Preview? = null
    private var cameraState: androidx.lifecycle.LiveData<androidx.camera.core.CameraState>? = null
    private var cameraObserver: androidx.lifecycle.Observer<androidx.camera.core.CameraState>? = null
    private var previous = -1L
    private var intervalMs = 200L

    @Volatile private var calibrationDeadline = 0L
    val inferenceMs = AtomicLong()
    val samples = AtomicLong()
    val calibrationRemaining = AtomicLong()
    val minimumIntervalMs = AtomicLong(200)

    fun calibrate() {
        calibrationRemaining.set(15)
        calibrationDeadline = SystemClock.elapsedRealtime() + 15_000
        calibrationRequest.set(true)
    }

    fun start() {
        executor.execute {
            try {
                model = FaceLandmarker.createFromOptions(
                    application,
                    FaceLandmarker.FaceLandmarkerOptions.builder()
                        .setBaseOptions(
                            BaseOptions.builder().setDelegate(Delegate.CPU)
                                .setModelAssetPath("attention/face_landmarker.task").build(),
                        )
                        .setRunningMode(RunningMode.VIDEO)
                        .setNumFaces(2)
                        .setMinFaceDetectionConfidence(0.65f)
                        .setMinFacePresenceConfidence(0.65f)
                        .setMinTrackingConfidence(0.65f)
                        .setOutputFaceBlendshapes(true)
                        .build(),
                )
                if (!closed.get()) main.execute { bind() }
            } catch (error: Exception) {
                fail(error)
            } catch (error: LinkageError) {
                fail(error)
            } catch (error: OutOfMemoryError) {
                fail(error)
            }
        }
    }

    private fun bind() {
        if (closed.get()) return
        val future = ProcessCameraProvider.getInstance(application)
        future.addListener({
            if (closed.get()) return@addListener
            try {
                val cameraProvider = future.get()
                provider = cameraProvider
                if (!cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                    fail()
                    return@addListener
                }
                val useCase = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder().setResolutionStrategy(
                            ResolutionStrategy(
                                android.util.Size(640, 480),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER,
                            ),
                        ).build(),
                    )
                    .setTargetRotation(rotation)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis = useCase
                useCase.setAnalyzer(executor, ::analyze)
                val previewUseCase = preview?.let { surface ->
                    Preview.Builder().setTargetRotation(rotation).build().also { it.setSurfaceProvider(surface) }
                }
                previewCase = previewUseCase
                val useCases = if (previewUseCase == null) arrayOf(useCase) else arrayOf(useCase, previewUseCase)
                val camera = cameraProvider.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, *useCases)
                val observer = androidx.lifecycle.Observer<androidx.camera.core.CameraState> { state ->
                    if (state.error != null && !closed.get()) fail()
                }
                cameraState = camera.cameraInfo.cameraState
                cameraObserver = observer
                camera.cameraInfo.cameraState.observe(owner, observer)
                displays.registerDisplayListener(displayListener, android.os.Handler(android.os.Looper.getMainLooper()))
            } catch (error: Exception) {
                fail(error)
            }
        }, main)
    }

    private fun analyze(proxy: ImageProxy) {
        var bitmap: Bitmap? = null
        var rotated: Bitmap? = null
        try {
            val start = SystemClock.elapsedRealtime()
            if (closed.get() || start - previous < maxOf(intervalMs, minimumIntervalMs.get())) return
            previous = start
            if (calibrationDeadline > 0 && start > calibrationDeadline) {
                classifier.cancelCalibration()
                calibrationRequest.set(false)
                calibrationRemaining.set(0)
                calibrationDeadline = 0
            }
            bitmap = proxy.toBitmap()
            if (!usableLight(bitmap)) {
                publish(start, AttentionSignal.Unknown)
                return
            }
            val degrees = proxy.imageInfo.rotationDegrees
            rotated = if (degrees == 0) {
                bitmap
            } else {
                Bitmap.createBitmap(
                    bitmap,
                    0,
                    0,
                    bitmap.width,
                    bitmap.height,
                    Matrix().apply { postRotate(degrees.toFloat()) },
                    true,
                )
            }
            // Synchronous VIDEO mode keeps bitmap ownership explicit; no skipped-frame callbacks.
            val result = BitmapImageBuilder(rotated).build().use { image ->
                checkNotNull(model).detectForVideo(image, start)
            }
            val faces = result.faceLandmarks()
            val signal = when (faces.size) {
                0 -> AttentionSignal.Away
                1 -> {
                    val scale = maxOf(rotated.width, rotated.height).toFloat()
                    val points = faces.single().map {
                        FacePoint(
                            it.x() * rotated.width / scale,
                            it.y() * rotated.height / scale,
                        )
                    }
                    val shapes = result.faceBlendshapes().orElse(emptyList()).firstOrNull().orEmpty()
                        .associate { it.categoryName() to it.score() }
                    val face = FaceGeometry.measure(points, shapes["eyeBlinkLeft"] ?: 0f, shapes["eyeBlinkRight"] ?: 0f)
                    if (calibrationRequest.getAndSet(false)) classifier.beginCalibration()
                    if (face == null) {
                        AttentionSignal.Unknown
                    } else {
                        classifier.classify(face, sensitivity).also {
                            calibrationRemaining.set(
                                if (classifier.calibrating) (15 - classifier.calibrationSamples).toLong() else 0,
                            )
                        }
                    }
                }
                else -> AttentionSignal.Unknown
            }
            val cost = SystemClock.elapsedRealtime() - start
            inferenceMs.set(cost)
            samples.incrementAndGet()
            // Preserve video quality. Slow inference lowers sampling, never queues camera work.
            intervalMs = when {
                cost > 180 -> 800
                cost > 100 -> 400
                else -> 200
            }
            publish(start, if (cost > 800) AttentionSignal.Unknown else signal)
        } catch (error: Exception) {
            fail(error)
        } catch (error: LinkageError) {
            fail(error)
        } catch (error: OutOfMemoryError) {
            fail(error)
        } finally {
            if (rotated !== bitmap) rotated?.recycle()
            bitmap?.recycle()
            proxy.close()
        }
    }

    private fun usableLight(bitmap: Bitmap): Boolean {
        var sum = 0.0
        var squares = 0.0
        var count = 0
        for (y in bitmap.height / 8 until bitmap.height step maxOf(1, bitmap.height / 12)) {
            for (x in bitmap.width / 8 until bitmap.width step maxOf(1, bitmap.width / 12)) {
                val color = bitmap.getPixel(x, y)
                val value =
                    (
                        android.graphics.Color.red(color) *
                            3 +
                            android.graphics.Color.green(color) *
                            6 +
                            android.graphics.Color.blue(color)
                        ) /
                        10.0
                sum += value
                squares += value * value
                count++
            }
        }
        val mean = sum / count
        return mean in 22.0..245.0 && sqrt((squares / count - mean * mean).coerceAtLeast(0.0)) > 8
    }

    private fun publish(at: Long, signal: AttentionSignal) {
        if (!closed.get()) {
            if (signal != AttentionSignal.Watching) classifier.discardCalibrationEvidence()
            onObservation(AttentionObservation(at, signal))
        }
    }

    private fun fail(error: Throwable? = null) {
        if (closed.get() || !failureReported.compareAndSet(false, true)) return
        // One local diagnostic per camera instance; never log frames or face measurements.
        if (error != null) Log.w("ViewingAttention", "Local detector unavailable", error)
        main.execute { if (!closed.get()) onFailure() }
    }

    /** Must be called on the UI thread; queued model cleanup runs after the active inference. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        displays.unregisterDisplayListener(displayListener)
        analysis?.let {
            it.clearAnalyzer()
            provider?.unbind(it)
        }
        analysis = null
        previewCase?.let { provider?.unbind(it) }
        previewCase = null
        cameraObserver?.let { cameraState?.removeObserver(it) }
        executor.execute {
            runCatching { model?.close() }
            model = null
        }
        executor.shutdown()
    }
}
