package app.relens.engine

import app.relens.optics.Lens
import kotlin.math.atan
import kotlin.math.tan

/** Tilt and shift, as Vintage Click's CameraBody does them, made for a photo. */
object Movements {
    private const val HALF_DIAGONAL_MM = 21.63f

    /**
     * A tilted lens tilts the plane of focus: the image surface leans off the
     * film by tan(tilt) x the half-diagonal at the corner, and d mm off the film
     * moves focus by d / f^2 dioptres (CameraBody.lensParams). Returns the focus
     * change at the frame's half-diagonal, (across, down) to match the shader's field q.
     */
    fun tiltDioptres(lens: Lens, tiltDeg: FloatArray): FloatArray {
        val f = lens.focalMM / 1000f
        val perMM = 0.001f / (f * f)
        val rad = Math.PI.toFloat() / 180f
        return floatArrayOf(
            tan(tiltDeg[0] * rad) * HALF_DIAGONAL_MM * perMM,
            -tan(tiltDeg[1] * rad) * HALF_DIAGONAL_MM * perMM,
        )
    }

    /**
     * A shift lens moves its axis, which on a photo is a change of perspective:
     * shifting up s mm is looking up by atan(s / f) without tilting the film, so
     * verticals stay parallel. Returns the picture-plane turn (radians) about the
     * horizontal axis (rise / fall) and the vertical axis (sideways).
     */
    fun keystone(lens: Lens, shiftMM: FloatArray): FloatArray = floatArrayOf(
        atan(shiftMM[1] / lens.focalMM),
        atan(-shiftMM[0] / lens.focalMM),
    )
}
