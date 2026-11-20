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
class DarkroomEntry(val frame: Int, val label: String) {
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
    /** Save a linear DNG of each relensed picture (no film) beside the print. */
    var dngOut by mutableStateOf(prefs.getBoolean("dng", true)); private set
    fun toggleDng() { dngOut = !dngOut; prefs.edit().putBoolean("dng", dngOut).apply() }

    /** Parallax capture: after each shot, ARCore guides two side frames for measured depth. */
    val parallaxAvailable: Boolean = runCatching {
        com.google.ar.core.ArCoreApk.getInstance().checkAvailability(app).isSupported
    }.getOrDefault(false)
    var parallaxOn by mutableStateOf(prefs.getBoolean("parallax", false)); private set
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
    private fun reciprocity(t: Float) = if (t <= 0.25f) 0f else (if (stock.blackAndWhite) 0.5f else 0.25f) * kotlin.math.log2(t / 0.25f)
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

    init {
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
        stockIndex = stock; push = pushStops; framesShot = 0; rollNumber++
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
        val moved = when (lens.movement) {
            is Movement.Tilt -> if (tiltX != 0f || tiltY != 0f) " · tilt %.0f°".format(kotlin.math.hypot(tiltX, tiltY)) else ""
            is Movement.Shift -> if (shiftX != 0f || shiftY != 0f) " · shift %.0f/%.0f mm".format(shiftX, shiftY) else ""
            else -> ""
        }
        val withFilter = if (filter.isNone) "" else " + ${filter.name}"
        val entry = DarkroomEntry(frame, "Roll $rollNumber · frame $frame · ${lens.name} f/%.1f$moved$withFilter · ${stock.name}".format(n))
        viewModelScope.launch {
            try {
                val name = "R%03d_F%02d_".format(rollNumber, frame) + SimpleDateFormat("HHmmss", Locale.US).format(Date())
                val framing = rig.framing.value ?: error("no camera for this lens")
                val exp = rig.shoot(File(getApplication<Application>().getExternalFilesDir("negatives"), ""), name)
                framesShot = frame; save()
                // the lens's own reading at the shutter's autofocus lock (the sweep's locks come after)
                val lockRaw = if (fm == null) rig.lastLockRaw else null
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
                }
                onShot()
                busy = false
                darkroom.add(0, entry)
                if (!developer.modelFile.exists()) {
                    entry.status = "no depth model: run scripts/install.sh"; return@launch
                }
                val p = developer.develop(DevelopJob(exp.jpeg, exp.dng, fx, fy, exp.cameraFocusD, lens, n, fm, stock, push, frame, tilt, shift,
                    filter = filter, crop = framing.crop, focalOverLong = framing.camera.focalMM / framing.camera.sensorLongMM,
                    // the sweep's distances, measured on this very frame
                    afSamples = swept, exposureStops = stops, saveDng = dngOut))
                if (lockRaw != null && p.arFocusD != null) learnFocus(heroCam, lockRaw, p.arFocusD)
                entry.preview = p.preview; entry.original = p.original
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
