package app.relens.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.graphics.Bitmap
import android.graphics.Matrix
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** A depth map from the network: relative disparity (near = large), normalised 0...1. */
class DepthMap(val w: Int, val h: Int, val data: FloatArray) {
    /** Median disparity in a small window round (fx, fy), fractions of the frame. */
    fun medianAround(fx: Float, fy: Float, radius: Float = 0.04f): Float {
        val cx = (fx * w).toInt(); val cy = (fy * h).toInt()
        val rx = maxOf(1, (radius * w).toInt()); val ry = maxOf(1, (radius * h).toInt())
        val v = ArrayList<Float>()
        for (y in maxOf(0, cy - ry) until minOf(h, cy + ry + 1))
            for (x in maxOf(0, cx - rx) until minOf(w, cx + rx + 1)) v.add(data[y * w + x])
        if (v.isEmpty()) return 0.5f
        v.sort(); return v[v.size / 2]
    }

    /** The same map turned a quarter turn clockwise (a landscape inference back to portrait). */
    fun rotatedCw(): DepthMap {
        val out = FloatArray(w * h)
        // new (x', y') = (h - 1 - y, x); new width = h
        for (y in 0 until h) for (x in 0 until w) out[x * h + (h - 1 - y)] = data[y * w + x]
        return DepthMap(h, w, out)
    }
}

private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

/** RGB pixels as floats, NCHW or NHWC, ImageNet-normalised or plain 0...1. */
fun toTensor(bmp: Bitmap, nchw: Boolean, imagenet: Boolean): FloatBuffer {
    val w = bmp.width; val h = bmp.height
    val px = IntArray(w * h).also { bmp.getPixels(it, 0, w, 0, 0, w, h) }
    val out = ByteBuffer.allocateDirect(w * h * 3 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    val plane = w * h
    for (i in 0 until plane) {
        val c = px[i]
        for (ch in 0 until 3) {
            var v = ((c shr (16 - 8 * ch)) and 0xff) / 255f
            if (imagenet) v = (v - MEAN[ch]) / STD[ch]
            if (nchw) out.put(ch * plane + i, v) else out.put(i * 3 + ch, v)
        }
    }
    out.position(0)
    return out
}

/** Robust 0...1 range (1st to 99th percentile) so a stray pixel doesn't squash the rest. */
fun normalise(raw: FloatArray, w: Int, h: Int): DepthMap {
    val s = raw.copyOf().also { it.sort() }
    val lo = s[(s.size * 0.01).toInt()]; val hi = s[(s.size * 0.99).toInt()]
    val k = 1f / maxOf(hi - lo, 1e-6f)
    return DepthMap(w, h, FloatArray(raw.size) { ((raw[it] - lo) * k).coerceIn(0f, 1f) })
}

fun depthToBitmap(m: DepthMap): Bitmap {
    val px = IntArray(m.w * m.h) { val v = (m.data[it] * 255).toInt().coerceIn(0, 255); (0xff shl 24) or (v shl 16) or (v shl 8) or v }
    return Bitmap.createBitmap(px, m.w, m.h, Bitmap.Config.ARGB_8888)
}

/**
 * Depth Anything V2 Small through ONNX Runtime's CPU execution provider (the
 * spike's winner: 0.8-1.1 s on the Snapdragon 7s Gen 3). The model is the
 * static 392 x 518 export; a portrait picture is turned to landscape for it
 * and the depth turned back. The session is kept between photos.
 */
/**
 * Depth Anything V2 Small through ONNX Runtime. A model exported at a fixed size runs at that
 * size; the original (free height and width) runs at [size] (height, width of the landscape
 * input, multiples of 14): 770 x 1036 for prints, a small one for the live finder.
 */
class DepthEstimator(private val model: File, size: Pair<Int, Int> = Pair(770, 1036)) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = OrtSession.SessionOptions().use { o ->
        o.setIntraOpNumThreads(4)
        o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        env.createSession(model.absolutePath, o)
    }
    private val inName = session.inputNames.first()
    private val inH: Int; private val inW: Int
    init {
        val shape = (session.inputInfo[inName]!!.info as TensorInfo).shape
        inH = shape[2].toInt().takeIf { it > 0 } ?: size.first
        inW = shape[3].toInt().takeIf { it > 0 } ?: size.second
    }

    class Result(val depth: DepthMap, val seen: Bitmap)

    /** Depth for an upright picture; also the small upright picture the network saw (the JBU guide). */
    fun estimate(upright: Bitmap): Result {
        val portrait = upright.height > upright.width
        val seenUpright = Bitmap.createScaledBitmap(upright,
            if (portrait) inH else inW, if (portrait) inW else inH, true)
        val input = if (portrait)
            Bitmap.createBitmap(seenUpright, 0, 0, seenUpright.width, seenUpright.height, Matrix().apply { postRotate(-90f) }, false)
        else seenUpright
        val fb = toTensor(input, nchw = true, imagenet = true)
        val raw = OnnxTensor.createTensor(env, fb, longArrayOf(1, 3, inH.toLong(), inW.toLong())).use { t ->
            session.run(mapOf(inName to t)).use { r ->
                val o = r[0] as OnnxTensor
                val b = o.floatBuffer
                FloatArray(b.remaining()).also { b.get(it) }
            }
        }
        var map = normalise(raw, inW, inH)
        if (portrait) map = map.rotatedCw()
        return Result(map, seenUpright)
    }

    override fun close() = session.close()
}
