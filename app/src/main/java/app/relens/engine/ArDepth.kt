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
                                  private val disp: FloatArray, private val wt: FloatArray, val match: Float,
                                  private val geo: Geo? = null) {
    /** How the parallax frames sit against the print: for the background plate. */
    private class Geo(val dir: File, val frames: JSONObject, val rot: Int, val dx: Float, val dy: Float,
                      val fullW: Int, val fullH: Int, val crop: Rect, val heroF: Float,
                      val afx: Float, val afy: Float, val acx: Float, val acy: Float)
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

    /**
     * What lies behind the foreground, from the side frames (the disocclusion fill): for each
     * point of the depth map's grid, the background's distance there (the farthest depth
     * near it: behind a foreground edge the background carries on), that point found in the
     * left and right frames (which saw round the foreground from either side), and of the
     * two the one nearer the local background's colour. Colours are matched to the hero
     * picture where all views see the same thing. Returns RGBA floats at dm.w x dm.h (linear,
     * the hero's exposure; alpha: how far behind the background is, 0 where nothing is in
     * front), or null.
     */
    fun plate(dm: DepthMap, dispA: Float, dispB: Float, photo: Bitmap): FloatArray? = runCatching {
        val g = geo ?: return null
        val c = g.frames.getJSONObject("centre")
        val sides = listOf("left", "right").mapNotNull { n -> g.frames.optJSONObject(n)?.let { n to it } }
        if (sides.isEmpty()) return null
        fun pose(j: JSONObject) = FloatArray(16) { j.getJSONArray("pose").getDouble(it).toFloat() }
        val pc = pose(c)
        // each side: centre camera -> side camera, its intrinsics, its picture (linear, small)
        class Side(val T: FloatArray, val k: FloatArray, val w: Int, val h: Int, val px: FloatArray)
        val sv = sides.mapNotNull { (n, j) ->
            val b = BitmapFactory.decodeFile(File(g.dir, "$n.jpg").absolutePath, BitmapFactory.Options().apply { inSampleSize = 2 }) ?: return@mapNotNull null
            val inv = FloatArray(16); android.opengl.Matrix.invertM(inv, 0, pose(j), 0)
            val T = FloatArray(16); android.opengl.Matrix.multiplyMM(T, 0, inv, 0, pc, 0)
            val s = j.getInt("width") / b.width.toFloat()
            val foc = j.getJSONArray("focal"); val pp = j.getJSONArray("principal")
            val k = floatArrayOf(foc.getDouble(0).toFloat() / s, foc.getDouble(1).toFloat() / s, pp.getDouble(0).toFloat() / s, pp.getDouble(1).toFloat() / s)
            val ip = IntArray(b.width * b.height); b.getPixels(ip, 0, b.width, 0, 0, b.width, b.height)
            val lin = FloatArray(ip.size * 3)
            for (i in ip.indices) { val q = ip[i]
                lin[i * 3] = srgbToLin(((q shr 16) and 255) / 255f); lin[i * 3 + 1] = srgbToLin(((q shr 8) and 255) / 255f); lin[i * 3 + 2] = srgbToLin((q and 255) / 255f) }
            Side(T, k, b.width, b.height, lin).also { b.recycle() }
        }
        if (sv.isEmpty()) return null
        val w = dm.w; val h = dm.h
        // metric disparity, and the background's: the smallest disparity (farthest) nearby,
        // as wide as the largest blur could reach (a twentieth of the frame)
        val disp = FloatArray(w * h) { maxOf(dispA + dispB * dm.data[it], 1e-3f) }
        val rad = maxOf(2, w / 20)
        val tmp = FloatArray(w * h); val far = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) { var m = Float.MAX_VALUE
            for (k in maxOf(0, x - rad)..minOf(w - 1, x + rad)) m = minOf(m, disp[y * w + k]); tmp[y * w + x] = m }
        for (y in 0 until h) for (x in 0 until w) { var m = Float.MAX_VALUE
            for (k in maxOf(0, y - rad)..minOf(h - 1, y + rad)) m = minOf(m, tmp[k * w + x]); far[y * w + x] = m }
        // the hero picture, linear, at the grid
        val ps = Bitmap.createScaledBitmap(photo, w, h, true)
        val hp = IntArray(w * h); ps.getPixels(hp, 0, w, 0, 0, w, h); if (ps !== photo) ps.recycle()
        val hero = FloatArray(w * h * 3) { i -> val q = hp[i / 3]; srgbToLin(((q shr (16 - 8 * (i % 3))) and 255) / 255f) }

        val pIn = FloatArray(4); val pOut = FloatArray(4)
        fun look(s: Side, fx: Float, fy: Float, z: Float, out: FloatArray): Boolean {
            // print fraction -> upright-normalised -> the centre camera's sensor -> a point at z
            val ux = (g.crop.left + fx * g.crop.width() - g.fullW / 2f) / g.heroF - g.dx
            val uy = (g.crop.top + fy * g.crop.height() - g.fullH / 2f) / g.heroF - g.dy
            val (sx, sy) = when (g.rot) { 90 -> Pair(uy, -ux); 180 -> Pair(-ux, -uy); 270 -> Pair(-uy, ux); else -> Pair(ux, uy) }
            pIn[0] = sx * z; pIn[1] = -sy * z; pIn[2] = -z; pIn[3] = 1f
            android.opengl.Matrix.multiplyMV(pOut, 0, s.T, 0, pIn, 0)
            val d = -pOut[2]; if (d < 0.05f) return false
            val u = s.k[2] + s.k[0] * pOut[0] / d; val v = s.k[3] - s.k[1] * pOut[1] / d
            val xi = u.toInt(); val yi = v.toInt()
            if (xi < 0 || yi < 0 || xi >= s.w || yi >= s.h) return false
            val o = (yi * s.w + xi) * 3
            out[0] = s.px[o]; out[1] = s.px[o + 1]; out[2] = s.px[o + 2]
            return true
        }
        // colour: each side's gain against the hero where nothing is in front (own depth = background)
        val gains = sv.map { s ->
            val sh = DoubleArray(3); val ss = DoubleArray(3); val v = FloatArray(3)
            for (y in 0 until h step 4) for (x in 0 until w step 4) {
                val i = y * w + x
                if (disp[i] > far[i] * 1.05f) continue
                if (!look(s, (x + 0.5f) / w, (y + 0.5f) / h, 1f / disp[i], v)) continue
                for (ch in 0..2) { sh[ch] += hero[i * 3 + ch]; ss[ch] += v[ch] }
            }
            FloatArray(3) { ch -> if (ss[ch] > 1e-6) (sh[ch] / ss[ch]).toFloat().coerceIn(0.2f, 5f) else 1f }
        }
        val out = FloatArray(w * h * 4)
        val a = FloatArray(3); val b = FloatArray(3)
        var filled = 0
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val behind = 1f - far[i] / disp[i]                    // 0: this is the background
            if (behind < 0.12f) {
                for (ch in 0..2) out[i * 4 + ch] = hero[i * 3 + ch]; out[i * 4 + 3] = 0f; continue
            }
            // the local background's colour: the hero's pixels nearby that are background
            var br = 0f; var bg = 0f; var bb = 0f; var bn = 0f
            for (dy in -rad..rad step maxOf(1, rad / 3)) for (dx in -rad..rad step maxOf(1, rad / 3)) {
                val xx = x + dx; val yy = y + dy
                if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue
                val j = yy * w + xx
                if (disp[j] > far[i] * 1.08f) continue
                br += hero[j * 3]; bg += hero[j * 3 + 1]; bb += hero[j * 3 + 2]; bn++
            }
            val z = 1f / far[i]
            if (bn == 0f) { for (ch in 0..2) out[i * 4 + ch] = hero[i * 3 + ch]; out[i * 4 + 3] = 0f; continue }
            val lb0 = br / bn; val lb1 = bg / bn; val lb2 = bb / bn
            // A side's sample counts only if it plainly looks like the background here and not
            // like the foreground in front (an edge seen a centimetre off still shows the
            // foreground): otherwise the blur keeps its own guess.
            var bestErr = Float.MAX_VALUE; var got = false
            for ((si, s) in sv.withIndex()) {
                if (!look(s, (x + 0.5f) / w, (y + 0.5f) / h, z, a)) continue
                for (ch in 0..2) a[ch] *= gains[si][ch]
                val dB = kotlin.math.abs(a[0] - lb0) + kotlin.math.abs(a[1] - lb1) + kotlin.math.abs(a[2] - lb2)
                val dF = kotlin.math.abs(a[0] - hero[i * 3]) + kotlin.math.abs(a[1] - hero[i * 3 + 1]) + kotlin.math.abs(a[2] - hero[i * 3 + 2])
                if (dF < dB * 1.5f + 0.03f) continue
                if (dB < bestErr) { bestErr = dB; got = true; b[0] = a[0]; b[1] = a[1]; b[2] = a[2] }
            }
            if (!got) { out[i * 4] = lb0; out[i * 4 + 1] = lb1; out[i * 4 + 2] = lb2; out[i * 4 + 3] = 0f; continue }
            for (ch in 0..2) out[i * 4 + ch] = b[ch]
            out[i * 4 + 3] = 0.75f * smoothstep(0.12f, 0.3f, behind)
            filled++
        }
        // smooth it (it's seen behind blur, and the side frames are a little off): two box passes
        val r2 = 3
        val sm = FloatArray(w * h * 4)
        for (pass in 0..1) {
            val src = if (pass == 0) out else sm; val dst = if (pass == 0) sm else out
            for (y in 0 until h) for (x in 0 until w) {
                var acc0 = 0f; var acc1 = 0f; var acc2 = 0f; var acc3 = 0f; var n = 0
                for (k in -r2..r2) {
                    val xx = if (pass == 0) (x + k).coerceIn(0, w - 1) else x
                    val yy = if (pass == 0) y else (y + k).coerceIn(0, h - 1)
                    val j = (yy * w + xx) * 4
                    acc0 += src[j]; acc1 += src[j + 1]; acc2 += src[j + 2]; acc3 += src[j + 3]; n++
                }
                val o = (y * w + x) * 4
                dst[o] = acc0 / n; dst[o + 1] = acc1 / n; dst[o + 2] = acc2 / n; dst[o + 3] = acc3 / n
            }
        }
        Trace.i("background plate: %dx%d, %d points filled from the side frames (gains L %s, R %s)".format(w, h, filled,
            gains.getOrNull(0)?.joinToString("/") { "%.2f".format(it) } ?: "-", gains.getOrNull(1)?.joinToString("/") { "%.2f".format(it) } ?: "-"))
        out
    }.onFailure { Trace.w("background plate failed", it) }.getOrNull()

    private fun smoothstep(a: Float, b: Float, x: Float): Float { val t = ((x - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3 - 2 * t) }
    private fun srgbToLin(c: Float) = if (c <= 0.04045f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()

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
            ArDepth(fxs.toFloatArray(), fys.toFloatArray(), ds.toFloatArray(), ws.toFloatArray(), best.score,
                Geo(dir, frames, best.rot, best.dx, best.dy, fullW, fullH, crop, heroF, afx, afy, acx, acy))
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
