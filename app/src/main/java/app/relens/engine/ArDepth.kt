package app.relens.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import app.relens.Trace
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * ARCore's own measured depth from a parallax capture (ParallaxCapture saves it as
 * <negative>.par/centre.depth16: 16-bit millimetres, 160 x 90, in the main camera's
 * sensor orientation, taken from where the hero was shot), brought onto the hero's
 * print and used to make Depth Anything's relative depth metric:
 *
 *  - where the samples land: by angle (ARCore's intrinsics into the hero camera's), at the
 *    quarter turn and small offset where ARCore's centre frame best matches the hero
 *    picture (normalised cross-correlation; no clear match, no ARCore depth);
 *  - a robust line, disparity = a + b x relative, through all of them;
 *  - then what's left over, region by region: a smooth correction (a coarse grid of
 *    weighted residuals) added to the relative depth, so the network keeps its fine edges
 *    and ARCore decides which parts of the scene are near and far.
 */
class ArDepth private constructor(private val fx: FloatArray, private val fy: FloatArray,
                                  private val disp: FloatArray, private val wt: FloatArray, val match: Float) {
    val size get() = fx.size

    /** ARCore's own measurement near (fx, fy) on the print: the median disparity (1/m) of the
     *  samples within [r], or null with fewer than six there. */
    fun disparityAround(fx: Float, fy: Float, r: Float = 0.05f): Float? {
        val v = ArrayList<Float>()
        for (i in 0 until size) {
            val dx = this.fx[i] - fx; val dy = (this.fy[i] - fy) * 1.5f       // the print is 2:3
            if (dx * dx + dy * dy < r * r) v += disp[i]
        }
        if (v.size < 6) return null
        v.sort(); return v[v.size / 2]
    }

    /**
     * Corrects [dm] in place (its data stay relative, so a + b x data is the metric
     * disparity) and returns (b, a), or null when there is too little to go on.
     */
    fun calibrate(dm: DepthMap): FloatArray? {
        if (size < 40) return null
        val rel = FloatArray(size) { sample(dm, fx[it], fy[it]) }
        val ab = fitLine(rel, disp, wt) ?: return null
        val b = ab[0]; val a = ab[1]
        if (b <= 1e-4f) { Trace.w("ARCore depth: no usable slope (b %.4f)".format(b)); return null }
        // The residuals on a coarse grid (portrait: 12 across, 18 down), Gaussian-weighted,
        // shrunk toward nothing where few samples fall.
        val dense = size > 4000
        val gw = if (dense) 24 else 12; val gh = if (dense) 36 else 18
        val acc = FloatArray(gw * gh); val wsum = FloatArray(gw * gh)
        val sigma = if (dense) 1.4f else 1.1f
        for (i in 0 until size) {
            val pred = a + b * rel[i]
            var r = disp[i] - pred
            val lim = 0.6f * maxOf(abs(pred), 0.02f)
            r = r.coerceIn(-lim, lim)
            val gx = fx[i] * gw - 0.5f; val gy = fy[i] * gh - 0.5f
            for (cy in maxOf(0, (gy - 4).toInt())..minOf(gh - 1, (gy + 4).toInt()))
                for (cx in maxOf(0, (gx - 4).toInt())..minOf(gw - 1, (gx + 4).toInt())) {
                    val d2 = (cx - gx) * (cx - gx) + (cy - gy) * (cy - gy)
                    val k = wt[i] * exp(-d2 / (2 * sigma * sigma))
                    acc[cy * gw + cx] += k * r; wsum[cy * gw + cx] += k
                }
        }
        val prior = 0.6f
        val grid = FloatArray(gw * gh) { acc[it] / (wsum[it] + prior) }
        var moved = 0f
        for (y in 0 until dm.h) for (x in 0 until dm.w) {
            val gx = ((x + 0.5f) / dm.w * gw - 0.5f).coerceIn(0f, gw - 1f)
            val gy = ((y + 0.5f) / dm.h * gh - 0.5f).coerceIn(0f, gh - 1f)
            val x0 = gx.toInt().coerceAtMost(gw - 2); val y0 = gy.toInt().coerceAtMost(gh - 2)
            val tx = gx - x0; val ty = gy - y0
            val g = (grid[y0 * gw + x0] * (1 - tx) + grid[y0 * gw + x0 + 1] * tx) * (1 - ty) +
                    (grid[(y0 + 1) * gw + x0] * (1 - tx) + grid[(y0 + 1) * gw + x0 + 1] * tx) * ty
            val i = y * dm.w + x
            dm.data[i] += g / b
            moved = maxOf(moved, abs(g))
        }
        Trace.i("ARCore depth: %d samples (match %.2f): disparity = %.3f x rel + %.3f (rel 1 at %.2f m, rel 0 at %.2f m), local correction up to %.3f /m".format(
            size, match, b, a, 1 / maxOf(a + b, 1e-3f), 1 / maxOf(a, 1e-3f), moved))
        return floatArrayOf(b, a)
    }

    companion object {
        /**
         * Loads the parallax capture beside [jpeg] (NAME.par/), if it has ARCore depth.
         * [photo] is the upright hero picture as printed (the lens's crop of the [fullW] x [fullH]
         * upright frame, at [crop]); [focalOverLong] the hero camera's focal length over its
         * sensor's long side (uncropped).
         */
        fun load(jpeg: File, photo: Bitmap, fullW: Int, fullH: Int, crop: Rect, focalOverLong: Float,
                 gl: Gl? = null, debugDir: File? = null): ArDepth? = runCatching {
            val dir = File(jpeg.parentFile, jpeg.nameWithoutExtension + ".par")
            val metaF = File(dir, "parallax.json")
            if (!metaF.exists()) return null
            val frames = JSONObject(metaF.readText()).getJSONObject("frames")
            val c = frames.getJSONObject("centre")
            val iw = c.getInt("width").toFloat(); val ih = c.getInt("height").toFloat()
            val foc = c.getJSONArray("focal"); val pp = c.getJSONArray("principal")
            val afx = foc.getDouble(0).toFloat(); val afy = foc.getDouble(1).toFloat()
            val acx = pp.getDouble(0).toFloat(); val acy = pp.getDouble(1).toFloat()
            fun mat(j: JSONObject) = FloatArray(16) { j.getJSONArray("pose").getDouble(it).toFloat() }
            val centreInv = FloatArray(16).also { android.opengl.Matrix.invertM(it, 0, mat(c), 0) }

            // The hero camera, upright: focal length in its pixels, centre in the middle.
            val heroF = focalOverLong * maxOf(fullW, fullH)
            val centre = BitmapFactory.decodeFile(File(dir, "centre.jpg").absolutePath)
            // Which quarter turn (and small offset) puts ARCore's view on the hero's: match pictures.
            val best = register(centre, iw, ih, afx, afy, acx, acy, photo, fullW, fullH, crop, heroF)
            centre?.recycle()
            if (best == null || best.score < 0.35f) {
                Trace.w("ARCore depth: its view doesn't match the hero's (best %.2f): not used".format(best?.score ?: 0f)); return null
            }
            val fxs = ArrayList<Float>(); val fys = ArrayList<Float>(); val ds = ArrayList<Float>(); val ws = ArrayList<Float>()
            var total = 0
            val pin = FloatArray(4); val pw = FloatArray(4); val pc = FloatArray(4)
            // Every frame's depth (the sides have it: ARCore needs motion), carried into the
            // centre camera by the poses, then onto the print.
            for (name in frames.keys()) {
                val fj = frames.getJSONObject(name)
                val dj = fj.optJSONObject("depth16") ?: continue
                val df = File(dir, "$name.depth16").takeIf { it.exists() } ?: continue
                val dw = dj.getInt("width"); val dh = dj.getInt("height"); val stride = dj.getInt("rowStride")
                val bytes = ByteBuffer.wrap(df.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
                val conf = File(dir, "$name.conf8").takeIf { it.exists() }?.readBytes()
                val confStride = fj.optJSONObject("conf8")?.optInt("rowStride", dw) ?: dw
                val ffoc = fj.getJSONArray("focal"); val fpp = fj.getJSONArray("principal")
                val ffx = ffoc.getDouble(0).toFloat(); val ffy = ffoc.getDouble(1).toFloat()
                val fcx = fpp.getDouble(0).toFloat(); val fcy = fpp.getDouble(1).toFloat()
                val fw = fj.getInt("width").toFloat(); val fh = fj.getInt("height").toFloat()
                val m = mat(fj)
                val su = fw / dw; val sv = fh / dh
                var used = 0
                for (j in 0 until dh) for (i in 0 until dw) {
                    val mm = bytes.getShort(j * stride + i * 2).toInt() and 0xFFFF
                    if (mm < 150 || mm > 15000) continue
                    total++
                    val cf = conf?.let { (it[j * confStride + i].toInt() and 0xFF) / 255f } ?: 1f
                    if (cf < 0.25f) continue
                    val d = mm / 1000f
                    // the point in this frame's camera (x right, y up, looking down -z), then the world, then the centre's
                    pin[0] = ((i + 0.5f) * su - fcx) / ffx * d; pin[1] = -((j + 0.5f) * sv - fcy) / ffy * d; pin[2] = -d; pin[3] = 1f
                    android.opengl.Matrix.multiplyMV(pw, 0, m, 0, pin, 0)
                    android.opengl.Matrix.multiplyMV(pc, 0, centreInv, 0, pw, 0)
                    val z = -pc[2]
                    if (z < 0.1f) continue
                    val p = toPrint(pc[0] / z, -pc[1] / z, best.rot, best.dx, best.dy, fullW, fullH, crop, heroF) ?: continue
                    fxs += p.first; fys += p.second; ds += 1f / z
                    // nearer measurements are the more precise (stereo error grows with distance squared)
                    ws += cf / (1f + (z / 3f) * (z / 3f))
                    used++
                }
                Trace.i("ARCore depth: $name gives $used points on the print")
            }
            // Stereo from the three frames themselves: far denser than ARCore's own, in the
            // centre frame's pixels, over the distances ARCore saw (with room either side).
            if (gl != null) runCatching {
                val sorted = ds.sorted()
                val nearM = if (sorted.size > 20) (1f / sorted[(sorted.size * 0.98).toInt()] * 0.6f).coerceIn(0.2f, 5f) else 0.3f
                val farM = if (sorted.size > 20) (1f / sorted[(sorted.size * 0.02).toInt()] * 2.5f).coerceIn(nearM * 3f, 60f) else 30f
                StereoDepth.compute(gl, dir, frames, nearM, farM, debugDir?.let { File(it, "last_stereo.png") })
            }.onFailure { Trace.w("stereo failed", it) }.getOrNull()?.let { st ->
                var on = 0
                for (i in st.u.indices) {
                    val p = toPrint((st.u[i] - acx) / afx, (st.v[i] - acy) / afy, best.rot, best.dx, best.dy, fullW, fullH, crop, heroF) ?: continue
                    fxs += p.first; fys += p.second; ds += st.disp[i]; ws += st.weight[i]; on++
                }
                Trace.i("stereo: $on points on the print")
            }
            Trace.i("ARCore depth: turn ${best.rot}°, offset %.3f, %.3f, match %.2f, %d of %d samples on the print".format(
                best.dx, best.dy, best.score, fxs.size, total))
            ArDepth(fxs.toFloatArray(), fys.toFloatArray(), ds.toFloatArray(), ws.toFloatArray(), best.score)
        }.onFailure { Trace.w("ARCore depth not read", it) }.getOrNull()

        private class Reg(val rot: Int, val dx: Float, val dy: Float, val score: Float)

        /** Sensor-normalised (x, y) to upright-normalised, turned [rot] degrees clockwise. */
        private fun turn(x: Float, y: Float, rot: Int): Pair<Float, Float> = when (rot) {
            90 -> Pair(-y, x); 180 -> Pair(-x, -y); 270 -> Pair(y, -x); else -> Pair(x, y)
        }
        private fun unturn(x: Float, y: Float, rot: Int): Pair<Float, Float> = when (rot) {
            90 -> Pair(y, -x); 180 -> Pair(-x, -y); 270 -> Pair(-y, x); else -> Pair(x, y)
        }

        /** Sensor-normalised direction to fractions of the print, or null off it. */
        private fun toPrint(x: Float, y: Float, rot: Int, dx: Float, dy: Float, fullW: Int, fullH: Int, crop: Rect, heroF: Float): Pair<Float, Float>? {
            val (ux, uy) = turn(x, y, rot)
            val px = fullW / 2f + (ux + dx) * heroF; val py = fullH / 2f + (uy + dy) * heroF
            val fx = (px - crop.left) / crop.width(); val fy = (py - crop.top) / crop.height()
            return if (fx in 0f..1f && fy in 0f..1f) Pair(fx, fy) else null
        }

        /** The quarter turn and offset (upright-normalised) that best lays ARCore's centre frame on the print. */
        private fun register(centre: Bitmap?, iw: Float, ih: Float, afx: Float, afy: Float, acx: Float, acy: Float,
                             photo: Bitmap, fullW: Int, fullH: Int, crop: Rect, heroF: Float): Reg? {
            if (centre == null) return null
            val cs = Bitmap.createScaledBitmap(centre, 240, (240 * centre.height / centre.width.toFloat()).toInt().coerceAtLeast(1), true)
            val ps = Bitmap.createScaledBitmap(photo, 96, (96 * photo.height / photo.width.toFloat()).toInt().coerceAtLeast(1), true)
            fun grey(b: Bitmap, x: Float, y: Float): Float? {
                val xi = x.toInt(); val yi = y.toInt()
                if (xi < 0 || yi < 0 || xi >= b.width || yi >= b.height) return null
                val c = b.getPixel(xi, yi)
                return 0.299f * ((c shr 16) and 255) + 0.587f * ((c shr 8) and 255) + 0.114f * (c and 255)
            }
            // The print's grid, and its grey values.
            val n = 24; val m = 36
            val heroG = Array(n * m) { k -> grey(ps, (k % n + 0.5f) / n * ps.width, (k / n + 0.5f) / m * ps.height)!! }
            fun score(rot: Int, dx: Float, dy: Float): Float {
                val a = ArrayList<Float>(); val b = ArrayList<Float>()
                for (k in 0 until n * m) {
                    val fx = (k % n + 0.5f) / n; val fy = (k / n + 0.5f) / m
                    // print fraction -> upright-normalised -> sensor-normalised -> ARCore pixel
                    val ux = (crop.left + fx * crop.width() - fullW / 2f) / heroF - dx
                    val uy = (crop.top + fy * crop.height() - fullH / 2f) / heroF - dy
                    val (sx, sy) = unturn(ux, uy, rot)
                    val u = acx + sx * afx; val v = acy + sy * afy
                    val g = grey(cs, u / iw * cs.width, v / ih * cs.height) ?: continue
                    a += g; b += heroG[k]
                }
                if (a.size < n * m / 3) return -1f
                val ma = a.average().toFloat(); val mb = b.average().toFloat()
                var sab = 0f; var saa = 0f; var sbb = 0f
                for (i in a.indices) { val p = a[i] - ma; val q = b[i] - mb; sab += p * q; saa += p * p; sbb += q * q }
                return sab / sqrt(maxOf(saa * sbb, 1e-6f))
            }
            var best: Reg? = null
            for (rot in intArrayOf(0, 90, 180, 270)) {
                val s = score(rot, 0f, 0f)
                if (best == null || s > best.score) best = Reg(rot, 0f, 0f, s)
            }
            val r = best ?: return null
            // a small offset between the cameras (and how the lens sits): search round the best turn
            var b2 = r
            for (iy in -6..6) for (ix in -6..6) {
                val dx = ix * 0.01f; val dy = iy * 0.01f
                val s = score(r.rot, dx, dy)
                if (s > b2.score) b2 = Reg(r.rot, dx, dy, s)
            }
            cs.recycle(); ps.recycle()
            return b2
        }

        private fun sample(dm: DepthMap, fx: Float, fy: Float): Float =
            dm.data[(fy * dm.h).toInt().coerceIn(0, dm.h - 1) * dm.w + (fx * dm.w).toInt().coerceIn(0, dm.w - 1)]

        /** A weighted line y = b x + a, refitted three times without the outliers; returns (b, a). */
        fun fitLine(xs: FloatArray, ys: FloatArray, ws: FloatArray): FloatArray? {
            var keep = BooleanArray(xs.size) { true }
            var a = 0f; var b = 0f
            repeat(3) {
                var sw = 0.0; var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
                for (i in xs.indices) if (keep[i]) {
                    val w = ws[i].toDouble()
                    sw += w; sx += w * xs[i]; sy += w * ys[i]; sxx += w * xs[i] * xs[i]; sxy += w * xs[i] * ys[i]
                }
                val den = sw * sxx - sx * sx
                if (abs(den) < 1e-12) return null
                b = ((sw * sxy - sx * sy) / den).toFloat(); a = ((sy - b * sx) / sw).toFloat()
                val res = xs.indices.map { abs(ys[it] - (b * xs[it] + a)) / maxOf(ys[it], 0.02f) }
                val med = res.sorted()[res.size / 2]
                keep = BooleanArray(xs.size) { res[it] <= 3f * maxOf(med, 0.03f) }
            }
            return floatArrayOf(b, a)
        }
    }
}
