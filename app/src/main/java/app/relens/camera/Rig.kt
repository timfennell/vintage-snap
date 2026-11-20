package app.relens.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import app.relens.optics.Lens
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.Executor
import kotlin.math.max
import kotlin.math.min

/** One of the phone's physical cameras, as Relens sees it. */
data class PhoneCamera(
    val id: String,
    /** Focal length on the 36 mm side (its long side spans the film's 36 mm). */
    val f36: Float,
    val focalMM: Float,
    val sensorLongMM: Float,
    val pixels: Size,           // its full output (JPEG / RAW), landscape
    val raw: Boolean,
    val minFocusD: Float,       // dioptres; 0 = fixed focus
    val sensorOrientation: Int,
    val activeArray: Rect,
)

/** How a lens is shot: which camera, how much of its frame (the crop factor), and what that leaves. */
data class Framing(val camera: PhoneCamera, val crop: Float) {
    /** The print's long side in pixels (3:2: the short side is 2/3 of it). */
    val longPx: Int get() = (camera.pixels.width / crop).toInt()
    val megapixels: Float get() = longPx * longPx * 2f / 3f / 1e6f
    /** The focal length over the frame's long side after the crop (for Metric3D, the keystone). */
    val focalOverLong: Float get() = camera.focalMM / camera.sensorLongMM * crop
}

/**
 * The phone's cameras behind the body, through Camera2 directly (CameraX sees
 * only the main camera; the Nothing's ultra-wide "2" and periscope "3" open by
 * id). Each lens is shot from the camera with the longest focal length that is
 * still no longer than the lens, and cropped to the lens's field of view on a
 * 3:2 frame, in software (the RAW keeps every pixel). JPEG + RAW (DNG via
 * DngCreator) together.
 */
