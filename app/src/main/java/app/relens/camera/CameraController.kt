package app.relens.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.util.Log
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A frame on the roll: the camera's files and its own focus distance. */
data class Exposure(val jpeg: File, val dng: File?, val cameraFocusD: Float?)

/**
 * The phone camera behind the body: CameraX preview (4:3, like the sensor),
 * RAW + JPEG capture where the camera offers it (the Nothing main camera does:
 * 4096 x 3072 DNG), tap to focus, and the lens focus distance from Camera2.
 */
class CameraController(private val context: Context) {
    private val _surface = MutableStateFlow<SurfaceRequest?>(null)
    val surfaceRequest: StateFlow<SurfaceRequest?> = _surface
    private val _raw = MutableStateFlow(false)
    /** Whether shots are captured as DNG + JPEG. */
    val rawSupported: StateFlow<Boolean> = _raw

    private val _liveFocusD = MutableStateFlow<Float?>(null)
    /** The phone lens's focus distance now, dioptres (for the focus ring to follow). */
    val liveFocusD: StateFlow<Float?> = _liveFocusD

    private var camera: Camera? = null
    private var capture: ImageCapture? = null
    @Volatile private var lastFocusD: Float? = null

    private val fourThree = ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build()

    @OptIn(ExperimentalCamera2Interop::class)
    fun bind(owner: LifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            val previewBuilder = Preview.Builder().setResolutionSelector(fourThree)
            // Every preview frame's result: keep the lens focus distance (dioptres) for the next shot.
            Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                    result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { d ->
                        lastFocusD = d
                        val prev = _liveFocusD.value
                        if (prev == null || kotlin.math.abs(d - prev) > 0.02f * maxOf(prev, 0.2f)) _liveFocusD.value = d
                    }
                }
            })
            val preview = previewBuilder.build().also { p -> p.setSurfaceProvider { req -> _surface.value = req } }
            val selector = CameraSelector.DEFAULT_BACK_CAMERA
            provider.unbindAll()
            // Ask the camera what it can capture before building the capture use case.
            val info = provider.getCameraInfo(selector)
            val formats = ImageCapture.getImageCaptureCapabilities(info).supportedOutputFormats
            val raw = ImageCapture.OUTPUT_FORMAT_RAW_JPEG in formats
            _raw.value = raw
            val cap = ImageCapture.Builder()
                .setResolutionSelector(fourThree)
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setOutputFormat(if (raw) ImageCapture.OUTPUT_FORMAT_RAW_JPEG else ImageCapture.OUTPUT_FORMAT_JPEG)
                .build()
            capture = cap
            camera = provider.bindToLifecycle(owner, selector, preview, cap)
            Log.i("Relens", "camera bound, RAW+JPEG $raw, formats $formats")
        }, ContextCompat.getMainExecutor(context))
    }

    /** Focus on a point of the viewfinder surface (surface pixels, from the viewfinder's transformer). */
    fun focusAt(x: Float, y: Float) {
        val req = _surface.value ?: return
        val f = SurfaceOrientedMeteringPointFactory(req.resolution.width.toFloat(), req.resolution.height.toFloat())
        val action = FocusMeteringAction.Builder(f.createPoint(x, y))
            .setAutoCancelDuration(5, TimeUnit.SECONDS).build()
        camera?.cameraControl?.startFocusAndMetering(action)
    }

    @SuppressLint("RestrictedApi")
    suspend fun shoot(dir: File, name: String): Exposure {
        val cap = capture ?: error("camera not ready")
        dir.mkdirs()
        val jpeg = File(dir, "$name.jpg")
        val dng = File(dir, "$name.dng")
        val focusD = lastFocusD
        return withTimeout(20_000) { suspendCancellableCoroutine { cont ->
            val raw = _raw.value
            fun ready(f: File) = f.exists() && f.length() > 0
            val cb = object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Log.i("Relens", "saved ${output.savedUri} jpeg ${ready(jpeg)} dng ${ready(dng)}")
                    // RAW + JPEG may report once per file or once for both: done when the JPEG is
                    // there and (with RAW) the DNG too, or shortly after, with whatever arrived.
                    if (!cont.isActive) return
                    if (ready(jpeg) && (!raw || ready(dng))) cont.resume(Exposure(jpeg, dng.takeIf(::ready), focusD))
                    else if (ready(jpeg)) android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (cont.isActive) cont.resume(Exposure(jpeg, dng.takeIf(::ready), focusD))
                    }, 1500)
                }
                override fun onError(e: ImageCaptureException) {
                    Log.e("Relens", "capture failed", e)
                    if (cont.isActive) cont.resumeWithException(e)
                }
            }
            val exec = ContextCompat.getMainExecutor(context)
            Log.i("Relens", "shoot $name raw $raw")
            if (raw) cap.takePicture(ImageCapture.OutputFileOptions.Builder(dng).build(),
                ImageCapture.OutputFileOptions.Builder(jpeg).build(), exec, cb)
            else cap.takePicture(ImageCapture.OutputFileOptions.Builder(jpeg).build(), exec, cb)
        } }
    }
}
