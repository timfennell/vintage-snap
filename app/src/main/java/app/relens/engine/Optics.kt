package app.relens.engine

import app.relens.optics.Lens
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The gather pattern: [n] points of equal area in rings over the unit disc, the
 * outer ring on the rim (CameraBody.ringCells), pushed out to the blades'
 * polygon (CameraBody.cellCentres). Returned as vec4 (x, y, 0, 0) for a uniform block.
 */
fun gatherPattern(n: Int, lens: Lens): FloatArray {
    val rings = maxOf(1, Math.round(sqrt(n / PI)).toInt())
    val radii = (0 until rings).map { (it + 0.5f) / rings }
    val total = radii.sum()
    val counts = radii.map { maxOf(1, Math.round(n * it / total)) }.toMutableList()
    counts[rings - 1] = maxOf(1, counts[rings - 1] + n - counts.sum())
    val out = ArrayList<FloatArray>()
    for ((i, r) in radii.withIndex()) {
        val a0 = i * 2.39996323f
        for (j in 0 until counts[i]) {
            val a = a0 + 2 * PI.toFloat() * j / counts[i]
            var x = r * cos(a); var y = r * sin(a)
            if (lens.blades >= 3) {
                val b = lens.blades.toFloat()
                val seg = 2 * PI.toFloat() / b
                val th = atan2(y, x)
                val aa = ((th - lens.bladeRotation) % seg + seg) % seg - seg / 2
                val k = cos(PI.toFloat() / b) / cos(aa)
                x *= k; y *= k
            }
            out.add(floatArrayOf(x, y))
        }
    }
    val f = FloatArray(n * 4)
    for (k in 0 until n) { f[4 * k] = out[k][0]; f[4 * k + 1] = out[k][1] }
    return f
}

/**
 * Blur radius in pixels per dioptre of defocus, for a frame [longPx] on its
 * long side (the 36 mm side of the film).
 *
 * Equivalent framing: the photo keeps the phone's framing, so the lens is
 * rendered as if the photographer stood where it gives this framing. Its
 * background blur relative to the frame is then (f/N) x f_view / s, the lens's
 * own pupil (f/N) with the phone's 35 mm-equivalent focal length [viewFocalMM]:
 * an 85 mm f/1.4 blurs a distant background exactly as much as a real one
 * would at this framing (CameraBody / coc_px use f in place of f_view).
 * A pinhole blurs by its spot everywhere instead (see [pinholeRadiusPx]).
 */
fun cocRadiusPxPerDioptre(lens: Lens, fNumber: Float, focusM: Float, longPx: Int, viewFocalMM: Float): Float {
    if (lens.isPinhole) return 0f
    val pupil = lens.focalMM / 1000f / fNumber
    val fv = viewFocalMM / 1000f
    val s = maxOf(focusM, fv * 1.01f)
    val perDioptre = pupil * fv / (1 - fv / s)
    return abs(perDioptre) / 0.036f * longPx * 0.5f
}

/** A pinhole's blur radius, px: its spot on 36 mm film, scaled like the lens (equivalent framing). */
fun pinholeRadiusPx(lens: Lens, longPx: Int, viewFocalMM: Float): Float =
    lens.pinholeSpotMM * (viewFocalMM / lens.focalMM) / 36f * longPx * 0.5f
