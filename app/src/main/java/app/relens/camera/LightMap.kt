package app.relens.camera

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CaptureResult
import android.media.Image
import org.json.JSONObject
import java.io.File

/**
 * The ultra-wide's frame from the moment of the hero shot (the logical camera
 * gives both in one request): saved as a JPEG with a sidecar of what mapping
 * it needs (its 36 mm-side focal length, orientation, and both cameras'
 * exposures, so its lights' energies can be put in the hero's terms).
 */
object LightMap {
    fun save(img: Image, jpeg: File, json: File, uw: PhoneCamera, orientation: Int, uwResult: CaptureResult?, heroResult: CaptureResult?) {
        val w = img.width; val h = img.height
        // YUV_420_888 -> NV21
        val nv21 = ByteArray(w * h * 3 / 2)
        val y = img.planes[0]; val u = img.planes[1]; val v = img.planes[2]
        var o = 0
        for (row in 0 until h) {
            val yb = y.buffer
            for (col in 0 until w) nv21[o++] = yb.get(row * y.rowStride + col * y.pixelStride)
        }
        for (row in 0 until h / 2) for (col in 0 until w / 2) {
            nv21[o++] = v.buffer.get(row * v.rowStride + col * v.pixelStride)
            nv21[o++] = u.buffer.get(row * u.rowStride + col * u.pixelStride)
        }
        jpeg.outputStream().use { YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), 90, it) }
        fun expo(r: CaptureResult?): Double? {
            val t = r?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return null
            val iso = r.get(CaptureResult.SENSOR_SENSITIVITY) ?: return null
            val n = r.get(CaptureResult.LENS_APERTURE) ?: 2f
            return t / 1e9 * iso / (n * n)
        }
        val j = JSONObject()
        j.put("f36", uw.f36.toDouble()); j.put("orientation", orientation)
        expo(uwResult)?.let { j.put("exposure", it) }
        expo(heroResult)?.let { j.put("heroExposure", it) }
        json.writeText(j.toString())
    }
}
