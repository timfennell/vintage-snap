package app.relens.optics

import kotlin.math.sqrt

/**
 * A manual-focus 35 mm lens, as in Vintage Click's Lens.swift: the aperture's
 * shape, mechanical vignetting (cat's eyes), soap-bubble rims, spherical
 * aberration, fall-off, colour fringing, field curvature (swirl), movements,
 * flare and leaks. The catalogue is generated: LensCatalogue.kt.
 */
data class Lens(
    val name: String,
    val inspiredBy: String,
    val focalMM: Float,
    val maxAperture: Float,
    val minAperture: Float,
    val blades: Int,
    val bladeRotation: Float,
    val catsEye: Float,
    val soapBubble: Float,
    val vignette: Float,
    val minFocusM: Float,
    val lateralCA: Float = 0.0005f,
    val axialCA: Float = 0f,
    val projection: Projection = Projection.RECTILINEAR,
    val obstruction: Float = 0f,
    val sphericalAberration: Float = 0f,
    val squeeze: Float = 1f,
    val curvatureRadial: Float = 0f,
    val curvatureTangential: Float = 0f,
    val movement: Movement = Movement.None,
    val maxMagnification: Float = 0f,
    val breathing: Float = 1f,
    val rearFilter: Boolean = false,
    val streak: Float = 0f,
    val flareBoost: Float = 1f,
    val leak: Float = 0f,
    val pinholeMM: Float = 0f,
    val collection: String = "",
) {
    val isFisheye get() = projection != Projection.RECTILINEAR
    val isPinhole get() = pinholeMM > 0
    /** A pinhole's blur spot on film, mm: its shadow and its Airy disc in quadrature. */
    val pinholeSpotMM: Float get() {
        val airy = 2.44f * 0.00055f * focalMM / maxOf(pinholeMM, 1e-3f)
        return sqrt(pinholeMM * pinholeMM + airy * airy)
    }

    /** The clicks on the aperture ring: full stops, plus the wide-open value. */
    val apertureStops: List<Float> get() {
        val full = listOf(1.0f, 1.4f, 2f, 2.8f, 4f, 5.6f, 8f, 11f, 16f, 22f, 32f)
        return listOf(maxAperture) + full.filter { it > maxAperture * 1.02f && it <= minAperture * 1.02f }
    }

    /** The focus ring's scale marks, metres, from the closest focus out (infinity last). */
    val focusMarks: List<Float> get() {
        val marks = listOf(0.2f, 0.25f, 0.3f, 0.35f, 0.45f, 0.5f, 0.7f, 1f, 1.5f, 2f, 3f, 5f, 10f, Float.POSITIVE_INFINITY)
        return listOf(minFocusM) + marks.filter { it > minFocusM * 1.05f }
    }
}

enum class Projection { RECTILINEAR, FISHEYE, CIRCULAR_FISHEYE }

/** What the lens can do besides focus: tilt its focus plane (degrees) or shift its axis (mm). */
sealed class Movement {
    data object None : Movement()
    data class Tilt(val maxDegrees: Float) : Movement()
    data class Shift(val maxMM: Float) : Movement()
}

data class LensCollection(val name: String, val about: String, val lenses: List<Lens>)
