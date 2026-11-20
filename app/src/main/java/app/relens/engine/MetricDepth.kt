package app.relens.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Metric3D v2 (ViT-Small, BSD-2) gives depth in metres for a camera with a
 * 1000 px focal length; real metres = output x (focal px / 1000). Relens uses
 * it only to calibrate Depth Anything's relative depth (which has the cleaner
 * edges): disparity_metric ~ a * disparity_relative + b, fitted robustly.
 */
class MetricDepth(model: File) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = OrtSession.SessionOptions().use { o ->
        o.setIntraOpNumThreads(4)
        o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        env.createSession(model.absolutePath, o)
    }

    /**
     * Metric depth (metres) for an upright picture, at about 518 px on the long
     * side. [focalOverLong]: the camera's focal length over its sensor's long side.
     */
    fun estimate(upright: Bitmap, focalOverLong: Float): DepthMap {
        val portrait = upright.height > upright.width
        val h = if (portrait) 518 else 392; val w = if (portrait) 392 else 518
        val bmp = Bitmap.createScaledBitmap(upright, w, h, true)
        val px = IntArray(w * h).also { bmp.getPixels(it, 0, w, 0, 0, w, h) }
        val fb = ByteBuffer.allocateDirect(w * h * 3 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val plane = w * h
        for (i in 0 until plane) {
            val c = px[i]   // raw 0...255 RGB, NCHW (the export normalises inside)
            fb.put(i, ((c shr 16) and 0xff).toFloat())
            fb.put(plane + i, ((c shr 8) and 0xff).toFloat())
            fb.put(2 * plane + i, (c and 0xff).toFloat())
        }
        fb.position(0)
        val focalPx = focalOverLong * maxOf(w, h)
        return OnnxTensor.createTensor(env, fb, longArrayOf(1, 3, h.toLong(), w.toLong())).use { t ->
            session.run(mapOf("pixel_values" to t), setOf("predicted_depth")).use { r ->
                val o = r[0] as OnnxTensor
                val shape = o.info.shape
                val oh = shape[shape.size - 2].toInt(); val ow = shape[shape.size - 1].toInt()
                val b = o.floatBuffer
                val d = FloatArray(b.remaining()).also { b.get(it) }
                for (i in d.indices) d[i] *= focalPx / 1000f
                DepthMap(ow, oh, d)
            }
        }
    }

    override fun close() = session.close()

    companion object {
        /**
         * Fits disparity_metric = a * rel + b over the frame (both maps sampled on a
         * grid). Weighted by 1 / disparity^2, i.e. in relative terms (about log
         * depth), so a far wall counts as much as a near table; two rounds of
         * outlier rejection. Returns (a, b).
         */
        fun fit(rel: DepthMap, metric: DepthMap): FloatArray {
            val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
            val n = 48
            for (j in 0 until n) for (i in 0 until n) {
                val fx = (i + 0.5f) / n; val fy = (j + 0.5f) / n
                val r = rel.data[(fy * rel.h).toInt().coerceAtMost(rel.h - 1) * rel.w + (fx * rel.w).toInt().coerceAtMost(rel.w - 1)]
                val z = metric.data[(fy * metric.h).toInt().coerceAtMost(metric.h - 1) * metric.w + (fx * metric.w).toInt().coerceAtMost(metric.w - 1)]
                if (z > 0.05f && z < 80f) { xs.add(r); ys.add(1f / z) }
            }
            var keep = BooleanArray(xs.size) { true }
            var a = 1f; var b = 0f
            repeat(3) {
                var sw = 0.0; var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
                for (i in xs.indices) if (keep[i]) {
                    val w = 1.0 / (ys[i] * ys[i]).coerceAtLeast(0.0025f)
                    sw += w; sx += w * xs[i]; sy += w * ys[i]; sxx += w * xs[i] * xs[i]; sxy += w * xs[i] * ys[i]
                }
                val den = sw * sxx - sx * sx
                if (abs(den) < 1e-12) return floatArrayOf(a, b)
                a = ((sw * sxy - sx * sy) / den).toFloat(); b = ((sy - a * sx) / sw).toFloat()
                val res = xs.indices.map { abs(ys[it] - (a * xs[it] + b)) / ys[it] }
                val med = res.sorted()[res.size / 2]
                keep = BooleanArray(xs.size) { res[it] <= 3f * maxOf(med, 0.02f) }
            }
            app.relens.Trace.i("depth calibration: disparity = %.3f * rel + %.3f (rel 1 at %.2f m, rel 0 at %.2f m)".format(
                a, b, 1 / maxOf(a + b, 1e-3f), 1 / maxOf(b, 1e-3f)))
            return floatArrayOf(a, b)
        }
    }
}
