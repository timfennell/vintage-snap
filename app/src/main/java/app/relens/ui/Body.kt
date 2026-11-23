package app.relens.ui

import kotlinx.coroutines.asCoroutineDispatcher

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.relens.Trace
import app.relens.camera.Rig
import app.relens.camera.Sweep
import app.relens.engine.DevelopJob
import app.relens.engine.Developer
import app.relens.engine.Models
import app.relens.film.filmStocks
import app.relens.optics.Movement
import app.relens.optics.fits
import app.relens.optics.lensFilters
import app.relens.optics.lensBag
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.pow

/** The shutter dial (Vintage Click's speeds): label, seconds. */
val shutterSpeeds: List<Pair<String, Float>> = listOf(
    "1" to 1f, "2" to 1f / 2, "4" to 1f / 4, "8" to 1f / 8, "15" to 1f / 15, "30" to 1f / 30,
    "60" to 1f / 60, "125" to 1f / 125, "250" to 1f / 250, "500" to 1f / 500,
    "1000" to 1f / 1000, "2000" to 1f / 2000, "4000" to 1f / 4000)

/** A frame in the darkroom: developing, developed or failed. */
class DarkroomEntry(val frame: Int, val label: String, var roll: Int = 0, val stock: String = "", val bw: Boolean = false) {
    /** Ringed on the light table as a keeper (in red china marker). */
    var marked by mutableStateOf(false)
    /** The print's file, while the app still holds it (not yet in the gallery). */
    var file: File? = null
    /** The print in the gallery (Pictures/Vintage Snap), and its DNG there. */
    var uri: android.net.Uri? = null
    var dngUri: android.net.Uri? = null
    /** When its development began (ms) and how long the darkroom timer was set for (s). */
    var devStart: Long = System.currentTimeMillis()
    var devSet: Float = 40f
    /** When the frame was developed (the light table lays its days out by it). */
    var taken: Long = System.currentTimeMillis()
    var preview by mutableStateOf<Bitmap?>(null)
    var status by mutableStateOf("developing…")
    var done by mutableStateOf(false)
}

/**
 * The camera body's state: the mounted lens, the aperture and focus rings,
 * the roll in the camera, and the darkroom. Kept between launches.
 */
