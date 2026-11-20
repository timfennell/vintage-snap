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
import app.relens.camera.Rig
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
            rig.manualFocus(focusM?.let { if (it.isInfinite()) 0f else 1f / it })
        }
    var stockIndex by mutableIntStateOf(prefs.getInt("stock", 1)); private set
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
    /** The last thing that went wrong, shown on the body until the next shot. */
    var message by mutableStateOf<String?>(null)
    val darkroom = mutableStateListOf<DarkroomEntry>()

    val lens get() = lensBag[lensIndex]

    init {
        if (rig.framingFor(lensBag[lensIndex]) == null)
            lensIndex = lensBag.indexOfFirst { it.name == "Ohi Kor 50mm f/1.4" }.coerceAtLeast(0)
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
        _focusPos.floatValue = Float.NaN          // the ring follows the subject again
        rig.focusAt(focusX, focusY)
    }

    fun save() {
        prefs.edit().putInt("lens", lensIndex).putFloat("aperture", aperturePos).putFloat("focus", focusPos)
            .putInt("stock", stockIndex).putInt("filter", filterIndex).putInt("push", push).putInt("frames", framesShot).putInt("roll", rollNumber).apply()
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
                onShot()
                busy = false
                darkroom.add(0, entry)
                if (!developer.modelFile.exists()) {
                    entry.status = "no depth model: run scripts/install.sh"; return@launch
                }
                val p = developer.develop(DevelopJob(exp.jpeg, exp.dng, fx, fy, exp.cameraFocusD, lens, n, fm, stock, push, frame, tilt, shift,
                    filter = filter, crop = framing.crop, focalOverLong = framing.camera.focalMM / framing.camera.sensorLongMM))
                entry.preview = p.preview; entry.original = p.original
                entry.status = if (p.uri != null) "in Pictures/Relens · ${p.timings}" else "saved in the app · ${p.timings}"
                entry.done = true
            } catch (t: Throwable) {
                busy = false
                android.util.Log.e("Relens", "frame failed", t)
                message = "frame failed: ${t.message ?: t.javaClass.simpleName}"
                entry.status = "failed: ${t.message}"
                if (entry !in darkroom) darkroom.add(0, entry)
            }
        }
    }
}
