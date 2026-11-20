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
 * Blur radius in pixels per dioptre of defocus, for an image [widthPx] wide
 * (36 mm film): c = (f/N) f / (1 - f/s) per dioptre (CameraBody / coc_px).
 * A pinhole blurs by its spot everywhere instead (see [pinholeRadiusPx]).
 */
fun cocRadiusPxPerDioptre(lens: Lens, fNumber: Float, focusM: Float, widthPx: Int): Float {
    if (lens.isPinhole) return 0f
    val f = lens.focalMM / 1000f
    val s = maxOf(focusM, f * 1.01f)
    val perDioptre = (f / fNumber) * f / (1 - f / s)
    return abs(perDioptre) / 0.036f * widthPx * 0.5f
}

/** A pinhole's blur radius, px: its spot on 36 mm film. */
fun pinholeRadiusPx(lens: Lens, widthPx: Int): Float = lens.pinholeSpotMM / 36f * widthPx * 0.5f
