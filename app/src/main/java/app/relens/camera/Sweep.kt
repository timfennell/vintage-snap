package app.relens.camera

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/** Picking the sweep's points and watching that the camera stays still while it runs. */
object Sweep {
    /**
     * Up to [n] well-textured points spread over the lens's crop of the hero JPEG: a tiny decode,
     * local contrast on a 6 x 6 grid of cells, the best point of each of the busiest cells, kept
     * apart. Autofocus can't lock on a blank wall, so texture matters more than position.
     */
    fun points(jpeg: File, crop: Float, n: Int = 5): List<Pair<Float, Float>> {
        val o = BitmapFactory.Options().apply { inSampleSize = 16 }
        var b = BitmapFactory.decodeFile(jpeg.absolutePath, o) ?: return emptyList()
        val deg = when (ExifInterface(jpeg).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f; ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f; else -> 0f }
        if (deg != 0f) b = android.graphics.Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg) }, true)
        val w = b.width; val h = b.height
        val px = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }
        val l = FloatArray(w * h) { val c = px[it]; (0.3f * ((c shr 16) and 255) + 0.59f * ((c shr 8) and 255) + 0.11f * (c and 255)) / 255f }
        // the lens's 3:2 crop of the upright frame
        val long = maxOf(w, h) / crop; val short = long * 2f / 3f
        val cw = if (w >= h) long else short; val ch = if (w >= h) short else long
        val x0 = (w - cw) / 2; val y0 = (h - ch) / 2
        data class Cand(val fx: Float, val fy: Float, val score: Float)
        val cands = ArrayList<Cand>()
        val g = 6
        for (j in 0 until g) for (i in 0 until g) {
            var best = 0f; var bx = 0; var by = 0
            val cx0 = (x0 + cw * i / g).toInt(); val cx1 = (x0 + cw * (i + 1) / g).toInt()
            val cy0 = (y0 + ch * j / g).toInt(); val cy1 = (y0 + ch * (j + 1) / g).toInt()
            for (y in maxOf(1, cy0) until minOf(h - 1, cy1)) for (x in maxOf(1, cx0) until minOf(w - 1, cx1)) {
                val k = y * w + x
                val gx = l[k + 1] - l[k - 1]; val gy = l[k + w] - l[k - w]
                val s = sqrt(gx * gx + gy * gy)
                if (s > best) { best = s; bx = x; by = y }
            }
            cands.add(Cand((bx - x0) / cw, (by - y0) / ch, best))
        }
        val picked = ArrayList<Cand>()
        for (c in cands.sortedByDescending { it.score }) {
            if (c.score < 0.04f) break
            if (picked.all { abs(it.fx - c.fx) + abs(it.fy - c.fy) > 0.3f }) picked.add(c)
            if (picked.size == n) break
        }
        return picked.map { Pair(it.fx.coerceIn(0.05f, 0.95f), it.fy.coerceIn(0.05f, 0.95f)) }
    }

    /** Integrates the gyroscope while a sweep runs: how far (degrees) the camera turned. */
    class Steadiness(context: Context) : SensorEventListener {
        private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        private var last = 0L
        @Volatile var degrees = 0f; private set
        fun start() { degrees = 0f; last = 0L; sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) } }
        fun stop() = sm.unregisterListener(this)
        override fun onSensorChanged(e: SensorEvent) {
            if (last != 0L) {
                val dt = (e.timestamp - last) / 1e9f
                degrees += sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]) * dt * 57.2958f
            }
            last = e.timestamp
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }
}
