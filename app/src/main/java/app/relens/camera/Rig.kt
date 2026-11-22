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
    /** LENS_INFO_FOCUS_DISTANCE_CALIBRATION: 0 uncalibrated (not dioptres), 1 approximate, 2 calibrated. */
    val focusCalibration: Int = 0,
)

/** One autofocus lock after a tap: where (fractions of the lens's crop), the corrected distance (dioptres), when. */
data class AfSample(val fx: Float, val fy: Float, val dioptres: Float, val cameraId: String, val timeMs: Long)

/** How a lens is shot: which camera, how much of its frame (the crop factor), and what that leaves. */
data class Framing(val camera: PhoneCamera, val crop: Float) {
    /** The print's long side in pixels (3:2: the short side is 2/3 of it). */
    val longPx: Int get() = (camera.pixels.width / crop).toInt()
    val megapixels: Float get() = longPx * longPx * 2f / 3f / 1e6f
    /** The focal length over the frame's long side after the crop (for Metric3D, the keystone). */
    val focalOverLong: Float get() = camera.focalMM / camera.sensorLongMM * crop
    /** The camera's role by its reach: ultra-wide, main or tele (for the bag). */
    val cameraRole: String get() = when { camera.f36 < 20f -> "ultra-wide"; camera.f36 > 50f -> "tele"; else -> "main" }
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
    val cameras: List<PhoneCamera> = (cm.cameraIdList.toList() +
            // the physical cameras inside a listed multi-camera (some phones let them be opened by id)
            cm.cameraIdList.flatMap { id -> runCatching { cm.getCameraCharacteristics(id).physicalCameraIds.toList() }.getOrDefault(emptyList()) } +
            // and cameras the phone doesn't list at all but will open by id (the Nothing's ultra-wide
            // "2" and periscope "3"); any that refuse to open are set aside (giveUp)
            (0..9).map { it.toString() }).distinct().mapNotNull { id ->
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
                c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)!!,
                c.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION) ?: 0)
        }.getOrNull()
    }.distinctBy { (it.f36 * 4).toInt() }        // the same lens listed twice: keep the first (listed) one
        .sortedBy { it.f36 }

    /**
     * Cameras found but not listed by the phone (opened by id, as the Nothing's ultra-wide and
     * periscope are) may refuse to open on another phone: those that do are set aside, and the
     * lens is framed again on what's left.
     */
    private val blocked = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val usable: List<PhoneCamera> get() = cameras.filter { it.id !in blocked }
    private var lastLens: Lens? = null
    private fun giveUp(cam: PhoneCamera, why: String) {
        if (cam.id in cm.cameraIdList || !blocked.add(cam.id)) return
        app.relens.Trace.w("camera ${cam.id} (unlisted) won't open here ($why): set aside")
        lastLens?.let { l -> handler.post { select(l) } }
    }

    /** For the log: each camera, and what each lens in [lenses] gets (camera, crop, megapixels) or why it's hidden. */
    fun lensReport(lenses: List<Lens>): String = buildString {
        for (c in usable) append("camera ${c.id}: %.1f mm (35 mm equiv.), %dx%d, RAW ${c.raw}, closest focus %s\n".format(c.f36, c.pixels.width, c.pixels.height,
            if (c.minFocusD > 0f) "%.1f cm".format(100f / c.minFocusD) else "fixed"))
        for (l in lenses) {
            val fr = framingFor(l)
            val why = when {
                fr != null -> "camera ${fr.camera.id}, crop %.2f, %.1f MP".format(fr.crop, fr.megapixels) +
                    if (fr.camera.f36 > l.focalMM * 1.005f) " (at the camera's %.0f mm view)".format(fr.camera.f36) else ""
                l.isFisheye -> "hidden: a fisheye"
                usable.none { it.f36 <= l.focalMM * 1.35f } -> "hidden: wider than any camera"
                else -> "hidden: under 1 MP"
            }
            append("${l.name}: $why\n")
        }
    }

    /** The widest focal length any camera reaches (narrower lenses can't be framed). */
    val widestMM: Float get() = usable.firstOrNull()?.f36 ?: 24f

    /** How a lens would be framed, or null if no camera can (too wide, a fisheye, too long to leave ~1 MP). */
    fun framingFor(lens: Lens): Framing? {
        if (lens.isFisheye || usable.isEmpty()) return null
        val cam = usable.lastOrNull { it.f36 <= lens.focalMM * 1.005f }
            // A lens a little wider than any camera (the 13 mm on a 16 mm ultra-wide) is shown at the
            // widest view there is, uncropped, keeping its own rendering (Framing.wider says so).
            ?: return usable.first().takeIf { it.f36 <= lens.focalMM * 1.35f }?.let { Framing(it, 1f) }
        val fr = Framing(cam, lens.focalMM / cam.f36)
        return fr.takeIf { it.megapixels >= 1f }
    }

    private val _focusD = MutableStateFlow<Float?>(null)
    /** The phone lens's focus distance now, dioptres (the focus ring follows it). */
    val liveFocusD: StateFlow<Float?> = _focusD
    private val _sceneEV = MutableStateFlow<Float?>(null)
    /**
     * The scene's brightness as the phone's own auto-exposure metered it, EV at ISO 100:
     * log2(N^2 / t) - log2(ISO / 100), smoothed (a reflected-light meter, for the body's meter).
     */
    val sceneEV: StateFlow<Float?> = _sceneEV
    private val _framing = MutableStateFlow<Framing?>(null)
    val framing: StateFlow<Framing?> = _framing
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    /** Open the logical camera "4" (hero streams + an ultra-wide light-map stream in one request), when it works. */
    var useLogical = false   // on after it is tested (--ez logical true turns it on for a test)
    private val logicalId: String? = runCatching {
        cm.getCameraCharacteristics("4").physicalCameraIds.takeIf { it.containsAll(listOf("0", "2", "3")) }?.let { "4" }
    }.getOrNull()
    private var logicalOpen = false
    private var uwReader: ImageReader? = null
    private var heroId: String? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var preview: Surface? = null
    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null
    private var characteristics: CameraCharacteristics? = null
    private var texture: SurfaceTexture? = null
    @Volatile private var lastFocusD: Float? = null
    /** The scene EV at the latest frame (unsmoothed): the hero frame's brightness at the shutter. */
    @Volatile var lastEV: Float? = null; private set
    @Volatile private var lastUwResult: CaptureResult? = null
    @Volatile private var afWatch = false
    @Volatile private var manualWatch = false
    /** A scan has been seen since the last trigger: only a lock after one is a new measurement. */
    @Volatile private var sawScan = false
    private var lastAfState = -1
    /** The lens's raw focus reading and autofocus state in the latest preview frame (the live calibration). */
    @Volatile var liveRaw: Float? = null; private set
    @Volatile var liveAfState: Int = -1; private set
    /** When the lens last came to rest (autofocus state and reading unchanged since), ms. */
    @Volatile var liveSettledAt: Long = 0L; private set
    private var settledRaw = Float.NaN; private var settledState = -1
    private var tapX = 0.5f; private var tapY = 0.5f

    /**
     * Each camera's focus-distance calibration: reading = offset + scale x true dioptres.
     * "Approximate" readings can be well off: the Nothing's periscope locks focus right but
     * reads a chair at 3 m (0.33 D) as 1.13 D and a table at 5 m (0.20 D) as 0.86 D, so
     * offset 0.45, scale 2.0 (Tim's measurement, 2026-11-19), on that phone (model A059P) only.
 * Others start as read and learn from parallax shots.
     */
    val afCalibration: MutableMap<String, Pair<Float, Float>> =
        if (android.os.Build.MODEL == "A059P") mutableMapOf("3" to Pair(0.45f, 2.0f)) else mutableMapOf()
    /** Cameras whose readings are true distances ("calibrated"): taken as they are. */
    private fun trusted(cameraId: String?) = _framing.value?.camera?.takeIf { it.id == cameraId }?.focusCalibration == 2
    fun corrected(cameraId: String, raw: Float): Float {
        if (trusted(cameraId)) return raw
        val (o, k) = afCalibration[cameraId] ?: return raw
        return maxOf((raw - o) / k, 0f)
    }
    /** The lens command for a true distance (dioptres): the calibration the other way. */
    fun command(cameraId: String?, trueD: Float): Float {
        if (trusted(cameraId)) return trueD
        val (o, k) = afCalibration[cameraId] ?: return trueD
        return o + k * trueD
    }

    /** The raw reading at the last autofocus lock (for collimating). */
    @Volatile var lastLockRaw: Float? = null; private set
    val currentCameraId: String? get() = heroId

    /** Recent autofocus locks after taps (calibration samples for the depth map). */
    val afSamples = java.util.concurrent.CopyOnWriteArrayList<AfSample>()
    @Volatile private var lastHeroResult: CaptureResult? = null
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

    /** Closes the camera for another user (ARCore, for parallax capture); returns once it is closed. */
    suspend fun release() {
        val d = CompletableDeferred<Unit>()
        handler.post { close(); d.complete(Unit) }
        d.await()
    }
    /** Opens the mounted lens's camera again after [release]. */
    fun reacquire() = handler.post { if (texture != null && device == null) _framing.value?.let { open(it) } }

    /**
     * The app went to the background: Android takes the camera from background apps (error 3,
     * "disabled"), so let it go cleanly first. [resume] opens it again and clears what showed.
     */
    fun pause() = handler.post { paused = true; close() }
    fun resume() = handler.post {
        paused = false
        _error.value = null
        if (texture != null && device == null) _framing.value?.let { open(it) }
    }
    @Volatile private var paused = false

    fun attach(t: SurfaceTexture) = handler.post { texture = t; _framing.value?.let { open(it) } }
    fun detach() = handler.post { close(); texture = null }

    /** Runs a camera call, turning failures (a camera taken away, closed under us) into a message, never a crash. */
    private inline fun safely(what: String, block: () -> Unit) {
        try { block() } catch (t: Throwable) {
            app.relens.Trace.w("camera: $what failed: ${t.message}")
            if (t !is IllegalStateException) _error.value = "camera: ${t.message}"
        }
    }

    /** Shoot this lens: opens its camera if it's a different one. */
    fun select(lens: Lens) {
        lastLens = lens
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
        try { openCam(fr, cam, tex, gen) } catch (t: Throwable) {
            app.relens.Trace.w("camera: open ${cam.id} failed: ${t.message}")
            _error.value = "camera: ${t.message}"
            giveUp(cam, t.javaClass.simpleName)
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCam(fr: Framing, cam: PhoneCamera, tex: SurfaceTexture, gen: Int) {
        run {
            characteristics = cm.getCameraCharacteristics(cam.id)
            val ps = previewSize(cam)
            tex.setDefaultBufferSize(ps.width, ps.height)
            preview = Surface(tex)
            jpegReader = ImageReader.newInstance(cam.pixels.width, cam.pixels.height, ImageFormat.JPEG, 2)
            if (cam.raw) {
                val rs = characteristics!!.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
                    .getOutputSizes(ImageFormat.RAW_SENSOR).maxBy { it.width * it.height }
                rawReader = ImageReader.newInstance(rs.width, rs.height, ImageFormat.RAW_SENSOR, 3)
            }
            if (heroId != cam.id) afSamples.clear()
            heroId = cam.id
            // Through the logical camera only to see round the picture with the ultra-wide; a lens on
            // the ultra-wide itself already has it.
            logicalOpen = useLogical && logicalId != null && cam.id != "2"
            if (logicalOpen && cam.id != "2") uwReader = ImageReader.newInstance(1280, 960, ImageFormat.YUV_420_888, 2)
            var everOpened = false
            cm.openCamera(if (logicalOpen) logicalId!! else cam.id, executor, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    everOpened = true
                    if (gen != generation) { d.close(); return }          // superseded while opening
                    device = d; startSession(d, gen)
                }
                override fun onDisconnected(d: CameraDevice) {
                    app.relens.Trace.w("camera ${cam.id} disconnected (current ${device == d})")
                    d.close()
                    if (device == d && !paused) { device = null; session = null; _error.value = "camera ${cam.id} was taken away; reopening"
                        handler.postDelayed({ _framing.value?.let { if (device == null && !paused) open(it) } }, 500) }
                    else if (device == d) { device = null; session = null }
                }
                override fun onError(d: CameraDevice, e: Int) {
                    app.relens.Trace.e("camera ${cam.id} error $e")
                    d.close()
                    // disabled (3) is Android taking the camera from an app in the background: not
                    // worth showing; it opens again when the app comes back (resume)
                    val quiet = e == ERROR_CAMERA_DISABLED || paused
                    if (device == d) { device = null; session = null; if (!quiet) _error.value = "camera ${cam.id} error $e" }
                    // only a camera that never opened, for the open still wanted, is set aside (an old
                    // session's error during a lens change is not this camera refusing)
                    if (!everOpened && gen == generation && !quiet) giveUp(cam, "error $e")
                }
            })
        }
    }

    private fun startSession(d: CameraDevice, gen: Int): Unit = safely("session") {
        val hero = heroId ?: return
        val outs = listOfNotNull(preview, jpegReader?.surface, rawReader?.surface).map { sfc ->
            OutputConfiguration(sfc).apply { if (logicalOpen) setPhysicalCameraId(hero) }
        } + listOfNotNull(uwReader?.surface).map { OutputConfiguration(it).apply { setPhysicalCameraId("2") } }
        val cfg = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outs, executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (gen != generation || device != d) { runCatching { s.close() }; return }
                    session = s; repeat()
                    startInfinityProbe()
                    app.relens.Trace.i("camera ${_framing.value?.camera?.id} open${if (logicalOpen) " (logical 4, ultra-wide map ${uwReader != null})" else ""}, crop %.2f, RAW ${rawReader != null}".format(_framing.value?.crop ?: 0f))
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    if (gen != generation || device != d) return
                    if (logicalOpen) {
                        // The logical camera won't take it: back to the camera on its own.
                        app.relens.Trace.e("logical session failed: opening camera $hero directly"); useLogical = false
                        _framing.value?.let { open(it) }; return
                    }
                    app.relens.Trace.e("session failed with RAW; retrying without")
                    if (rawReader != null) { rawReader?.close(); rawReader = null; startSession(d, gen) }
                    else _error.value = "camera session failed"
                }
            })
        if (logicalOpen && runCatching { d.isSessionConfigurationSupported(cfg) }.getOrDefault(true) == false) {
            app.relens.Trace.w("logical session not supported for $hero: direct"); useLogical = false
            _framing.value?.let { open(it) }; return
        }
        d.createCaptureSession(cfg)
    }

    private val resultCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
            lastResult = result
            val own = if (logicalOpen) heroId?.let { result.physicalCameraTotalResults[it] } ?: result else result
            // The meter: the phone's auto-exposure, as a scene brightness.
            run {
                val t = own.get(CaptureResult.SENSOR_EXPOSURE_TIME); val iso = own.get(CaptureResult.SENSOR_SENSITIVITY)
                val n = own.get(CaptureResult.LENS_APERTURE) ?: 1.88f
                if (t != null && iso != null && t > 0) {
                    val ev = (Math.log((n * n) / (t / 1e9)) / Math.log(2.0) - Math.log(iso / 100.0) / Math.log(2.0)).toFloat()
                    val prev = _sceneEV.value
                    _sceneEV.value = if (prev == null) ev else prev + (ev - prev) * 0.15f
                    lastEV = ev
                }
            }
            // The infinity probe: where the lens comes to rest when sent to infinity.
            if (probing) own.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { probeFrame(it) }
            // After the ring moves: where the lens actually went (a few frames later).
            if (manualWatch && result.frameNumber % 15 == 0L) {
                manualWatch = false
                app.relens.Trace.i("ring result: AF mode ${result.get(CaptureResult.CONTROL_AF_MODE)}, lens reads %s".format(
                    own.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { "%.2f D raw (%.2f m corrected)".format(it, 1 / maxOf(corrected(heroId ?: "0", it), 1e-3f)) } ?: "-"))
            }
            // After a tap: each autofocus state change and where the lens ends up.
            val af = result.get(CaptureResult.CONTROL_AF_STATE) ?: -1
            if (afWatch && af == CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN) sawScan = true
            if (afWatch && af != lastAfState) {
                lastAfState = af
                app.relens.Trace.i("AF state $af, lens at %s".format(own.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { "%.2f D (%.2f m)".format(it, 1 / maxOf(it, 1e-3f)) } ?: "-"))
                if (af == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED && sawScan) sweepWait?.complete(null)
                if (af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED && !sawScan) { lastAfState = -1; return }
                if (af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED) {
                    own.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { raw -> lastLockRaw = raw; sweepWait?.complete(corrected(heroId ?: "0", raw)) }
                    if (sweepWait == null) own.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { raw ->
                        val id = heroId ?: "0"
                        afSamples.add(AfSample(tapX, tapY, corrected(id, raw), id, System.currentTimeMillis()))
                        while (afSamples.size > 12) afSamples.removeAt(0)
                    }
                }
                if ((af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED || af == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) && sawScan) afWatch = false
            }
            liveAfState = af
            own.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { rawD ->
                liveRaw = rawD
                if (af != settledState || settledRaw.isNaN() || kotlin.math.abs(rawD - settledRaw) > 0.03f) {
                    settledState = af; settledRaw = rawD; liveSettledAt = System.currentTimeMillis()
                }
                val d = corrected(heroId ?: "0", rawD)
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
            // On the logical camera: the zoom that makes the hero the active camera (its 3A, its lens).
            if (logicalOpen) set(CaptureRequest.CONTROL_ZOOM_RATIO, when (heroId) { "2" -> 0.6f; "3" -> 3.2f; else -> 1.0f })
            val md = manualD
            val reg = afRegion
            when {
                probing -> {
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
                }
                md != null -> {
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    set(CaptureRequest.LENS_FOCUS_DISTANCE, command(heroId, md).coerceIn(0f, _framing.value?.camera?.minFocusD ?: 10f))
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
        // On the logical camera the regions are in its active array, relative to the zoomed
        // view (which is the hero's frame).
        val a = (if (logicalOpen) cm.getCameraCharacteristics(logicalId!!) else c).get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)!!
        // A region about 8 % of the sensor's short side: enough texture for the autofocus even
        // through a long lens's crop (smaller, a periscope's AF could fail and park the lens near).
        val half = (min(a.width(), a.height()) * 0.08f).toInt()
        val cx = (a.left + sx * a.width()).toInt(); val cy = (a.top + sy * a.height()).toInt()
        afRegion = MeteringRectangle(Rect(max(a.left, cx - half), max(a.top, cy - half),
            min(a.right, cx + half), min(a.bottom, cy + half)), MeteringRectangle.METERING_WEIGHT_MAX - 1)
        afWatch = true
        sawScan = false
        tapX = fx; tapY = fy
        app.relens.Trace.i("tap %.2f,%.2f on camera ${fr.camera.id} (crop %.2f): full frame %.2f,%.2f -> sensor %.2f,%.2f -> region %s of %s".format(
            fx, fy, fr.crop, ux, uy, sx, sy, afRegion?.rect, a))
        safely("focus") {
            val s = session ?: return@post
            val p = preview ?: return@post
            val b = baseRequest(CameraDevice.TEMPLATE_PREVIEW) ?: return@post
            b.addTarget(p)
            // Cancel the old lock first, or the camera keeps it and reports "locked" at once.
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
            s.capture(b.build(), resultCallback, handler)
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            s.capture(b.build(), resultCallback, handler)
        }
        repeat()
    }

    /** Autofocus again (collimating: the ring is an input, not the lens). */
    fun manualFocusOff() = handler.post { manualD = null; repeat() }

    /**
     * A camera's infinity, measured: an "approximate" camera's readings are shifted (the
     * Nothing's main camera reads 0.46 D with the lens at its far stop, where a true reading
     * is 0). Once per camera, the lens is sent to infinity for a moment and where it comes to
     * rest is that shift. [infinityKnown] says which cameras are done; [onInfinity] gets the result.
     */
    var infinityKnown: (String) -> Boolean = { true }
    var onInfinity: ((String, Float) -> Unit)? = null
    @Volatile private var probing = false
    private var probeId: String? = null
    private var probeFrames = 0
    private val probeReads = ArrayList<Float>()
    private fun startInfinityProbe() {
        val cam = _framing.value?.camera ?: return
        val id = heroId ?: return
        if (probing || cam.focusCalibration == 2 || cam.minFocusD <= 0f || infinityKnown(id)) return
        probing = true; probeId = id; probeFrames = 0; probeReads.clear()
        app.relens.Trace.i("infinity probe on camera $id")
        repeat()
    }
    private fun probeFrame(raw: Float) {
        probeFrames++; probeReads += raw
        val n = probeReads.size
        val still = n >= 8 && (n - 6 until n).all { kotlin.math.abs(probeReads[it] - probeReads[n - 1]) < 0.005f }
        if (!still && probeFrames < 60) return
        probing = false
        val id = probeId
        val inf = probeReads.takeLast(4).average().toFloat()
        handler.post { repeat() }
        if (id != null && id == heroId && still) {
            app.relens.Trace.i("infinity probe, camera $id: the lens rests at %.3f D at infinity (%d frames)".format(inf, probeFrames))
            onInfinity?.invoke(id, inf)
        } else app.relens.Trace.w("infinity probe, camera $id: no steady reading (%d frames)".format(probeFrames))
    }

    /** The focus ring driving the phone lens (dioptres; 0 = infinity); null: back to autofocus. */
    fun manualFocus(dioptres: Float?) = handler.post {
        if (dioptres == manualD && session != null) return@post
        manualD = dioptres
        if (dioptres == null) afRegion = null
        manualWatch = dioptres != null
        app.relens.Trace.i("ring: %s -> lens command %s on camera $heroId".format(
            dioptres?.let { "%.2f m".format(1 / maxOf(it, 1e-3f)) } ?: "AF",
            dioptres?.let { "%.2f D".format(command(heroId, it).coerceIn(0f, _framing.value?.camera?.minFocusD ?: 10f)) } ?: "-"))
        repeat()
    }

    /**
     * Test: can the logical camera "4" give the ultra-wide ("2") alongside the main ("0")
     * or periscope ("3") in one request? Opens 4 with small YUV streams on two physical
     * cameras and captures once. Logs what is supported and what arrives.
     */
    @SuppressLint("MissingPermission")
    suspend fun logicalTest(): String {
        val out = CompletableDeferred<String>()
        handler.post {
            close()
            val c4 = runCatching { cm.getCameraCharacteristics("4") }.getOrNull() ?: run { out.complete("no camera 4"); return@post }
            val phys = c4.physicalCameraIds
            val results = StringBuilder("physical ${phys}; concurrent sets ${runCatching { cm.concurrentCameraIds }.getOrNull()}; ")
            safely("logical test") {
                cm.openCamera("4", executor, object : CameraDevice.StateCallback() {
                    override fun onOpened(d: CameraDevice) {
                        fun tryPair(a: String, b: String, next: () -> Unit) {
                            val ra = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2)
                            val rb = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2)
                            val oa = OutputConfiguration(ra.surface).apply { setPhysicalCameraId(a) }
                            val ob = OutputConfiguration(rb.surface).apply { setPhysicalCameraId(b) }
                            val cfg = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, listOf(oa, ob), executor,
                                object : CameraCaptureSession.StateCallback() {
                                    override fun onConfigured(s: CameraCaptureSession) {
                                        var gotA = false; var gotB = false
                                        ra.setOnImageAvailableListener({ r -> r.acquireNextImage()?.close(); gotA = true }, handler)
                                        rb.setOnImageAvailableListener({ r -> r.acquireNextImage()?.close(); gotB = true }, handler)
                                        val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(ra.surface); addTarget(rb.surface) }.build()
                                        runCatching { s.capture(req, null, handler) }
                                        handler.postDelayed({
                                            results.append("$a+$b: configured, frames $a=$gotA $b=$gotB; ")
                                            s.close(); ra.close(); rb.close(); next()
                                        }, 2500)
                                    }
                                    override fun onConfigureFailed(s: CameraCaptureSession) {
                                        results.append("$a+$b: configure failed; "); ra.close(); rb.close(); next()
                                    }
                                })
                            val sup = runCatching { d.isSessionConfigurationSupported(cfg) }.getOrNull()
                            results.append("$a+$b supported=$sup ")
                            runCatching { d.createCaptureSession(cfg) }.onFailure { results.append("$a+$b: ${it.message}; "); next() }
                        }
                        // Does the periscope autofocus inside the logical session? Watch its own focus distance.
                        fun afTest(zoom: Float, next: () -> Unit) {
                            val ra = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 3)
                            val rb = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 3)
                            ra.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.close() }, handler)
                            rb.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.close() }, handler)
                            val oa = OutputConfiguration(ra.surface).apply { setPhysicalCameraId("3") }
                            val ob = OutputConfiguration(rb.surface).apply { setPhysicalCameraId("2") }
                            d.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, listOf(oa, ob), executor,
                                object : CameraCaptureSession.StateCallback() {
                                    override fun onConfigured(s: CameraCaptureSession) {
                                        val seen = ArrayList<String>()
                                        val cb = object : CameraCaptureSession.CaptureCallback() {
                                            override fun onCaptureCompleted(ss: CameraCaptureSession, r: CaptureRequest, t: TotalCaptureResult) {
                                                val p3 = t.physicalCameraTotalResults["3"]?.get(CaptureResult.LENS_FOCUS_DISTANCE)
                                                val af = t.get(CaptureResult.CONTROL_AF_STATE)
                                                if (seen.size < 400) seen.add("%.2f/%s".format(p3 ?: -1f, af))
                                            }
                                        }
                                        val b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                            addTarget(ra.surface); addTarget(rb.surface)
                                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                            if (zoom > 0f) set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
                                        }
                                        s.setRepeatingRequest(b.build(), cb, handler)
                                        handler.postDelayed({
                                            val dist = seen.map { it.substringBefore('/') }.distinct()
                                            results.append("AF zoom $zoom: periscope focus values ${dist.take(12)} (${seen.size} frames, last ${seen.lastOrNull()}); ")
                                            s.close(); ra.close(); rb.close(); next()
                                        }, 3500)
                                    }
                                    override fun onConfigureFailed(s: CameraCaptureSession) { results.append("AF test configure failed; "); next() }
                                }))
                        }
                        tryPair("0", "2") { tryPair("3", "2") { afTest(0f) { afTest(3.2f) { d.close(); out.complete(results.toString()) } } } }
                    }
                    override fun onDisconnected(d: CameraDevice) { d.close() }
                    override fun onError(d: CameraDevice, e: Int) { d.close(); out.complete("open 4 error $e") }
                })
            }
        }
        val r = try { withTimeout(30000) { out.await() } } catch (t: Throwable) { "timed out" }
        app.relens.Trace.i("logical test: $r")
        handler.post { _framing.value?.let { open(it) } }
        return r
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
        app.relens.Trace.i("raw test on camera ${fr.camera.id}: $out")
        return out
    }

    /**
     * The rangefinder sweep, right after the hero frame while the camera is held steady:
     * autofocus on each point (fractions of the lens's crop) in turn, and keep the corrected
     * distance where it locks. Points that won't lock (no texture) are dropped. Afterwards
     * focus goes back to where it was (the tapped subject, or the ring).
     */
    suspend fun sweep(points: List<Pair<Float, Float>>): List<AfSample> {
        val out = ArrayList<AfSample>()
        val savedRegion = afRegion; val savedManual = manualD; val savedTap = Pair(tapX, tapY)
        for ((fx, fy) in points) {
            val locked = CompletableDeferred<Float?>()
            sweepWait = locked
            focusAt(fx, fy)
            val d = try { withTimeout(550) { locked.await() } } catch (t: Throwable) { null }
            sweepWait = null
            if (d != null) out.add(AfSample(fx, fy, d, heroId ?: "0", System.currentTimeMillis()))
        }
        handler.post {
            afRegion = savedRegion; manualD = savedManual; tapX = savedTap.first; tapY = savedTap.second
            afWatch = false
            safely("restore focus") {
                val s = session ?: return@post; val p = preview ?: return@post
                val b = baseRequest(CameraDevice.TEMPLATE_PREVIEW) ?: return@post
                b.addTarget(p)
                if (savedRegion != null) b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                s.capture(b.build(), resultCallback, handler)
            }
            repeat()
        }
        app.relens.Trace.i("sweep: ${out.size}/${points.size} locked: " + out.joinToString { "(%.2f,%.2f %.2f m)".format(it.fx, it.fy, 1 / maxOf(it.dioptres, 1e-3f)) })
        return out
    }
    @Volatile private var sweepWait: CompletableDeferred<Float?>? = null

    /** Takes a frame: JPEG (and DNG when the camera gives RAW) into [dir]. */
    /** Merge a burst of RAW frames into each negative (RawMerge): BURST_EXTRA more than the first. */
    var burstMerge = true

    /** The hero shot's own capture result (its exposure, for the light map's exposure ratio). */
    @Volatile private var lastShotResult: CaptureResult? = null
    /** After each shot, a frame from the ultra-wide to find the lights round the picture. */
    var ultraWideMap = true

    /**
     * The ultra-wide's view, taken just after the shot while the photographer still holds the
     * camera as it was (the mirror is up, HOLD STEADY): the lights beyond the picture, for its
     * flare. This phone can't run the ultra-wide beside the RAW camera (no concurrent pair, and
     * RAW fails through the logical camera), so the camera switches to the ultra-wide for one
     * settled frame and back. Saves [name].uw.jpg / .uw.json beside the negative.
     */
    suspend fun grabLightMap(dir: File, name: String): Boolean {
        val fr = _framing.value ?: return false
        // the widest camera, when it is clearly wider than the one the shot was taken on
        val uwCam = cameras.firstOrNull()?.takeIf { it.f36 < fr.camera.f36 * 0.8f } ?: return false
        if (!ultraWideMap) return false
        val t0 = System.currentTimeMillis()
        val done = CompletableDeferred<Boolean>()
        var dev: CameraDevice? = null
        var reader: ImageReader? = null
        fun finishUp(ok: Boolean) {
            if (done.isCompleted) return
            runCatching { dev?.close() }; runCatching { reader?.close() }
            done.complete(ok)
            _framing.value?.let { open(it) }
        }
        handler.post {
            close()
            val r = ImageReader.newInstance(1280, 960, ImageFormat.YUV_420_888, 3); reader = r
            var latest: CaptureResult? = null
            var settleUntil = Long.MAX_VALUE
            safely("light map") {
                cm.openCamera(uwCam.id, executor, object : CameraDevice.StateCallback() {
                    override fun onOpened(d: CameraDevice) {
                        dev = d
                        val cfg = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, listOf(OutputConfiguration(r.surface)), executor,
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(s: CameraCaptureSession) {
                                    settleUntil = System.currentTimeMillis() + 500       // let its exposure settle
                                    r.setOnImageAvailableListener({ rr ->
                                        val img = rr.acquireLatestImage() ?: return@setOnImageAvailableListener
                                        if (System.currentTimeMillis() < settleUntil || latest == null || done.isCompleted) { img.close(); return@setOnImageAvailableListener }
                                        val ok = runCatching { LightMap.save(img, File(dir, "$name.uw.jpg"), File(dir, "$name.uw.json"), uwCam,
                                            (uwCam.sensorOrientation + deviceRotation) % 360, latest, lastShotResult) }
                                            .onFailure { app.relens.Trace.w("light map not saved", it) }.isSuccess
                                        img.close()
                                        app.relens.Trace.i("light map from the ultra-wide: $ok in ${System.currentTimeMillis() - t0} ms")
                                        runCatching { s.close() }
                                        finishUp(ok)
                                    }, handler)
                                    val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(r.surface) }.build()
                                    runCatching { s.setRepeatingRequest(req, object : CameraCaptureSession.CaptureCallback() {
                                        override fun onCaptureCompleted(ss: CameraCaptureSession, q: CaptureRequest, t: TotalCaptureResult) { latest = t }
                                    }, handler) }.onFailure { finishUp(false) }
                                }
                                override fun onConfigureFailed(s: CameraCaptureSession) { finishUp(false) }
                            })
                        runCatching { d.createCaptureSession(cfg) }.onFailure { finishUp(false) }
                    }
                    override fun onDisconnected(d: CameraDevice) { finishUp(false) }
                    override fun onError(d: CameraDevice, e: Int) { app.relens.Trace.w("ultra-wide error $e"); finishUp(false) }
                })
            }
        }
        return try { withTimeout(5_000) { done.await() } } catch (t: kotlinx.coroutines.TimeoutCancellationException) {
            app.relens.Trace.w("light map timed out"); handler.post { finishUp(false) }; false
        }
    }

    suspend fun shoot(dir: File, name: String): Exposure {
        val s = session ?: run { _framing.value?.let { fr -> handler.post { open(fr) } }; error("camera not ready (reopening)") }
        if (device == null) error("camera not open")
        val fr = _framing.value ?: error("no lens framing")
        dir.mkdirs()
        val jpeg = File(dir, "$name.jpg"); val dng = File(dir, "$name.dng")
        // Only a calibrated (or approximate) focus distance is in dioptres; uncalibrated is just a lens position.
        val focusD = lastFocusD.takeIf { fr.camera.focusCalibration > 0 }
        app.relens.Trace.i("shot on camera ${fr.camera.id}: focus %s (calibration ${fr.camera.focusCalibration})".format(lastFocusD?.let { "%.2f m".format(1 / maxOf(it, 1e-3f)) } ?: "-"))
        val done = CompletableDeferred<Exposure>()
        val jr = jpegReader!!; val rr = rawReader
        var jpegImage: Image? = null; var rawImage: Image? = null; var result: CaptureResult? = null
        var rawGiveUp = false
        // the burst's extra RAW frames, copied as they come (merged into the negative: RawMerge)
        val extraN = if (rr != null && burstMerge) BURST_EXTRA else 0
        val extras = java.util.Collections.synchronizedList(ArrayList<ShortArray>())
        var extrasGiveUp = false; var extrasWaiting = false
        fun finish() {
            val ji = jpegImage ?: return
            if (rawImage != null && result != null && extras.size < extraN && !extrasGiveUp) {
                if (!extrasWaiting) { extrasWaiting = true
                    handler.postDelayed({ if (!done.isCompleted) { extrasGiveUp = true; finish() } }, 1500) }
                return
            }
            if (rr != null && !rawGiveUp && (rawImage == null || result == null)) {
                if (rawImage != null && result == null) return
                // Some cameras accept a RAW stream and never fill it: don't lose the frame over it.
                if (result != null) handler.postDelayed({
                    if (!done.isCompleted && rawImage == null) {
                        app.relens.Trace.w("camera ${fr.camera.id}: no RAW image; keeping the JPEG")
                        rawGiveUp = true; finish()
                    }
                }, 1500)
                return
            }
            try {
                jpeg.outputStream().use { o -> val buf = ji.planes[0].buffer; val a = ByteArray(buf.remaining()); buf.get(a); o.write(a) }
                ji.close()
                rawImage?.takeIf { result != null }?.let { ri ->
                    // For the developer: the sensor's values as plain 16-bit, and what it needs to read them.
                    runCatching {
                        RawNegative.write(File(dir, "$name.raw16"), File(dir, "$name.rawmeta.json"), ri, characteristics!!, result!!, (fr.camera.sensorOrientation + deviceRotation) % 360)
                        // the burst's other frames merged in: less noise, the same picture
                        val ex = synchronized(extras) { extras.toList() }
                        // a test shot keeps its single first frame beside the merge (to compare)
                        if (ex.isNotEmpty() && name == "testshot") {
                            File(dir, "$name.raw16").copyTo(File(dir, "$name.single.raw16"), overwrite = true)
                            File(dir, "$name.rawmeta.json").copyTo(File(dir, "$name.single.rawmeta.json"), overwrite = true)
                        }
                        if (ex.isNotEmpty()) RawMerge.merge(File(dir, "$name.raw16"), File(dir, "$name.rawmeta.json"), ex, ri.width, ri.height)
                    }.onFailure { app.relens.Trace.w("raw negative not written", it) }
                    DngCreator(characteristics!!, result!!).use { dc ->
                        dc.setOrientation(exifOrientation(fr.camera.sensorOrientation))
                        dng.outputStream().use { dc.writeImage(it, ri) }
                    }
                    ri.close()
                }
                lastShotResult = result
                done.complete(Exposure(jpeg, dng.takeIf { it.exists() && it.length() > 0 }, focusD,
                    result?.get(CaptureResult.LENS_FOCUS_DISTANCE)))
            } catch (t: Throwable) { done.completeExceptionally(t) }
        }
        uwReader?.setOnImageAvailableListener({ r ->
            val img = r.acquireNextImage() ?: return@setOnImageAvailableListener
            runCatching { LightMap.save(img, File(dir, "$name.uw.jpg"), File(dir, "$name.uw.json"),
                cameras.first { it.id == "2" }, (cameras.first { it.id == "2" }.sensorOrientation + deviceRotation) % 360,
                lastUwResult, lastHeroResult) }.onFailure { app.relens.Trace.w("light map not saved", it) }
            img.close()
        }, handler)
        jr.setOnImageAvailableListener({ r -> jpegImage = r.acquireNextImage(); app.relens.Trace.i("jpeg in"); finish() }, handler)
        rr?.setOnImageAvailableListener({ r ->
            val img = r.acquireNextImage() ?: return@setOnImageAvailableListener
            if (rawImage == null) { rawImage = img; app.relens.Trace.i("raw in") }
            else { runCatching { extras += RawMerge.copy(img) }; img.close() }    // an extra: copied, given back
            finish()
        }, handler)
        handler.post { try {
            // This phone's camera fills a RAW stream only in a request of its own (RAW and
            // JPEG together: the JPEG only). So a burst: the RAW frame, then the JPEG frame.
            val jb = baseRequest(CameraDevice.TEMPLATE_STILL_CAPTURE) ?: run { done.completeExceptionally(IllegalStateException("no camera")); return@post }
            jb.addTarget(jr.surface)
            jb.set(CaptureRequest.JPEG_ORIENTATION, (fr.camera.sensorOrientation + deviceRotation) % 360)
            jb.set(CaptureRequest.JPEG_QUALITY, 97.toByte())
            val jpegReq = jb.build()
            val reqs = ArrayList<CaptureRequest>()
            val uw = uwReader
            if (rr != null) reqs.add(baseRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)!!.apply {
                addTarget(rr.surface)
                // The lens shading map (RAW isn't corrected for it): the developer applies it.
                set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON)
            }.build())
            reqs.add(jpegReq)
            // the extra RAW frames for the merge, the same as the first
            val extraReqs = (1..extraN).map {
                baseRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)!!.apply { addTarget(rr!!.surface) }.build()
            }
            reqs.addAll(extraReqs)
            // The ultra-wide's view of the same moment (where the lights are, round the picture):
            // a request of its own, since this phone fails a RAW request that also feeds it.
            val uwReq = uw?.let { u -> baseRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)!!.apply { addTarget(u.surface) }.build() }
            uwReq?.let { reqs.add(it) }
            s.captureBurst(reqs, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(ss: CameraCaptureSession, r: CaptureRequest, t: TotalCaptureResult) {
                    if (r == uwReq) { lastUwResult = t.physicalCameraTotalResults["2"]; return }
                    if (logicalOpen) { lastHeroResult = heroId?.let { t.physicalCameraTotalResults[it] } }
                    // The DNG needs the RAW frame's own result (the hero camera's, on the logical camera).
                    val t = if (logicalOpen) heroId?.let { t.physicalCameraTotalResults[it] } ?: t else t
                    if (r in extraReqs) return                    // the merge's frames: their results aren't the negative's
                    if (r != jpegReq || rr == null) { if (result == null || r != jpegReq) result = t }
                    if (rr == null) result = t
                    finish()
                }
                override fun onCaptureFailed(ss: CameraCaptureSession, r: CaptureRequest, f: android.hardware.camera2.CaptureFailure) {
                    if (r in extraReqs) { app.relens.Trace.w("a merge frame failed (${f.reason})"); return }
                    if (r == uwReq) { app.relens.Trace.w("ultra-wide frame failed (${f.reason}): no light map"); return }
                    app.relens.Trace.e("capture failed (${f.reason}) on the ${if (r == jpegReq) "JPEG" else "RAW"} request, logical $logicalOpen")
                    done.completeExceptionally(IllegalStateException("capture failed (${f.reason})"))
                }
            }, handler)
        } catch (t: Throwable) { done.completeExceptionally(t) } }
        return try { withTimeout(10_000) { done.await() } } catch (t: kotlinx.coroutines.TimeoutCancellationException) {
            app.relens.Trace.e("shot timed out: jpeg ${jpegImage != null} raw ${rawImage != null} result ${result != null} device ${device != null}")
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
        if (device != null) app.relens.Trace.i("closing camera ${device?.id}")
        generation++
        runCatching { session?.close() }; session = null
        runCatching { device?.close() }; device = null
        runCatching { jpegReader?.close() }; jpegReader = null
        runCatching { rawReader?.close() }; rawReader = null
        runCatching { uwReader?.close() }; uwReader = null
        runCatching { preview?.release() }; preview = null
    }

    companion object {
        const val BURST_EXTRA = 3

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