class Rig(private val context: Context) {
    private val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("relens-camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }

    /** The back cameras that can be opened (listed or not), widest first. */
    val cameras: List<PhoneCamera> = (listOf("0", "2", "3") + cm.cameraIdList.toList()).distinct().mapNotNull { id ->
        runCatching {
            val c = cm.getCameraCharacteristics(id)
            if (c.get(CameraCharacteristics.LENS_FACING) != CameraMetadata.LENS_FACING_BACK) return@runCatching null
            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet() ?: emptySet()
            if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in caps) return@runCatching null
            val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)!!.first()
            val phys = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)!!
            val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            val jpeg = map.getOutputSizes(ImageFormat.JPEG).maxBy { it.width * it.height }
            val raw = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW in caps &&
                map.getOutputSizes(ImageFormat.RAW_SENSOR)?.isNotEmpty() == true
            val long = max(phys.width, phys.height)
            PhoneCamera(id, f * 36f / long, f, long, jpeg, raw,
                c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
                c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
                c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)!!)
        }.getOrNull()
    }.sortedBy { it.f36 }

    /** The widest focal length any camera reaches (narrower lenses can't be framed). */
    val widestMM: Float get() = cameras.firstOrNull()?.f36 ?: 24f

    /** How a lens would be framed, or null if no camera can (too wide, a fisheye, too long to leave ~1 MP). */
    fun framingFor(lens: Lens): Framing? {
        if (lens.isFisheye || cameras.isEmpty()) return null
        val cam = cameras.lastOrNull { it.f36 <= lens.focalMM * 1.005f } ?: return null
        val fr = Framing(cam, lens.focalMM / cam.f36)
        return fr.takeIf { it.megapixels >= 1f }
    }

    private val _focusD = MutableStateFlow<Float?>(null)
    /** The phone lens's focus distance now, dioptres (the focus ring follows it). */
    val liveFocusD: StateFlow<Float?> = _focusD
    private val _framing = MutableStateFlow<Framing?>(null)
    val framing: StateFlow<Framing?> = _framing
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var preview: Surface? = null
    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null
    private var characteristics: CameraCharacteristics? = null
    private var texture: SurfaceTexture? = null
    @Volatile private var lastFocusD: Float? = null
    @Volatile private var lastResult: TotalCaptureResult? = null
    /** Tap focus region (sensor coordinates) or manual focus (dioptres); else continuous AF. */
    private var afRegion: MeteringRectangle? = null
    private var manualD: Float? = null
    /** Which way up the phone is held (0, 90, 180, 270, clockwise from portrait). */
    @Volatile var deviceRotation = 0

    /** The preview size: 4:3 like the sensor, about screen size. */
    fun previewSize(cam: PhoneCamera): Size {
        val map = cm.getCameraCharacteristics(cam.id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        return map.getOutputSizes(SurfaceTexture::class.java)
            .filter { it.width * 3 == it.height * 4 && it.width <= 1920 }
            .maxByOrNull { it.width } ?: Size(1440, 1080)
    }

    /** Which open this is: callbacks from an earlier camera or session are ignored. */
    @Volatile private var generation = 0

    fun attach(t: SurfaceTexture) = handler.post { texture = t; _framing.value?.let { open(it) } }
    fun detach() = handler.post { close(); texture = null }

    /** Runs a camera call, turning failures (a camera taken away, closed under us) into a message, never a crash. */
    private inline fun safely(what: String, block: () -> Unit) {
        try { block() } catch (t: Throwable) {
            Log.w("Relens", "camera: $what failed: ${t.message}")
            if (t !is IllegalStateException) _error.value = "camera: ${t.message}"
        }
    }

    /** Shoot this lens: opens its camera if it's a different one. */
    fun select(lens: Lens) {
        val fr = framingFor(lens) ?: framingFor(lens.copy(focalMM = 50f)) ?: return
        handler.post {
            val same = _framing.value?.camera?.id == fr.camera.id && device != null
            _framing.value = fr
            if (!same) open(fr)
        }
    }

    @SuppressLint("MissingPermission")
    private fun open(fr: Framing) {
        val tex = texture ?: return
        close()
        _error.value = null
        val gen = ++generation
        val cam = fr.camera
        safely("open ${cam.id}") {
            characteristics = cm.getCameraCharacteristics(cam.id)
            val ps = previewSize(cam)
            tex.setDefaultBufferSize(ps.width, ps.height)
            preview = Surface(tex)
            jpegReader = ImageReader.newInstance(cam.pixels.width, cam.pixels.height, ImageFormat.JPEG, 2)
            if (cam.raw) {
                val rs = characteristics!!.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
                    .getOutputSizes(ImageFormat.RAW_SENSOR).maxBy { it.width * it.height }
                rawReader = ImageReader.newInstance(rs.width, rs.height, ImageFormat.RAW_SENSOR, 2)
            }
            cm.openCamera(cam.id, executor, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    if (gen != generation) { d.close(); return }          // superseded while opening
                    device = d; startSession(d, gen)
                }
                override fun onDisconnected(d: CameraDevice) {
                    Log.w("Relens", "camera ${cam.id} disconnected (current ${device == d})")
                    d.close()
                    if (device == d) { device = null; session = null; _error.value = "camera ${cam.id} was taken away; reopening"
                        handler.postDelayed({ _framing.value?.let { if (device == null) open(it) } }, 500) }
                }
                override fun onError(d: CameraDevice, e: Int) {
                    Log.e("Relens", "camera ${cam.id} error $e")
                    d.close()
                    if (device == d) { device = null; session = null; _error.value = "camera ${cam.id} error $e" }
                }
            })
        }
    }

    private fun startSession(d: CameraDevice, gen: Int): Unit = safely("session") {
        val outs = listOfNotNull(preview, jpegReader?.surface, rawReader?.surface).map { OutputConfiguration(it) }
        d.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outs, executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (gen != generation || device != d) { runCatching { s.close() }; return }
                    session = s; repeat()
                    Log.i("Relens", "camera ${_framing.value?.camera?.id} open, crop %.2f, RAW ${rawReader != null}".format(_framing.value?.crop ?: 0f))
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    if (gen != generation || device != d) return
                    Log.e("Relens", "session failed with RAW; retrying without")
                    if (rawReader != null) { rawReader?.close(); rawReader = null; startSession(d, gen) }
                    else _error.value = "camera session failed"
                }
            }))
    }

    private val resultCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
            lastResult = result
            result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { d ->
                lastFocusD = d
                val prev = _focusD.value
                if (prev == null || kotlin.math.abs(d - prev) > 0.02f * max(prev, 0.2f)) _focusD.value = d
            }
        }
    }

    private fun baseRequest(template: Int): CaptureRequest.Builder? {
        val d = device ?: return null
        return d.createCaptureRequest(template).apply {
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            val md = manualD
            val reg = afRegion
            when {
                md != null -> {
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    set(CaptureRequest.LENS_FOCUS_DISTANCE, md.coerceIn(0f, _framing.value?.camera?.minFocusD ?: 10f))
                }
                reg != null -> {
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(reg))
                    set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(reg))
                }
                else -> set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }
        }
    }

    private fun repeat(): Unit = safely("preview") {
        val s = session ?: return
        val p = preview ?: return
        val b = baseRequest(CameraDevice.TEMPLATE_PREVIEW) ?: return
        b.addTarget(p)
        s.setRepeatingRequest(b.build(), resultCallback, handler)
    }

    /**
     * Focus at a point of the finder ([fx], [fy]: fractions of the upright 3:2
     * crop), and stay there until the next tap (the print is developed for it).
     */
    fun focusAt(fx: Float, fy: Float) = handler.post {
        val fr = _framing.value ?: return@post
        val c = characteristics ?: return@post
        manualD = null
        // The crop's point on the upright full frame, then on the sensor (landscape, turned by its orientation).
        val (ux, uy) = cropToFull(fx, fy, fr)
        val (sx, sy) = uprightToSensor(ux, uy, fr.camera.sensorOrientation)
        val a = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)!!
        val half = (min(a.width(), a.height()) * 0.05f / fr.crop).toInt().coerceAtLeast(16)
        val cx = (a.left + sx * a.width()).toInt(); val cy = (a.top + sy * a.height()).toInt()
        afRegion = MeteringRectangle(Rect(max(a.left, cx - half), max(a.top, cy - half),
            min(a.right, cx + half), min(a.bottom, cy + half)), MeteringRectangle.METERING_WEIGHT_MAX - 1)
        safely("focus") {
            val s = session ?: return@post
            val p = preview ?: return@post
            val b = baseRequest(CameraDevice.TEMPLATE_PREVIEW) ?: return@post
            b.addTarget(p)
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            s.capture(b.build(), resultCallback, handler)
        }
        repeat()
    }

    /** The focus ring driving the phone lens (dioptres; 0 = infinity); null: back to autofocus. */
    fun manualFocus(dioptres: Float?) = handler.post {
        if (dioptres == manualD) return@post
        manualD = dioptres
        if (dioptres == null) afRegion = null
        repeat()
    }

    /**
     * Test: a RAW-only capture (no JPEG in the same request), to see whether this
     * camera fills a RAW stream at all. Logs the outcome; writes [file] as DNG.
     */
    suspend fun rawTest(file: File): String {
        val s = session ?: return "no session"
        val rr = rawReader ?: return "no RAW stream"
        val fr = _framing.value ?: return "no framing"
        val done = CompletableDeferred<String>()
        var img: Image? = null; var res: TotalCaptureResult? = null
        fun fin() {
            val i = img ?: return; val r = res ?: return
            runCatching { DngCreator(characteristics!!, r).use { dc -> file.outputStream().use { dc.writeImage(it, i) } } }
            i.close(); done.complete("RAW ${i.width}x${i.height} -> ${file.length()} bytes")
        }
        rr.setOnImageAvailableListener({ r -> img = r.acquireNextImage(); fin() }, handler)
        handler.post { try {
            val b = baseRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)!!
            b.addTarget(rr.surface)
            s.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(ss: CameraCaptureSession, r: CaptureRequest, t: TotalCaptureResult) { res = t; fin() }
                override fun onCaptureFailed(ss: CameraCaptureSession, r: CaptureRequest, f: android.hardware.camera2.CaptureFailure) {
                    done.complete("capture failed reason ${f.reason}")
                }
            }, handler)
        } catch (t: Throwable) { done.complete("error ${t.message}") } }
        val out = try { withTimeout(6000) { done.await() } } catch (t: Throwable) { "timed out: image ${img != null} result ${res != null}" }
        Log.i("Relens", "raw test on camera ${fr.camera.id}: $out")
        return out
    }

    /** Takes a frame: JPEG (and DNG when the camera gives RAW) into [dir]. */
    suspend fun shoot(dir: File, name: String): Exposure {
        val s = session ?: run { _framing.value?.let { fr -> handler.post { open(fr) } }; error("camera not ready (reopening)") }
        if (device == null) error("camera not open")
        val fr = _framing.value ?: error("no lens framing")
        dir.mkdirs()
        val jpeg = File(dir, "$name.jpg"); val dng = File(dir, "$name.dng")
        val focusD = lastFocusD
        val done = CompletableDeferred<Exposure>()
        val jr = jpegReader!!; val rr = rawReader
        var jpegImage: Image? = null; var rawImage: Image? = null; var result: TotalCaptureResult? = null
        var rawGiveUp = false
        fun finish() {
            val ji = jpegImage ?: return
            if (rr != null && !rawGiveUp && (rawImage == null || result == null)) {
                if (rawImage != null && result == null) return
                // Some cameras accept a RAW stream and never fill it: don't lose the frame over it.
                if (result != null) handler.postDelayed({
                    if (!done.isCompleted && rawImage == null) {
                        Log.w("Relens", "camera ${fr.camera.id}: no RAW image; keeping the JPEG")
                        rawGiveUp = true; finish()
                    }
                }, 1500)
                return
            }
            try {
                jpeg.outputStream().use { o -> val buf = ji.planes[0].buffer; val a = ByteArray(buf.remaining()); buf.get(a); o.write(a) }
                ji.close()
                rawImage?.takeIf { result != null }?.let { ri ->
                    DngCreator(characteristics!!, result!!).use { dc ->
                        dc.setOrientation(exifOrientation(fr.camera.sensorOrientation))
                        dng.outputStream().use { dc.writeImage(it, ri) }
                    }
                    ri.close()
                }
                done.complete(Exposure(jpeg, dng.takeIf { it.exists() && it.length() > 0 }, focusD))
            } catch (t: Throwable) { done.completeExceptionally(t) }
        }
        jr.setOnImageAvailableListener({ r -> jpegImage = r.acquireNextImage(); Log.i("Relens", "jpeg in"); finish() }, handler)
        rr?.setOnImageAvailableListener({ r -> rawImage = r.acquireNextImage(); Log.i("Relens", "raw in"); finish() }, handler)
        handler.post { try {
            // This phone's camera fills a RAW stream only in a request of its own (RAW and
            // JPEG together: the JPEG only). So a burst: the RAW frame, then the JPEG frame.
            val jb = baseRequest(CameraDevice.TEMPLATE_STILL_CAPTURE) ?: run { done.completeExceptionally(IllegalStateException("no camera")); return@post }
            jb.addTarget(jr.surface)
            jb.set(CaptureRequest.JPEG_ORIENTATION, (fr.camera.sensorOrientation + deviceRotation) % 360)
            jb.set(CaptureRequest.JPEG_QUALITY, 97.toByte())
            val jpegReq = jb.build()
            val reqs = ArrayList<CaptureRequest>()
            if (rr != null) reqs.add(baseRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)!!.apply { addTarget(rr.surface) }.build())
            reqs.add(jpegReq)
            s.captureBurst(reqs, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(ss: CameraCaptureSession, r: CaptureRequest, t: TotalCaptureResult) {
                    // The DNG needs the RAW frame's own result.
                    if (r != jpegReq || rr == null) { if (result == null || r != jpegReq) result = t }
                    if (rr == null) result = t
                    finish()
                }
                override fun onCaptureFailed(ss: CameraCaptureSession, r: CaptureRequest, f: android.hardware.camera2.CaptureFailure) {
                    done.completeExceptionally(IllegalStateException("capture failed (${f.reason})"))
                }
            }, handler)
        } catch (t: Throwable) { done.completeExceptionally(t) } }
        return try { withTimeout(10_000) { done.await() } } catch (t: kotlinx.coroutines.TimeoutCancellationException) {
            Log.e("Relens", "shot timed out: jpeg ${jpegImage != null} raw ${rawImage != null} result ${result != null} device ${device != null}")
            handler.post { _framing.value?.let { open(it) } }
            error("the camera didn't deliver the frame (jpeg ${jpegImage != null}, raw ${rawImage != null}, result ${result != null})")
        }
    }

    private fun exifOrientation(sensor: Int): Int = when ((sensor + deviceRotation) % 360) {
        90 -> androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90
        180 -> androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180
        270 -> androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270
        else -> androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
    }

    private fun close() {
        if (device != null) Log.i("Relens", "closing camera ${device?.id}", Throwable("who closed it"))
        generation++
        runCatching { session?.close() }; session = null
        runCatching { device?.close() }; device = null
        runCatching { jpegReader?.close() }; jpegReader = null
        runCatching { rawReader?.close() }; rawReader = null
        runCatching { preview?.release() }; preview = null
    }

    companion object {
        /** A point of the upright 3:2 crop to the upright 4:3 full frame (fractions), portrait or landscape. */
        fun cropToFull(fx: Float, fy: Float, fr: Framing, portrait: Boolean = true): Pair<Float, Float> {
            // Upright portrait full frame: 3 wide by 4 tall. The crop: 1/crop of the long (4) side,
            // 2/3 of that across (narrower than the 3 side's 1/crop: 2/3 x 4 = 2.67 < 3).
            val longFrac = 1f / fr.crop
            val shortFrac = (2f / 3f) * (4f / 3f) / fr.crop
            return if (portrait) Pair(0.5f + (fx - 0.5f) * shortFrac, 0.5f + (fy - 0.5f) * longFrac)
            else Pair(0.5f + (fx - 0.5f) * longFrac, 0.5f + (fy - 0.5f) * shortFrac)
        }

        /** Upright portrait (fractions) to the landscape sensor's, for a sensor turned [orientation] degrees. */
        fun uprightToSensor(ux: Float, uy: Float, orientation: Int): Pair<Float, Float> = when (orientation) {
            90 -> Pair(uy, 1f - ux)
            270 -> Pair(1f - uy, ux)
            180 -> Pair(1f - ux, 1f - uy)
            else -> Pair(ux, uy)
        }
    }
}
