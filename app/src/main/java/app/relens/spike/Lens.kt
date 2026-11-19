package app.relens.spike

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A manual-focus 35 mm lens: the subset of Vintage Click's Lens.swift the spike
 * needs (aperture shape, cat's eye, soap bubble, fall-off, lateral colour).
 * The full catalogue, movements, flare and leaks come over in milestone 3.
 */
data class Lens(
    val name: String,
    val focalMM: Float,
    val maxAperture: Float,
    val blades: Int,            // 0 = round
    val bladeRotation: Float,   // radians
    val catsEye: Float,         // barrel clipping offset at the frame corner, in max-aperture radii
    val soapBubble: Float,      // extra weight at the aperture rim
    val vignette: Float,        // fall-off at the corner, wide open
    val lateralCA: Float = 0.0005f,
    val obstruction: Float = 0f,
)

val spikeLenses = listOf(
    Lens("Ohi Kor 50mm f/1.4", 50f, 1.4f, 7, 0.1f, 0.45f, 0.15f, 0.40f, lateralCA = 0.0005f),
    Lens("Ohi Kor 85mm f/1.4", 85f, 1.4f, 9, 0f, 0.5f, 0.1f, 0.4f, lateralCA = 0.0005f),
    Lens("Krasnogorsk Solnce 58mm f/2", 58f, 2f, 0, 0f, 0.9f, 0.2f, 0.45f, lateralCA = 0.0009f),
    Lens("Ohi Reflex-Kor 500mm f/8", 500f, 8f, 0, 0f, 0.1f, 0.2f, 0.3f, lateralCA = 0.0001f, obstruction = 0.45f),
)

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

/** Signed circle of confusion per dioptre of defocus, in pixels of radius, for an image [widthPx] wide (36 mm film). */
fun cocRadiusPxPerDioptre(lens: Lens, fNumber: Float, focusM: Float, widthPx: Int): Float {
    val f = lens.focalMM / 1000f
    val s = maxOf(focusM, f * 1.01f)
    // c = (f/N) * f/(1 - f/s) * (1/s - 1/z): film-plane diameter in metres per dioptre.
    val perDioptre = (f / fNumber) * f / (1 - f / s)
    return abs(perDioptre) / 0.036f * widthPx * 0.5f
}
