package app.relens.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES30
import android.opengl.GLES31
import android.opengl.Matrix
import app.relens.Trace
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * Stereo depth from a parallax capture's three frames (stereo.comp: a plane sweep
 * with ARCore's poses, no rectification needed), at a third of ARCore's 1920 x 1080.
 * Returns the confident points as (u, v) in the centre frame's own pixels with their
 * disparity (1/m) and a weight, for ArDepth to lay on the print with ARCore's.
 */
object StereoDepth {
    class Points(val u: FloatArray, val v: FloatArray, val disp: FloatArray, val weight: FloatArray, val w: Int, val h: Int)

    private const val SCALE = 3

    fun compute(g: Gl, dir: File, frames: JSONObject, nearM: Float, farM: Float, debugPng: File? = null): Points? {
        val c = frames.optJSONObject("centre") ?: return null
        val l = frames.optJSONObject("left") ?: return null
        val r = frames.optJSONObject("right") ?: return null
        val fw = c.getInt("width"); val fh = c.getInt("height")
        val w = fw / SCALE; val h = fh / SCALE
        fun grey(name: String): Int? {
            val b = BitmapFactory.decodeFile(File(dir, "$name.jpg").absolutePath) ?: return null
            val s = Bitmap.createScaledBitmap(b, w, h, true); if (s !== b) b.recycle()
            val px = IntArray(w * h); s.getPixels(px, 0, w, 0, 0, w, h); s.recycle()
            val f = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            for (q in px) f.put((0.299f * ((q shr 16) and 255) + 0.587f * ((q shr 8) and 255) + 0.114f * (q and 255)) / 255f)
            f.position(0)
            val t = g.texture(w, h, GLES30.GL_R16F, linear = true)
            g.upload(t, w, h, GLES30.GL_RED, GLES30.GL_FLOAT, f)
            return t
        }
        val tc = grey("centre") ?: return null
        val tl = grey("left") ?: run { g.deleteTextures(tc); return null }
        val tr = grey("right") ?: run { g.deleteTextures(tc, tl); return null }
        fun pose(j: JSONObject) = FloatArray(16) { j.getJSONArray("pose").getDouble(it).toFloat() }
        fun toSide(side: JSONObject): FloatArray {
            val inv = FloatArray(16); Matrix.invertM(inv, 0, pose(side), 0)
            return FloatArray(16).also { Matrix.multiplyMM(it, 0, inv, 0, pose(c), 0) }
        }
        val foc = c.getJSONArray("focal"); val pp = c.getJSONArray("principal")
        val k = floatArrayOf(foc.getDouble(0).toFloat() / SCALE, foc.getDouble(1).toFloat() / SCALE,
            pp.getDouble(0).toFloat() / SCALE, pp.getDouble(1).toFloat() / SCALE)
        val out = g.texture(w, h, GLES30.GL_RGBA32F, linear = false)
        val planes = 96
        val t0 = System.nanoTime()
        val p = g.use("stereo")
        g.sampler(p, "uC", 0, tc); g.sampler(p, "uL", 1, tl); g.sampler(p, "uR", 2, tr)
        g.image(0, out, GLES30.GL_RGBA32F)
        GLES31.glUniformMatrix4fv(GLES31.glGetUniformLocation(p, "uTL"), 1, false, toSide(l), 0)
        GLES31.glUniformMatrix4fv(GLES31.glGetUniformLocation(p, "uTR"), 1, false, toSide(r), 0)
        g.u4f(p, "uK", k[0], k[1], k[2], k[3])
        g.u2f(p, "uInv", 1f / nearM, 1f / farM)
        g.u1i(p, "uPlanes", planes)
        // in strips: a long dispatch loses the GL context on Adreno
        val rows = 32
        var y = 0
        while (y < h) { g.u2i(p, "uOffset", 0, y); g.dispatch(w, min(rows, h - y)); GLES30.glFinish(); y += rows }
        val res = g.readFloat(out, w, h)
        g.deleteTextures(tc, tl, tr, out)
        val ms = (System.nanoTime() - t0) / 1_000_000

        // Keep the confident ones: a clear match (cost), unlike its rivals (unique), on texture.
        val us = ArrayList<Float>(); val vs = ArrayList<Float>(); val ds = ArrayList<Float>(); val ws = ArrayList<Float>()
        var kept = 0
        for (yy in 0 until h step 2) for (xx in 0 until w step 2) {
            val i = (yy * w + xx) * 4
            val inv = res[i]; val cost = res[i + 1]; val uniq = res[i + 2]; val con = res[i + 3]
            if (inv <= 0f || cost > 0.25f || uniq > 0.8f || con < 0.02f) continue
            us += (xx + 0.5f) * SCALE; vs += (yy + 0.5f) * SCALE; ds += inv
            ws += (1f - cost / 0.25f) * (1f - uniq) * 0.5f
            kept++
        }
        Trace.i("stereo: %dx%d, %d planes %.2f-%.1f m, %d ms; %d confident points".format(w, h, planes, nearM, farM, ms, kept))
        debugPng?.let { f ->
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val px = IntArray(w * h) { i ->
                val inv = res[i * 4]; val ok = inv > 0f && res[i * 4 + 1] <= 0.25f && res[i * 4 + 2] <= 0.8f && res[i * 4 + 3] >= 0.02f
                val z = if (inv > 0f) 1f / inv else 99f
                val g8 = ((1f - kotlin.math.ln(max(z, 0.25f) / 0.25f) / kotlin.math.ln(32f)).coerceIn(0f, 1f) * 255).toInt()
                if (ok) (0xFF shl 24) or (g8 shl 16) or (g8 shl 8) or g8 else (0xFF shl 24) or ((g8 / 3) shl 16)
            }
            bmp.setPixels(px, 0, w, 0, 0, w, h)
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
        }
        return Points(us.toFloatArray(), vs.toFloatArray(), ds.toFloatArray(), ws.toFloatArray(), fw, fh)
    }
}
