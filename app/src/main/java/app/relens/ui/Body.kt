package app.relens.ui

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
class DarkroomEntry(val frame: Int, val label: String, val roll: Int = 0, val stock: String = "", val bw: Boolean = false) {
    /** Ringed on the light table as a keeper (in red china marker). */
    var marked by mutableStateOf(false)
    /** The print's file (for the full view). */
    var file: File? = null
    var preview by mutableStateOf<Bitmap?>(null)
    var original by mutableStateOf<Bitmap?>(null)
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

    fun startLiveDepth() {
        if (liveJob?.isActive == true || !liveModel.exists()) return
        liveJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            val est = runCatching { app.relens.engine.DepthEstimator(liveModel, Pair(168, 224)) }
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
                    kotlinx.coroutines.delay(40)
                }
            } finally { est.close() }
        }
    }
    fun stopLiveDepth() { liveJob?.cancel(); liveJob = null }

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
        val pts = (prefs.getString("afsamples_$id", "") ?: "").split(";").mapNotNull { e ->
            e.split(":").mapNotNull { it.toFloatOrNull() }.takeIf { it.size == 2 }?.get(1) }
        return Pair(pts.size, pts.size >= 3 && (pts.max() - pts.min()) >= 0.25f)
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
    private fun learnFocus(id: String, raw: Float, trueD: Float) {
        if (trueD !in 0.05f..6f || raw <= 0f) return
        val key = "afsamples_$id"
        val pts = (prefs.getString(key, "") ?: "").split(";").mapNotNull { e ->
            e.split(":").mapNotNull { it.toFloatOrNull() }.takeIf { it.size == 2 }?.let { Pair(it[0], it[1]) }
        }.toMutableList()
        pts += Pair(raw, trueD)
        while (pts.size > 10) pts.removeAt(0)
        prefs.edit().putString(key, pts.joinToString(";") { "${it.first}:${it.second}" }).apply()
        focusLearnTick++
        val ts = pts.map { it.second }
        var k = rig.afCalibration[id]?.second ?: 1f
        if (pts.size >= 2 && (ts.max() - ts.min()) >= 0.25f) {
            val mt = ts.average(); val mr = pts.map { it.first }.average()
            var sxy = 0.0; var sxx = 0.0
            for ((r, t) in pts) { sxy += (t - mt) * (r - mr); sxx += (t - mt) * (t - mt) }
            val fitK = (sxy / sxx).toFloat()
            if (fitK in 0.5f..3.5f) k = fitK
        }
        val o = pts.map { it.first - k * it.second }.average().toFloat()
        val old = rig.afCalibration[id]
        rig.afCalibration[id] = Pair(o, k)
        prefs.edit().putString("afcal_$id", "$o,$k").apply()
        Trace.i("focus calibration from parallax, camera $id: read %.3f D at a measured %.2f m; now reading = %.3f + %.3f x true (%d pairs; was %s)".format(
            raw, 1 / trueD, o, k, pts.size, old?.let { "%.3f + %.3f".format(it.first, it.second) } ?: "none"))
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
        Trace.i("collimated camera $id: reading = %.3f + %.3f x true (from ${collimation.size} targets)".format(o, k))
        val got = "read %.2f m raw, set %.2f m".format(1 / maxOf(raw, 1e-3f), m)
        collimationNote = if (collimation.size >= 2) "$got · camera $id collimated (offset %.2f, scale %.2f)".format(o, k)
            else "$got · now a second target at another distance".format(o)
        if (collimation.size >= 2) { collimating = false; focusPos = Float.NaN }   // back to autofocus, corrected
    }

    /**
     * The light table after a restart: the recent prints (the last 48) with their roll and
     * frame from the sidecar each print gets, or for older prints from the negative shot just
     * before it with that frame number. Small previews, decoded off the main thread.
     */
    private fun loadLightTable() = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        val app = getApplication<Application>()
        val prints = app.getExternalFilesDir("prints")?.listFiles { f -> f.name.startsWith("VINTAGESNAP_") && f.name.endsWith(".jpg") }
            ?.sortedBy { it.lastModified() }?.takeLast(48) ?: return@launch
        val negs = app.getExternalFilesDir("negatives")?.listFiles { f -> f.name.matches(Regex("R\\d+_F\\d+_\\d+\\.jpg")) }?.toList() ?: emptyList()
        val loaded = ArrayList<DarkroomEntry>()
        for (f in prints) runCatching {
            val side = File(f.parentFile, f.nameWithoutExtension + ".json")
            val e: DarkroomEntry = if (side.exists()) {
                val j = org.json.JSONObject(side.readText())
                DarkroomEntry(j.getInt("frame"), j.optString("label"), j.getInt("roll"), j.optString("stock"), j.optBoolean("bw"))
            } else {
                val frame = f.nameWithoutExtension.substringAfterLast("_").toIntOrNull() ?: return@runCatching
                if (frame == 0) return@runCatching                         // a test development
                val neg = negs.filter { it.name.contains("_F%02d_".format(frame)) && it.lastModified() <= f.lastModified() + 1000 }
                    .maxByOrNull { it.lastModified() }?.takeIf { f.lastModified() - it.lastModified() < 10 * 60_000 } ?: return@runCatching
                val roll = neg.name.substring(1, neg.name.indexOf('_')).toInt()
                val desc = runCatching { androidx.exifinterface.media.ExifInterface(f).getAttribute(androidx.exifinterface.media.ExifInterface.TAG_IMAGE_DESCRIPTION) }.getOrNull() ?: ""
                val stockName = desc.substringAfterLast(" on ", "").trim()
                val stock = filmStocks.firstOrNull { it.name == stockName }
                DarkroomEntry(frame, "Roll $roll · frame $frame · $desc", roll, stockName, stock?.blackAndWhite ?: false)
            }
            val o = android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }
            e.preview = android.graphics.BitmapFactory.decodeFile(f.absolutePath, o)
            e.status = "saved"; e.done = true; e.file = f
            e.marked = isMarked(e.roll, e.frame)
            loaded += e
        }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            // newest first, as the session adds them; keep any already developed this session
            val have = darkroom.map { it.roll to it.frame }.toSet()
            darkroom.addAll(loaded.filter { (it.roll to it.frame) !in have }.reversed())
        }
        Trace.i("light table: ${loaded.size} prints from before")
    }

    init {
        loadLightTable()
        // The first focus learning (2026-11-20) paired a stale lock reading with each shot:
        // forget its pairs and put camera 0 back where Tim's collimation left it.
        if (!prefs.getBoolean("aflearn_v2", false)) {
            val e = prefs.edit()
            if (prefs.contains("afsamples_0")) e.putString("afcal_0", "0.902,1.579")
            for (id in listOf("0", "2", "3")) e.remove("afsamples_$id")
            e.putBoolean("aflearn_v2", true).apply()
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
                darkroom.add(0, entry)
                if (!developer.modelFile.exists()) {
                    entry.status = "no depth models yet: download them from the camera screen"; return@launch
                }
                val p = developer.develop(DevelopJob(exp.jpeg, exp.dng, fx, fy, exp.cameraFocusD, lens, n, fm, stock, push, frame, tilt, shift,
                    filter = filter, crop = framing.crop, focalOverLong = framing.camera.focalMM / framing.camera.sensorLongMM,
                    // the sweep's distances, measured on this very frame
                    afSamples = swept, exposureStops = stops, saveDng = dngOut, reciprocity = recip))
                if (lockRaw != null && p.arFocusD != null) learnFocus(heroCam, lockRaw, p.arFocusD)
                entry.preview = p.preview; entry.original = p.original; entry.file = p.file
                // the light table finds it again after a restart
                runCatching {
                    File(p.file.parentFile, p.file.nameWithoutExtension + ".json").writeText(org.json.JSONObject()
                        .put("roll", entry.roll).put("frame", entry.frame).put("stock", entry.stock).put("bw", entry.bw)
                        .put("label", entry.label).toString())
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