class Body(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("body", Context.MODE_PRIVATE)
    /** The phone's cameras: the right one for each lens, cropped to its field of view. */
    val rig = Rig(app)
    val developer = Developer(app)

    var lensIndex by mutableIntStateOf(prefs.getInt("lens", lensBag.indexOfFirst { it.name == "Ohi Kor 50mm f/1.4" }.coerceAtLeast(0)))
        private set
    /** The aperture ring, in stops of the lens's own scale (fractional while turning). */
    var aperturePos by mutableFloatStateOf(prefs.getFloat("aperture", 0f))
    /** The focus ring, in marks of the lens's scale (fractional); NaN = follow the tapped subject. */
    private var _focusPos = mutableFloatStateOf(prefs.getFloat("focus", Float.NaN))
    var focusPos: Float
        get() = _focusPos.floatValue
        set(v) {
            _focusPos.floatValue = v
            // The phone's own lens follows the ring, so it focuses where the print will.
            if (!collimating) rig.manualFocus(focusM?.let { if (it.isInfinite()) 0f else 1f / it })
        }
    var stockIndex by mutableIntStateOf(prefs.getInt("stock", 1)); private set
    /** The shutter dial: index into [shutterSpeeds] (fractional while turning). */
    var shutterPos by mutableFloatStateOf(prefs.getFloat("shutter", 7f))
    /** The filter on the lens (index into lensFilters; 0 = none). */
    var filterIndex by mutableIntStateOf(prefs.getInt("filter", 0)); private set
    val filter get() = lensFilters[filterIndex]
    var push by mutableIntStateOf(prefs.getInt("push", 0)); private set
    var framesShot by mutableIntStateOf(prefs.getInt("frames", 0)); private set
    /** What the frame counter shows: it moves when the film is wound on (with the advance sound), not when the frame is taken. */
    var counterShows by mutableIntStateOf(framesShot); private set
    var rollNumber by mutableIntStateOf(prefs.getInt("roll", 1)); private set
    /** Where the photographer last tapped, fractions of the upright frame. */
    var focusX by mutableFloatStateOf(0.5f)
    var focusY by mutableFloatStateOf(0.5f)
    /** A tilt / whacked lens's tilt (degrees, across and up); a shift lens's shift (mm, right and up). */
    var tiltX by mutableFloatStateOf(0f)
    var tiltY by mutableFloatStateOf(0f)
    var shiftX by mutableFloatStateOf(0f)
    var shiftY by mutableFloatStateOf(0f)
    var busy by mutableStateOf(false); private set
    /** The depth models (fetched once, on the photographer's say-so). */
    val models = Models(app)
    fun downloadModels() { viewModelScope.launch { models.download() } }

    // ---- live depth for the finder: the split prism and the live blur ----

    /**
     * A depth map of the finder's picture, live (three or so a second): relative depth from the
     * network on a small copy of the preview, put to scale as disparity = [a] + [b] x relative by
     * the lens's own focus distance at the focus point (fresh while autofocusing, held when the
     * ring is taken by hand). [bmp]: the relative depth in its red channel, for the shaders.
     */
    class LiveDepth(val map: app.relens.engine.DepthMap, val bmp: Bitmap, val a: Float, val b: Float)
    val liveDepth = kotlinx.coroutines.flow.MutableStateFlow<LiveDepth?>(null)
    /** The finder's view (set by the screen): the live depth's source. */
    var finderView: android.view.TextureView? = null
    private var liveJob: kotlinx.coroutines.Job? = null
    private var liveAnchor: Pair<Float, Float>? = null       // (relative, dioptres) at the focus point
    private var liveFrames = 0

    /** The depth model for the live finder: the original free-size Depth Anything (models/da2s_fp32.onnx). */
    val liveModel: File get() = File(getApplication<Application>().getExternalFilesDir(null), "models/da2s_fp32.onnx")

    private val liveDispatcher = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "live-depth")
    }.let { with(kotlinx.coroutines.Dispatchers) { it.asCoroutineDispatcher() } }

    fun startLiveDepth() {
        startLiveCalibration()
        if (liveJob?.isActive == true || !liveModel.exists()) return
        // its own low-priority thread, and two threads for the model: the finder's drawing comes first
        liveJob = viewModelScope.launch(liveDispatcher) {
            val est = runCatching { app.relens.engine.DepthEstimator(liveModel, Pair(168, 224), threads = 2) }
                .onFailure { Trace.w("live depth: model didn't load", it) }.getOrNull() ?: return@launch
            try {
                while (kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.isActive == true) {
                    if (busy || parallax != null) { kotlinx.coroutines.delay(300); continue }
                    val shot = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        finderView?.takeIf { it.isAvailable }?.getBitmap(168, 224)
                    } ?: run { kotlinx.coroutines.delay(300); continue }
                    if (++liveFrames == 6) { runCatching {
                        File(getApplication<Application>().getExternalFilesDir("tests"), "live_src.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) } } }
                    val map = est.estimate(shot).depth; shot.recycle()
                    // scale: the lens focused at the focus point (autofocus) tells its distance there
                    // (the focus point is in the finder's crop; the map covers the whole preview)
                    val cr = rig.framing.value?.crop ?: 1f
                    val relF = map.medianAround(0.5f + (focusX - 0.5f) / (1.125f * cr), 0.5f + (focusY - 0.5f) / cr, 0.04f)
                    val lensD = rig.liveFocusD.value
                    if (focusPos.isNaN() && lensD != null && lensD > 0.02f) liveAnchor = Pair(relF, lensD)
                    val (ar, ad) = liveAnchor ?: Pair(relF, 0.5f)
                    val a = 0.02f
                    val b = maxOf((ad - a) / maxOf(ar, 0.05f), 0.01f)
                    val px = IntArray(map.w * map.h) { i -> val v = (map.data[i].coerceIn(0f, 1f) * 255).toInt(); (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
                    val bmp = Bitmap.createBitmap(px, map.w, map.h, Bitmap.Config.ARGB_8888)
                    liveDepth.value = LiveDepth(map, bmp, a, b)
                    // the centre's distance, eased over a few maps so the prism doesn't twitch
                    val c = a + b * map.medianAround(0.5f, 0.5f, 0.03f)
                    val prev = centreD
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { centreD = if (prev == null) c else prev + (c - prev) * 0.4f }
                    kotlinx.coroutines.delay(260)           // three or four maps a second: the prism eases between them
                }
            } finally { est.close() }
        }
    }
    fun stopLiveDepth() { liveJob?.cancel(); liveJob = null; calibJob?.cancel(); calibJob = null }

    /**
     * The focus calibration learning while the shot is composed, not only at the shutter: every
     * six seconds or so on autofocus, once the lens has settled, Metric3D measures a preview frame
     * and its distance at the focus point is paired with the lens's own reading there (weight 1/5:
     * a rough measure, many of them). The same view held still isn't counted again and again.
     */
    private var calibJob: kotlinx.coroutines.Job? = null
    private var lastLivePair: Pair<Float, Long>? = null
    private fun startLiveCalibration() {
        if (calibJob?.isActive == true) return
        calibJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            while (true) {
                kotlinx.coroutines.delay(1000)
                val fr = rig.framing.value ?: continue
                if (busy || parallax != null || !focusPos.isNaN()) continue
                val raw = rig.liveRaw ?: continue
                val state = rig.liveAfState
                if (state != 2 && state != 4) continue                            // passively focused, or locked
                val now = System.currentTimeMillis()
                // Only a reading the lens has just come to for this view: settled a moment, and for a
                // tap's lock (which holds the lens however the phone is turned after) fresh, within a
                // few seconds. A lock left standing while the picture is recomposed measures nothing.
                val settled = now - rig.liveSettledAt
                if (settled < 1200) continue
                if (state == 4 && settled > 4000) continue
                val last = lastLivePair
                if (last != null && now - last.second < 6000) continue
                if (last != null && kotlin.math.abs(raw - last.first) < 0.04f && now - last.second < 45_000) continue   // the same view, held
                val t0 = rig.liveSettledAt
                val shot = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    finderView?.takeIf { it.isAvailable }?.getBitmap(384, 512)
                } ?: continue
                val md = runCatching { developer.metricModel()?.estimate(shot, fr.camera.focalMM / fr.camera.sensorLongMM) }
                    .onFailure { Trace.w("live calibration: metric failed", it) }.getOrNull()
                shot.recycle()
                if (md == null || busy) continue
                if (rig.liveSettledAt != t0) continue                              // the lens moved meanwhile
                val z = md.medianAround(0.5f + (focusX - 0.5f) / (1.125f * fr.crop), 0.5f + (focusY - 0.5f) / fr.crop, 0.04f)
                if (z !in 0.15f..30f) continue
                lastLivePair = Pair(raw, now)
                val id = fr.camera.id
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { learnFocus(id, raw, 1f / z, 0.2f) }
            }
        }
    }

    /** The depth-of-field preview lever held: the finder shows the lens's blur at the set aperture. */
    var dofPreview by mutableStateOf(false)
    /** The subject's distance at the finder's centre (dioptres), smoothed over a few depth maps (the split prism). */
    var centreD by mutableStateOf<Float?>(null); private set

    /** The focus distance the finder shows, dioptres: the ring's, or the lens's own on autofocus. */
    val finderFocusD: Float? get() = focusM?.let { if (it.isInfinite()) liveFarD ?: 0f else 1f / it } ?: rig.liveFocusD.value
    /** The scene's far end in the live depth (dioptres): where the ring's infinity focuses. */
    private val liveFarD: Float? get() = liveDepth.value?.let { ld ->
        val sorted = ld.map.data.copyOf().also { it.sort() }
        maxOf(ld.a + ld.b * sorted[(sorted.size * 0.02).toInt()], 0f)
    }

    /** Keepers ringed on the light table, kept between launches ("roll:frame"). */
    fun isMarked(roll: Int, frame: Int) = "$roll:$frame" in (prefs.getStringSet("marks", emptySet()) ?: emptySet())
    fun toggleMark(e: DarkroomEntry) {
        e.marked = !e.marked
        val set = (prefs.getStringSet("marks", emptySet()) ?: emptySet()).toMutableSet()
        if (e.marked) set += "${e.roll}:${e.frame}" else set -= "${e.roll}:${e.frame}"
        prefs.edit().putStringSet("marks", set).apply()
    }

    /** Save a linear DNG of each relensed picture (no film) beside the print. */
    var dngOut by mutableStateOf(prefs.getBoolean("dng", true)); private set
    fun toggleDng() { dngOut = !dngOut; prefs.edit().putBoolean("dng", dngOut).apply() }
    /** Also put the camera's own untouched RAW (its DNG) in the gallery beside the print. */
    var rawOut by mutableStateOf(prefs.getBoolean("rawOut", false)); private set
    fun toggleRaw() { rawOut = !rawOut; prefs.edit().putBoolean("rawOut", rawOut).apply() }

    /** Parallax capture: after each shot, ARCore guides two side frames for measured depth. */
    val parallaxAvailable: Boolean = runCatching {
        com.google.ar.core.ArCoreApk.getInstance().checkAvailability(app).isSupported
    }.getOrDefault(false)
    // on from the start where the phone has ARCore: better prints, and it teaches the focus
    var parallaxOn by mutableStateOf(prefs.getBoolean("parallax", parallaxAvailable)); private set
    /** The one-time explanation, the first time the ghost camera appears. */
    var parallaxIntroSeen by mutableStateOf(prefs.getBoolean("parallaxIntro", false)); private set
    fun dismissParallaxIntro() { parallaxIntroSeen = true; prefs.edit().putBoolean("parallaxIntro", true).apply() }
    /** Bumped when a parallax shot teaches the focus (the switch's label follows it). */
    var focusLearnTick by mutableIntStateOf(0); private set
    /** How far a camera's focus calibration has got: (pairs so far, settled). Settled: 3 pairs spanning 0.25 D. */
    fun focusLearning(id: String?): Pair<Int, Boolean> {
        if (id == null) return Pair(0, false)
        val pts = loadPairs(id)
        val w = pts.sumOf { it.third.toDouble() }.toFloat()
        val ts = pts.map { it.second }
        return Pair(kotlin.math.round(w).toInt(), w >= 3f && ts.isNotEmpty() && (ts.max() - ts.min()) >= 0.25f)
    }
    fun toggleParallax() { parallaxOn = !parallaxOn; prefs.edit().putBoolean("parallax", parallaxOn).apply() }
    /** The capture in progress (the finder shows it), or null. */
    var parallax by mutableStateOf<app.relens.parallax.ParallaxCapture?>(null); private set
    /** Testing from the Mac: a parallax capture on its own, into [dir] (no hero shot, no roll). */
    fun parallaxTest(dir: File, subjectM: Float) {
        viewModelScope.launch {
            val pc = app.relens.parallax.ParallaxCapture(getApplication(), dir, subjectM, { rig.deviceRotation }) { onParallaxFire() }
            rig.release(); parallax = pc
            val ok = runCatching { pc.run() }.onFailure { Trace.e("parallax test failed", it) }.getOrDefault(false)
            parallax = null; rig.reacquire()
            Trace.i("parallax test: $ok")
        }
    }
    /** Plays the shutter as each side frame is taken (set by the screen, which owns the sounds). */
    var onParallaxFire: () -> Unit = {}

    /** The rangefinder sweep after the shot: the finder asks the photographer to hold steady. */
    var sweeping by mutableStateOf(false); private set
    private val steadiness = Sweep.Steadiness(app)
    /** The last thing that went wrong, shown on the body until the next shot. */
    var message by mutableStateOf<String?>(null)
    val darkroom = mutableStateListOf<DarkroomEntry>()

    val lens get() = lensBag[lensIndex]

    // ---- exposure: the meter and the shutter (Vintage Click's CameraBody) ----
    val shutterSeconds: Float get() {
        val i = shutterPos.coerceIn(0f, (shutterSpeeds.size - 1).toFloat())
        val a = i.toInt().coerceAtMost(shutterSpeeds.size - 2); val t = i - a
        return shutterSpeeds[a].second * (shutterSpeeds[a + 1].second / shutterSpeeds[a].second).pow(t)
    }
    /** Reciprocity failure past 1/4 s: film answers less than the time suggests (meters don't know). */
    fun reciprocity(t: Float) = if (t <= 0.25f) 0f else (if (stock.blackAndWhite) 0.5f else 0.25f) * kotlin.math.log2(t / 0.25f)
    /**
     * Stops over (+) or under (-) for the film: the scene's EV as metered, against the EV of
     * the f-number and speed at the rated EI, less the filter's light and reciprocity.
     */
    fun exposureStops(sceneEV: Float): Float {
        val n = fNumber; val t = shutterSeconds
        val ev = kotlin.math.log2(n * n / t) - kotlin.math.log2(ratedEI / 100f)
        return sceneEV - ev - filter.stops - reciprocity(t)
    }
    /** Where on the shutter scale the meter's needle points (the speed for a correct exposure). */
    fun meteredShutterPos(sceneEV: Float): Float {
        val n = fNumber
        // t for 0 stops (ignoring reciprocity, as a meter does)
        val tStar = n * n / 2f.pow(sceneEV - filter.stops + kotlin.math.log2(ratedEI / 100f))
        // fractional index by log time
        val logs = shutterSpeeds.map { kotlin.math.log2(it.second) }
        val lt = kotlin.math.log2(tStar)
        if (lt >= logs.first()) return 0f - (lt - logs.first())
        for (i in 0 until logs.size - 1) if (lt <= logs[i] && lt >= logs[i + 1]) return i + (logs[i] - lt) / (logs[i] - logs[i + 1])
        return (logs.size - 1).toFloat() + (logs.last() - lt)
    }

    // ---- collimating: each camera's focus readings against true distances ----
    /** Collimating: taps measure, the ring is set to the true distance (and doesn't drive the lens). */
    var collimating by mutableStateOf(false)
    /** (raw reading, true dioptres) recorded for the current camera. */
    val collimation = mutableStateListOf<Pair<Float, Float>>()
    var collimationNote by mutableStateOf("")

    fun startCollimating() {
        collimating = true; collimation.clear()
        collimationNote = "tap a target, turn the ring to its true distance, press SET"
    }

    /** Records the last lock against the ring's distance; with two, fits offset and scale. */
    /**
     * Collimating by itself: each parallax frame shot on autofocus pairs the lens's own
     * reading at the lock with ARCore's measured distance at that point. The last ten pairs
     * per camera fix its calibration (reading = offset + scale x true dioptres): a line
     * once they span distances, else just the offset, keeping the scale.
     */
    /**
     * The focus calibration teaching itself, from every autofocus shot on every camera: the lens's
     * own reading at the lock, against the frame's measured distance at the focus point.
     * ARCore's (a parallax shot) counts fully; Metric3D's (any shot) a third, since it is rougher.
     * The last 24 pairs per camera set reading = offset + scale x true dioptres by a weighted
     * line refitted without outliers; until they span 0.25 D only the offset moves.
     */
    /** A camera's measured infinity reading (the rig's probe), if it has one. */
    private fun infinityOf(id: String): Float? = prefs.getFloat("afinf_$id", Float.NaN).takeIf { !it.isNaN() }

    private fun learnFocus(id: String, raw: Float, trueD: Float, weight: Float) {
        if (trueD !in 0.05f..6f || raw <= 0f) return
        if (rig.framing.value?.camera?.takeIf { it.id == id }?.focusCalibration == 2) return   // its readings are true already
        val key = "afsamples_$id"
        val pts = loadPairs(id).toMutableList()
        pts += Triple(raw, trueD, weight)
        while (pts.size > 24) pts.removeAt(0)
        prefs.edit().putString(key, pts.joinToString(";") { "${it.first}:${it.second}:${it.third}" }).apply()
        focusLearnTick++
        // newer pairs count more (each step back in time a little less): a calibration follows
        // the camera as it is now, and old mistakes fade out
        val wpts = pts.mapIndexed { i, p -> Triple(p.first, p.second, p.third * Math.pow(0.9, (pts.size - 1 - i).toDouble()).toFloat()) } +
            collimationPairs(id)                                                  // the hand's targets, full weight, for good
        val all = pts + collimationPairs(id)
        var keep = all.map { true }.toBooleanArray()
        var k = rig.afCalibration[id]?.second ?: 1f
        var o = 0f
        repeat(3) {
            val sel = wpts.filterIndexed { i, _ -> keep[i] }
            val sw = sel.sumOf { it.third.toDouble() }
            val mt = sel.sumOf { (it.second * it.third).toDouble() } / sw
            val mr = sel.sumOf { (it.first * it.third).toDouble() } / sw
            val spread = (sel.maxOfOrNull { it.second } ?: 0f) - (sel.minOfOrNull { it.second } ?: 0f)
            val inf = infinityOf(id)
            if (inf != null) {
                // the line goes through the measured infinity (reading inf at 0 D): only its slope is
                // learnt, and only from pairs far enough from infinity to say anything about it
                var num = 0.0; var den = 0.0
                for ((r, t, w) in sel) if (t > 0.15f) { num += w * t * (r - inf); den += w * t * t }
                if (den > 0) { val fitK = (num / den).toFloat(); if (fitK in 0.5f..3.5f) k = fitK }
                o = inf
            } else if (sel.size >= 2 && spread >= 0.25f) {
                var sxy = 0.0; var sxx = 0.0
                for ((r, t, w) in sel) { sxy += w * (t - mt) * (r - mr); sxx += w * (t - mt) * (t - mt) }
                val fitK = (sxy / sxx).toFloat()
                if (fitK in 0.5f..3.5f) k = fitK
            }
            if (inf == null) o = (mr - k * mt).toFloat()
            // drop the pairs the line can't explain (a lock on something else, a bad estimate)
            val res = all.map { kotlin.math.abs(it.first - (o + k * it.second)) }
            val med = res.sorted()[res.size / 2]
            keep = res.map { it <= 3f * maxOf(med, 0.03f) }.toBooleanArray()
        }
        val old = rig.afCalibration[id]
        rig.afCalibration[id] = Pair(o, k)
        prefs.edit().putString("afcal_$id", "$o,$k").apply()
        Trace.i("focus calibration, camera $id: read %.3f D at a measured %.2f m (weight %.2f); now reading = %.3f + %.3f x true (%d pairs; was %s)".format(
            raw, 1 / trueD, weight, o, k, pts.size, old?.let { "%.3f + %.3f".format(it.first, it.second) } ?: "none"))
    }

    /** A camera's saved pairs: (reading, true dioptres, weight); older pairs (no weight) count fully. */
    private fun loadPairs(id: String) = (prefs.getString("afsamples_$id", "") ?: "").split(";").mapNotNull { e ->
        val v = e.split(":").mapNotNull { it.toFloatOrNull() }
        when (v.size) { 2 -> Triple(v[0], v[1], 1f); 3 -> Triple(v[0], v[1], v[2]); else -> null }
    }

    /** A camera's hand-collimation targets: (reading, true dioptres, weight 5), never aged out. */
    private fun collimationPairs(id: String) = (prefs.getString("afcolli_$id", "") ?: "").split(";").mapNotNull { e ->
        val v = e.split(":").mapNotNull { it.toFloatOrNull() }
        if (v.size == 3) Triple(v[0], v[1], v[2]) else null
    }

    // ---------------- Focus calibration, a camera at a time ----------------

    /** A camera as the calibration page lists it: the camera, a lens that shoots on it, its state. */
    class CalibCamera(val camera: app.relens.camera.PhoneCamera, val role: String, val lensIndex: Int, val status: String, val done: Boolean)

    fun calibCameras(): List<CalibCamera> {
        val seen = LinkedHashMap<String, CalibCamera>()
        for ((i, l) in lensBag.withIndex()) {
            val fr = rig.framingFor(l) ?: continue
            val c = fr.camera
            if (c.id in seen || c.minFocusD <= 0f) continue
            val role = fr.cameraRole
            val hand = collimationPairs(c.id).size
            val (status, done) = when {
                c.focusCalibration == 2 -> "calibrated by the phone: nothing to do" to true
                hand >= 2 -> "calibrated by hand (${hand} targets)" + (infinityOf(c.id)?.let { " · infinity at %.2f".format(it) } ?: "") to true
                infinityOf(c.id) != null -> "infinity measured · learning from your shots (${loadPairs(c.id).size} so far)" to false
                else -> "not calibrated yet" to false
            }
            seen[c.id] = CalibCamera(c, role, i, status, done)
        }
        return seen.values.toList()
    }

    /** The calibration under way: which camera, which step (0 infinity, 1 and 2 targets, 3 done). */
    class Calib(val cameraId: String, val role: String, val near: Float, val far: Float, val returnLens: Int) {
        var step by mutableIntStateOf(0)
        var distance by mutableStateOf("")
        var note by mutableStateOf("")
        val pairs = ArrayList<Pair<Float, Float>>()        // (reading, true dioptres)
        var stepStarted = System.currentTimeMillis()
    }
    var calib by mutableStateOf<Calib?>(null); private set

    /** How long a development takes lately (s): what the darkroom timer is wound to. */
    private var devEstimate = prefs.getFloat("devS", 40f)

    fun startCalibration(c: CalibCamera) {
        val id = c.camera.id
        // suggested distances: close and farther, for what the camera focuses on
        val (near, far) = when {
            c.role == "macro" -> 0.15f to 0.40f
            c.role == "tele" -> 1.5f to 5f
            else -> 1f to 3f
        }
        val cal = Calib(id, c.role, near, far, lensIndex)
        // start clean: this camera's targets, learnt pairs and infinity measured afresh
        prefs.edit().remove("afcolli_$id").remove("afsamples_$id").remove("afinf_$id").apply()
        calib = cal
        if (lensIndex != c.lensIndex) mount(c.lensIndex)
        focusPos = Float.NaN; rig.manualFocus(null)                // the lens on autofocus
        cal.note = "measuring where this lens rests at infinity…"
        if (rig.currentCameraId == id) rig.measureInfinity()        // else it is measured as the camera opens
    }

    private fun calibInfinityDone(id: String) {
        val cal = calib ?: return
        if (cal.cameraId != id || cal.step != 0) return
        cal.step = 1; cal.stepStarted = System.currentTimeMillis()
        cal.note = calibAsk(cal)
    }

    private fun calibAsk(cal: Calib) = "put something with clear edges about %s away and measure the distance from the phone. Tap it in the finder to focus, type the distance, press SET.".format(
        fmtM(if (cal.step == 1) cal.near else cal.far))

    private fun fmtM(m: Float) = if (m < 1f) "%.0f cm".format(m * 100) else "%.1f m".format(m)

    fun calibSet() {
        val cal = calib ?: return
        if (cal.step !in 1..2) return
        val m = cal.distance.replace(',', '.').toFloatOrNull()
        if (m == null || m !in 0.05f..50f) { cal.note = "type the measured distance in metres (e.g. 1.25)"; return }
        val raw = rig.lastLockRaw
        if (raw == null || rig.lastLockAt < cal.stepStarted) { cal.note = "tap the target in the finder first, so the lens focuses on it"; return }
        if (cal.pairs.isNotEmpty() && kotlin.math.abs(1f / m - cal.pairs.last().second) < 0.15f) {
            cal.note = "the second target needs to be at a clearly different distance (try about ${fmtM(cal.far)})"; return
        }
        cal.pairs += raw to 1f / m
        Trace.i("calibration, camera ${cal.cameraId}: target ${cal.pairs.size} at %.2f m read %.3f".format(m, raw))
        if (cal.step == 1) { cal.step = 2; cal.distance = ""; cal.stepStarted = System.currentTimeMillis(); cal.note = calibAsk(cal); return }
        // the line through the measured infinity and the two targets
        val id = cal.cameraId
        val inf = infinityOf(id) ?: 0f
        var num = 0.0; var den = 0.0
        for ((r, t) in cal.pairs) { num += t * (r - inf); den += t * t }
        val k = (num / den).toFloat()
        if (k !in 0.3f..5f) { cal.note = "those readings don't fit together (scale %.2f): try again, tapping each target carefully".format(k); cal.pairs.clear(); cal.step = 1; cal.stepStarted = System.currentTimeMillis(); return }
        rig.afCalibration[id] = Pair(inf, k)
        prefs.edit().putString("afcal_$id", "$inf,$k")
            .putString("afcolli_$id", cal.pairs.joinToString(";") { "${it.first}:${it.second}:5.0" }).apply()
        val worst = cal.pairs.maxOf { (r, t) -> kotlin.math.abs((r - inf) / k - t) }
        Trace.i("calibration, camera $id done: reading = %.3f + %.3f x true (worst target off by %.3f D)".format(inf, k, worst))
        cal.step = 3
        cal.note = "the ${cal.role} camera is calibrated. Its focus readings, ring and split prism now agree with the tape."
        focusLearnTick++
    }

    fun endCalibration() {
        val cal = calib ?: return
        calib = null
        if (lensIndex != cal.returnLens) mount(cal.returnLens)
    }

    fun collimateSet() {
        val raw = rig.lastLockRaw ?: run { collimationNote = "tap a target first"; return }
        val m = focusM ?: run { collimationNote = "turn the ring to the target's distance"; return }
        val trueD = if (m.isInfinite()) 0f else 1f / m
        collimation.add(Pair(raw, trueD))
        Trace.i("collimation target ${collimation.size}: camera read %.3f D (%.2f m raw), ring says %.2f m".format(raw, 1 / maxOf(raw, 1e-3f), m))
        val id = rig.currentCameraId ?: "0"
        val (o, k) = if (collimation.size >= 2) {
            val (r1, t1) = collimation[collimation.size - 2]; val (r2, t2) = collimation.last()
            if (kotlin.math.abs(t2 - t1) < 0.05f) { collimationNote = "pick a second target at a clearly different distance"; collimation.removeLast(); return }
            val kk = (r2 - r1) / (t2 - t1)
            Pair(r1 - kk * t1, kk)
        } else {
            val kk = rig.afCalibration[id]?.second ?: 1f
            Pair(raw - kk * trueD, kk)
        }
        rig.afCalibration[id] = Pair(o, k)
        prefs.edit().putString("afcal_$id", "$o,$k").apply()
        // kept for good (the last six targets): the automatic learning fits around them, never over them
        val colli = (collimationPairs(id) + Triple(raw, trueD, 5f)).takeLast(6)
        prefs.edit().putString("afcolli_$id", colli.joinToString(";") { "${it.first}:${it.second}:${it.third}" }).apply()
        Trace.i("collimated camera $id: reading = %.3f + %.3f x true (from ${collimation.size} targets)".format(o, k))
        val got = "read %.2f m raw, set %.2f m".format(1 / maxOf(raw, 1e-3f), m)
        collimationNote = if (collimation.size >= 2) "$got · the ${rig.framing.value?.cameraRole ?: ""} camera ($id) collimated (offset %.2f, scale %.2f)".format(o, k)
            else "$got · now a second target at another distance".format(o)
        if (collimation.size >= 2) { collimating = false; focusPos = Float.NaN }   // back to autofocus, corrected
    }

    /**
     * The light table is the gallery's Pictures/Vintage Snap: the recent prints (the last 48),
     * read back from there, since the app keeps nothing once a print is in it. Read again each
     * time the table is opened or the app comes back, so a print deleted from the folder leaves
     * the table too. Roll and frame come from the note in each print's EXIF; older prints fall
     * back on the sidecar or negative the app kept for them then. Small previews, off the main thread.
     */
    fun refreshLightTable() { if (!refreshing) { refreshing = true; loadLightTable().invokeOnCompletion { refreshing = false } } }
    @Volatile private var refreshing = false
    private fun loadLightTable() = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        val app = getApplication<Application>()
        val cr = app.contentResolver
        val ms = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        class G(val uri: android.net.Uri, val name: String, val added: Long)
        val gallery = ArrayList<G>()
        runCatching {
            cr.query(ms, arrayOf(android.provider.MediaStore.Images.Media._ID, android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                    android.provider.MediaStore.Images.Media.DATE_ADDED),
                "${android.provider.MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND ${android.provider.MediaStore.Images.Media.DISPLAY_NAME} LIKE ?",
                arrayOf("Pictures/Vintage Snap%", "VINTAGESNAP_%"), null)?.use { c ->
                while (c.moveToNext()) gallery += G(android.content.ContentUris.withAppendedId(ms, c.getLong(0)), c.getString(1), c.getLong(2))
            }
        }.onFailure { Trace.w("gallery query failed", it) }
        val dngs = gallery.filter { it.name.endsWith(".dng") && !it.name.endsWith("_RAW.dng") }.associateBy { it.name.removeSuffix(".dng") }
        val inGallery = gallery.filter { it.name.endsWith(".jpg") }
        val galleryUris = inGallery.map { it.uri }.toSet()
        val printsDir = app.getExternalFilesDir("prints")
        val negs = app.getExternalFilesDir("negatives")?.listFiles { f -> f.name.matches(Regex("R\\d+_F\\d+_\\d+\\.jpg")) }?.toList() ?: emptyList()

        class Src(val name: String, val time: Long, val uri: android.net.Uri?, val file: File?)
        val shown = darkroom.toList().mapNotNull { it.uri }.toSet()
        val sources = inGallery.map { Src(it.name.removeSuffix(".jpg"), it.added * 1000, it.uri, null) }
            .sortedBy { it.time }.takeLast(48).filter { it.uri !in shown }      // only what isn't on the table yet

        fun note(j: org.json.JSONObject) = DarkroomEntry(j.getInt("frame"), j.optString("label"), j.getInt("roll"), j.optString("stock"), j.optBoolean("bw"))
        val loaded = ArrayList<DarkroomEntry>()
        for (src in sources) runCatching {
            fun open() = src.uri?.let { cr.openInputStream(it) } ?: src.file!!.inputStream()
            val exif = open().use { androidx.exifinterface.media.ExifInterface(it) }
            val comment = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_USER_COMMENT)
            val json = comment?.let { c -> val a = c.indexOf('{'); val b = c.lastIndexOf('}'); if (a >= 0 && b > a) c.substring(a, b + 1) else null }
            val side = printsDir?.let { File(it, src.name + ".json") }
            val e: DarkroomEntry = when {
                json != null -> note(org.json.JSONObject(json))
                side != null && side.exists() -> note(org.json.JSONObject(side.readText()))
                else -> {
                    val frame = src.name.substringAfterLast("_").toIntOrNull() ?: return@runCatching
                    if (frame == 0) return@runCatching                         // a test development
                    val desc = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_IMAGE_DESCRIPTION) ?: ""
                    val neg = negs.filter { it.name.contains("_F%02d_".format(frame)) && it.lastModified() <= src.time + 1000 }
                        .maxByOrNull { it.lastModified() }?.takeIf { src.time - it.lastModified() < 10 * 60_000 }
                    val roll = neg?.let { it.name.substring(1, it.name.indexOf('_')).toInt() } ?: 0
                    val stockName = desc.substringAfterLast(" on ", "").substringBefore(" (").trim()
                    val stock = filmStocks.firstOrNull { it.name == stockName }
                    DarkroomEntry(frame, (if (roll > 0) "Roll $roll · " else "") + "frame $frame · $desc", roll, stockName, stock?.blackAndWhite ?: false)
                }
            }
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            open().use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
            var ss = 1; while (maxOf(bounds.outWidth, bounds.outHeight) / (ss * 2) >= 1440) ss *= 2
            e.preview = open().use { android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = ss }) }
            e.status = "saved"; e.done = true
            e.uri = src.uri; e.file = src.file; e.dngUri = dngs[src.name]?.uri; e.taken = src.time
            e.marked = isMarked(e.roll, e.frame)
            loaded += e
        }.onFailure { Trace.w("light table: ${src.name} not loaded", it) }
        // Prints from before their roll was noted in them: rolls pieced back together from the
        // order they were made in (a new roll where the stock changes or the count starts over).
        run {
            var pseudo = 0; var lastFrame = Int.MAX_VALUE; var lastStock = ""
            for (e in loaded.filter { it.roll == 0 }.sortedBy { it.taken }) {
                if (pseudo == 0 || e.frame <= lastFrame || e.stock != lastStock) pseudo++
                e.roll = -pseudo; e.marked = isMarked(e.roll, e.frame); lastFrame = e.frame; lastStock = e.stock
            }
        }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            // gone from the folder: off the table (frames still developing, or kept in the app, stay)
            val gone = darkroom.filter { it.uri != null && it.uri !in galleryUris }
            darkroom.removeAll(gone)
            // newest first, as the session adds them
            val have = darkroom.mapNotNull { it.uri }.toSet()
            for (e in loaded.filter { it.uri !in have }.sortedByDescending { it.uri?.lastPathSegment?.toLongOrNull() ?: 0L }) {
                val at = darkroom.indexOfFirst { d -> (d.uri?.lastPathSegment?.toLongOrNull() ?: Long.MAX_VALUE) < (e.uri?.lastPathSegment?.toLongOrNull() ?: 0L) }
                if (at < 0) darkroom.add(e) else darkroom.add(at, e)
            }
        }
        Trace.i("light table: ${loaded.size} added, ${inGallery.size} prints in the gallery")
    }

    /** Everything the app kept of a frame to develop it (the negative, its RAW, the light map, the parallax views). */
    private fun clearNegative(name: String) {
        val dir = getApplication<Application>().getExternalFilesDir("negatives") ?: return
        var freed = 0L
        for (ext in listOf("jpg", "dng", "raw16", "rawmeta.json", "uw.jpg", "uw.json", "par")) {
            val f = File(dir, "$name.$ext")
            if (!f.exists()) continue
            freed += if (f.isDirectory) f.walkBottomUp().sumOf { it.length() } else f.length()
            if (f.isDirectory) f.deleteRecursively() else f.delete()
        }
        Trace.i("negative $name cleared: ${freed / 1_000_000} MB")
    }

    init {
        refreshLightTable()
        // The first focus learning (2026-11-20) paired a stale lock reading with each shot:
        // forget its pairs and put camera 0 back where Tim's collimation left it.
        if (!prefs.getBoolean("aflearn_v2", false)) {
            val e = prefs.edit()
            if (prefs.contains("afsamples_0")) e.putString("afcal_0", "0.902,1.579")
            for (id in listOf("0", "2", "3")) e.remove("afsamples_$id")
            e.putBoolean("aflearn_v2", true).apply()
        }
        // Live pairs before 0.1.10 could pair a tap's lock left standing with a recomposed view
        // (camera 0 drifted to reading 0.9 = 17 m at a 2 m target): forget them, and start camera 0
        // from what Tim's 2026-11-22 recording showed (reading ~0.95 sharp at 2 m), scale 1.
        if (!prefs.getBoolean("aflearn_v3", false)) {
            val e = prefs.edit()
            if (android.os.Build.MODEL == "A059P") e.putString("afcal_0", "0.45,1.0")
            for (id in listOf("0", "2", "3")) e.remove("afsamples_$id")
            e.putBoolean("aflearn_v3", true).apply()
        }
        // Camera 0 on Tim's phone: the probe puts infinity at 0.12, and his 2026-11-22 recording had
        // a 2 m target sharp at ~0.95, so a scale of ~1.66 to start from (the learning refines it).
        if (!prefs.getBoolean("aflearn_v4", false)) {
            val e = prefs.edit()
            if (android.os.Build.MODEL == "A059P") {
                val inf = infinityOf("0") ?: 0.12f
                e.putString("afcal_0", "$inf,1.66"); rig.afCalibration["0"] = Pair(inf, 1.66f)
            }
            e.remove("afsamples_0")
            e.putBoolean("aflearn_v4", true).apply()
        }
        // Each camera's infinity, measured once by the rig; the calibration's offset from then on.
        rig.infinityKnown = { id -> infinityOf(id) != null }
        rig.onInfinity = { id, inf ->
            prefs.edit().putFloat("afinf_$id", inf).apply()
            val k = rig.afCalibration[id]?.second ?: 1f
            rig.afCalibration[id] = Pair(inf, k)
            prefs.edit().putString("afcal_$id", "$inf,$k").apply()
            Trace.i("camera $id: infinity at reading %.3f; now reading = %.3f + %.3f x true".format(inf, inf, k))
            calibInfinityDone(id)
        }
        // Saved collimations: "afcal_<camera>" = "offset,scale".
        for (id in listOf("0", "2", "3")) prefs.getString("afcal_$id", null)?.split(",")?.mapNotNull { it.toFloatOrNull() }
            ?.takeIf { it.size == 2 }?.let { rig.afCalibration[id] = Pair(it[0], it[1]) }
        if (rig.framingFor(lensBag[lensIndex]) == null)
            lensIndex = lensBag.indexOfFirst { it.name == "Ohi Kor 50mm f/1.4" }.coerceAtLeast(0)
        // A ring set by hand last time: the phone's lens goes there too (the rig applies it when it opens).
        rig.manualFocus(focusM?.let { if (it.isInfinite()) 0f else 1f / it })
    }
    val stock get() = filmStocks[stockIndex]
    val framesLeft get() = stock.exposures - framesShot
    val ratedEI get() = stock.iso * 2f.pow(push)

    /** The f-number under the aperture ring's index (stops snap; between them while turning). */
    val fNumber: Float get() {
        val s = lens.apertureStops
        val p = aperturePos.coerceIn(0f, (s.size - 1).toFloat())
        val i = p.toInt().coerceAtMost(s.size - 2).coerceAtLeast(0)
        if (s.size == 1) return s[0]
        val t = p - i
        // Between clicks the iris moves evenly in stops (log of the f-number).
        return (s[i] * (s[i + 1] / s[i]).pow(t))
    }

    /** The focus ring's distance in metres (null: on the tapped subject). */
    val focusM: Float? get() {
        if (focusPos.isNaN()) return null
        val m = lens.focusMarks
        val p = focusPos.coerceIn(0f, (m.size - 1).toFloat())
        val i = p.toInt().coerceAtMost(m.size - 2)
        val t = p - i
        // Interpolate in dioptres (infinity = 0).
        val d0 = 1f / m[i]; val d1 = 1f / m[i + 1]
        val d = d0 + (d1 - d0) * t
        return if (d <= 1e-4f) Float.POSITIVE_INFINITY else 1f / d
    }

    fun mount(index: Int) {
        val n = fNumber
        lensIndex = index
        rig.select(lens)
        // Keep the nearest f-number the new lens has; focus back on the subject.
        aperturePos = lens.apertureStops.withIndex().minBy { kotlin.math.abs(it.value - n) }.index.toFloat()
        focusPos = Float.NaN
        centreMovements()
        // An effect filter can't go on the back of a fisheye or mirror lens.
        if (!lens.fits(filter)) filterIndex = 0
        save()
    }

    fun screwOn(index: Int) {
        filterIndex = if (lens.fits(lensFilters[index])) index else 0
        save()
    }

    fun centreMovements() { tiltX = 0f; tiltY = 0f; shiftX = 0f; shiftY = 0f }

    /**
     * Rocks or shifts the lens by a drag on the finder ([dx], [dy] as fractions
     * of the finder: a full width is the lens's whole travel), as far as it goes.
     */
    /**
     * A tilt (or whacked) lens steered round the focus point: a finger circling it turns the
     * plane of focus (the sharp band turns with it), moving out from it tilts more, in less.
     * [dAngle] radians the finger went round (screen, y down), [dRadius] how far out it moved
     * (fractions of the finder's width), [fingerAngle] where it is, for starting from flat.
     */
    fun steerTilt(dAngle: Float, dRadius: Float, fingerAngle: Float) {
        val m = lens.movement as? Movement.Tilt ?: return
        // the plane's direction on the screen: (tiltX, -tiltY) (Movements.tiltDioptres)
        var mag = kotlin.math.hypot(tiltX, tiltY)
        var ang = if (mag > 0.05f) kotlin.math.atan2(-tiltY, tiltX) else fingerAngle
        ang += dAngle
        mag = (mag + dRadius * m.maxDegrees * 2.5f).coerceIn(0f, m.maxDegrees)
        tiltX = mag * kotlin.math.cos(ang)
        tiltY = -mag * kotlin.math.sin(ang)
    }

    /** Pinched: the sharp band wider (less tilt) or narrower (more), its angle kept. */
    fun scaleTilt(f: Float) {
        val m = lens.movement as? Movement.Tilt ?: return
        val mag = kotlin.math.hypot(tiltX, tiltY)
        if (mag < 0.05f) return
        val k = ((mag * f).coerceIn(0.1f, m.maxDegrees)) / mag
        tiltX *= k; tiltY *= k
    }

    fun moveLens(dx: Float, dy: Float) {
        when (val m = lens.movement) {
            is Movement.Tilt -> {
                // Drag right: the plane of focus swings across; drag down: it tips toward the ground.
                tiltX = (tiltX + dx * m.maxDegrees * 2f)
                tiltY = (tiltY - dy * m.maxDegrees * 2f)
                val r = kotlin.math.hypot(tiltX, tiltY)
                if (r > m.maxDegrees) { tiltX *= m.maxDegrees / r; tiltY *= m.maxDegrees / r }
            }
            is Movement.Shift -> {
                // Drag up: rise (the camera looks up without tipping: verticals stay straight).
                shiftX = (shiftX + dx * m.maxMM * 2f).coerceIn(-m.maxMM, m.maxMM)
                shiftY = (shiftY - dy * m.maxMM * 2f).coerceIn(-m.maxMM, m.maxMM)
            }
            Movement.None -> {}
        }
    }

    fun loadRoll(stock: Int, pushStops: Int) {
        stockIndex = stock; push = pushStops; framesShot = 0; counterShows = 0; rollNumber++
        save()
    }

    fun tapFocus(fx: Float, fy: Float) {
        focusX = fx.coerceIn(0f, 1f); focusY = fy.coerceIn(0f, 1f)
        // Collimating, the ring is the true distance being entered: a tap only measures.
        if (collimating) { rig.manualFocusOff(); rig.focusAt(focusX, focusY); return }
        _focusPos.floatValue = Float.NaN          // the ring follows the subject again
        rig.focusAt(focusX, focusY)
    }

    fun save() {
        prefs.edit().putInt("lens", lensIndex).putFloat("aperture", aperturePos).putFloat("focus", focusPos)
            .putInt("stock", stockIndex).putFloat("shutter", shutterPos).putInt("filter", filterIndex).putInt("push", push).putInt("frames", framesShot).putInt("roll", rollNumber).apply()
    }

    fun shoot(onShot: () -> Unit = {}) {
        if (busy) { message = "still taking the last frame…"; return }
        if (framesLeft <= 0) { message = "roll finished: load film"; return }
        busy = true
        message = null
        val frame = framesShot + 1
        val lens = lens; val n = fNumber; val fm = focusM; val stock = stock; val push = push
        val fx = focusX; val fy = focusY
        val tilt = floatArrayOf(tiltX, tiltY); val shift = floatArrayOf(shiftX, shiftY)
        val filter = filter
        // the exposure at the shutter: the hero frame's metered brightness against the settings
        val stops = (rig.lastEV ?: rig.sceneEV.value)?.let { exposureStops(it) } ?: 0f
        val recip = reciprocity(shutterSeconds)
        val moved = when (lens.movement) {
            is Movement.Tilt -> if (tiltX != 0f || tiltY != 0f) " · tilt %.0f°".format(kotlin.math.hypot(tiltX, tiltY)) else ""
            is Movement.Shift -> if (shiftX != 0f || shiftY != 0f) " · shift %.0f/%.0f mm".format(shiftX, shiftY) else ""
            else -> ""
        }
        val withFilter = if (filter.isNone) "" else " + ${filter.name}"
        val entry = DarkroomEntry(frame, "Roll $rollNumber · frame $frame · ${lens.name} f/%.1f$moved$withFilter · ${stock.name}".format(n),
            rollNumber, stock.name, stock.blackAndWhite)
        entry.marked = isMarked(rollNumber, frame)
        viewModelScope.launch {
            try {
                val name = "R%03d_F%02d_".format(rollNumber, frame) + SimpleDateFormat("HHmmss", Locale.US).format(Date())
                val framing = rig.framing.value ?: error("no camera for this lens")
                val exp = rig.shoot(File(getApplication<Application>().getExternalFilesDir("negatives"), ""), name)
                framesShot = frame; save()
                // the lens's own reading in this very frame (on autofocus: where it focused for it)
                val lockRaw = if (fm == null) exp.rawFocusD?.takeIf { it > 0.02f } else null
                val heroCam = framing.camera.id
                // The rangefinder sweep while the camera is still held as it was: distances at a few
                // well-textured points of this very frame, kept only if the camera didn't turn.
                sweeping = true
                steadiness.start()
                val pts = runCatching { Sweep.points(exp.jpeg, framing.crop, n = 4) }.getOrDefault(emptyList())
                var swept = runCatching { rig.sweep(pts) }.getOrDefault(emptyList())
                // then the ultra-wide's view of the lights round the picture, still held steady
                runCatching { rig.grabLightMap(File(getApplication<Application>().getExternalFilesDir("negatives"), ""), name) }
                steadiness.stop()
                // allowed drift: a twelfth of the lens's field of view (its long side)
                val fov = Math.toDegrees(2 * kotlin.math.atan(18.0 / lens.focalMM)).toFloat()
                if (steadiness.degrees > fov / 12f) {
                    Trace.w("sweep discarded: the camera drifted %.2f degrees (allowed %.2f)".format(steadiness.degrees, fov / 12f))
                    swept = emptyList()
                } else Trace.i("sweep kept: drift %.2f degrees (allowed %.2f)".format(steadiness.degrees, fov / 12f))
                sweeping = false
                // Parallax: two guided side frames, the main camera lent to ARCore meanwhile.
                if (parallaxOn && parallaxAvailable) {
                    val subjectM = fm?.takeIf { it.isFinite() } ?: exp.cameraFocusD?.takeIf { it > 0.05f }?.let { 1f / it } ?: 2f
                    val pc = app.relens.parallax.ParallaxCapture(getApplication(),
                        File(getApplication<Application>().getExternalFilesDir("negatives"), "$name.par"), subjectM, { rig.deviceRotation }) { onParallaxFire() }
                    rig.release()
                    parallax = pc
                    val ok = runCatching { pc.run() }.onFailure { Trace.e("parallax failed", it) }.getOrDefault(false)
                    parallax = null
                    rig.reacquire()
                    if (!ok) message = "parallax not finished: this frame uses single-shot depth"
                    else if (!parallaxIntroSeen) dismissParallaxIntro()
                }
                counterShows = framesShot           // the lever's stroke turns the counter (with its sound)
                onShot()
                busy = false
                entry.devStart = System.currentTimeMillis(); entry.devSet = devEstimate
                darkroom.add(0, entry)
                if (!developer.modelFile.exists()) {
                    entry.status = "no depth models yet: download them from the camera screen"; return@launch
                }
                val p = developer.develop(DevelopJob(exp.jpeg, exp.dng, fx, fy, exp.cameraFocusD, lens, n, fm, stock, push, frame, tilt, shift,
                    filter = filter, crop = framing.crop, focalOverLong = framing.camera.focalMM / framing.camera.sensorLongMM,
                    // the sweep's distances, measured on this very frame
                    afSamples = swept, exposureStops = stops, saveDng = dngOut, reciprocity = recip,
                    note = org.json.JSONObject().put("roll", entry.roll).put("frame", entry.frame).put("stock", entry.stock)
                        .put("bw", entry.bw).put("label", entry.label).toString()))
                // every autofocus frame teaches the focus calibration: ARCore's measure fully, Metric3D's a third
                if (lockRaw != null) when {
                    p.arFocusD != null -> learnFocus(heroCam, lockRaw, p.arFocusD, 1f)
                    p.metricFocusD != null -> learnFocus(heroCam, lockRaw, p.metricFocusD, 0.33f)
                }
                entry.preview = p.preview
                // the darkroom timer is wound to what developments have been taking lately
                devEstimate = (0.6f * devEstimate + 0.4f * (System.currentTimeMillis() - entry.devStart) / 1000f).coerceIn(5f, 300f)
                prefs.edit().putFloat("devS", devEstimate).apply()
                if (p.uri != null) {
                    entry.uri = p.uri; entry.dngUri = p.dngUri
                    // the camera's own RAW too, if asked for; then the app keeps nothing of this frame
                    if (rawOut) exp.dng?.takeIf { it.exists() }?.let { raw ->
                        runCatching { developer.publish(raw, p.file.nameWithoutExtension + "_RAW.dng", "image/x-adobe-dng") }
                            .onFailure { Trace.w("raw not saved to the gallery", it) }
                    }
                    clearNegative(name)
                } else {
                    // not in the gallery: the app keeps the print, and a sidecar to find it after a restart
                    entry.file = p.file
                    runCatching {
                        File(p.file.parentFile, p.file.nameWithoutExtension + ".json").writeText(org.json.JSONObject()
                            .put("roll", entry.roll).put("frame", entry.frame).put("stock", entry.stock).put("bw", entry.bw)
                            .put("label", entry.label).toString())
                    }
                }
                entry.status = if (p.uri != null) "in Pictures/Vintage Snap · ${p.timings}" else "saved in the app · ${p.timings}"
                entry.done = true
            } catch (t: Throwable) {
                busy = false
                Trace.e("frame failed", t)
                message = "frame failed: ${t.message ?: t.javaClass.simpleName}"
                entry.status = "failed: ${t.message}"
                if (entry !in darkroom) darkroom.add(0, entry)
            }
        }
    }
}
