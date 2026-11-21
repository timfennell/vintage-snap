package app.relens.camera

import android.media.Image
import app.relens.Trace
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * A burst of RAW frames merged into one negative, in the Bayer domain, before demosaicing:
 * the noise averages down (four frames: about half the noise, a stop cleaner shadows) while
 * the picture stays the first frame's.
 *
 *  - Each extra frame is aligned to the first by a whole shift found on a small grey copy
 *    (2x2 photosites binned, then a quarter of that), coarse then fine; the shift is kept to
 *    an even number of photosites so the colour pattern stays in register.
 *  - In tiles of 32 photosites, an extra frame that disagrees with the first by more than its
 *    noise could explain (something moved: a hand, a cat) is left out there, so nothing ghosts.
 *  - The sum is written back as the negative. When the white level times the number of frames
 *    still fits 16 bits (a 10-bit sensor's does), the sum is kept as it is, with the black and
 *    white levels scaled to match, so no precision is lost to rounding.
 */
object RawMerge {
    /** The photosites of a RAW_SENSOR image, packed row after row (a copy: the image can go back). */
    fun copy(img: Image): ShortArray {
        val w = img.width; val h = img.height
        val p = img.planes[0]
        val src = p.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val out = ShortArray(w * h)
        val row = ShortArray(w)
        for (y in 0 until h) {
            src.position(y * p.rowStride)
            src.asShortBuffer().get(row, 0, w)
            System.arraycopy(row, 0, out, y * w, w)
        }
        return out
    }

    /** Merges [extras] into the negative at [bin] / [json]; returns how many frames went in. */
    fun merge(bin: File, json: File, extras: List<ShortArray>, w: Int, h: Int): Int {
        if (extras.isEmpty()) return 1
        val t0 = System.currentTimeMillis()
        val meta = JSONObject(json.readText())
        val bytes = ByteBuffer.wrap(bin.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val ref = ShortArray(w * h).also { bytes.get(it) }
        val blackArr = meta.getJSONArray("black")
        val black = (0 until 4).sumOf { blackArr.getDouble(it) }.toFloat() / 4f
        val white = meta.getDouble("white").toFloat()
        val range = maxOf(white - black, 1f)

        val bw = w / 2; val bh = h / 2
        fun binned(a: ShortArray): FloatArray {
            val o = FloatArray(bw * bh)
            for (y in 0 until bh) { val r0 = 2 * y * w; val r1 = r0 + w
                for (x in 0 until bw) {
                    val i = 2 * x
                    o[y * bw + x] = ((a[r0 + i].toInt() and 0xFFFF) + (a[r0 + i + 1].toInt() and 0xFFFF) +
                        (a[r1 + i].toInt() and 0xFFFF) + (a[r1 + i + 1].toInt() and 0xFFFF) - 4 * black)
                }
            }
            return o
        }
        val cw = bw / 4; val ch = bh / 4
        fun coarse(b: FloatArray): FloatArray {
            val o = FloatArray(cw * ch)
            for (y in 0 until ch) for (x in 0 until cw) {
                var s = 0f
                for (dy in 0 until 4) for (dx in 0 until 4) s += b[(4 * y + dy) * bw + 4 * x + dx]
                o[y * cw + x] = s
            }
            return o
        }
        fun sad(a: FloatArray, b: FloatArray, aw: Int, ah: Int, sx: Int, sy: Int, step: Int, margin: Int): Float {
            var s = 0f; var n = 0
            var y = margin
            while (y < ah - margin) {
                var x = margin
                while (x < aw - margin) {
                    s += abs(a[y * aw + x] - b[(y + sy) * aw + (x + sx)]); n++
                    x += step
                }
                y += step
            }
            return s / maxOf(n, 1)
        }
        val refB = binned(ref); val refC = coarse(refB)
        val sum = IntArray(w * h) { ref[it].toInt() and 0xFFFF }
        var used = 1
        val tile = 32
        for ((k, ex) in extras.withIndex()) {
            val exB = binned(ex); val exC = coarse(exB)
            // where the frame sits against the first: coarse (up to 12 cells, ~96 photosites), then fine
            var bx = 0; var by = 0; var best = Float.MAX_VALUE
            for (sy in -12..12) for (sx in -12..12) {
                val v = sad(refC, exC, cw, ch, sx, sy, 2, 13); if (v < best) { best = v; bx = sx; by = sy }
            }
            var fx = bx * 4; var fy = by * 4; best = Float.MAX_VALUE
            val cx = fx; val cy = fy
            for (sy in cy - 4..cy + 4) for (sx in cx - 4..cx + 4) {
                val v = sad(refB, exB, bw, bh, sx, sy, 6, 60); if (v < best) { best = v; fx = sx; fy = sy }
            }
            val ox = 2 * fx; val oy = 2 * fy                       // even: the colour pattern stays in register
            // tile by tile: in, unless it disagrees beyond its noise (it moved there)
            var kept = 0; var tiles = 0
            for (ty in 0 until h step tile) for (tx in 0 until w step tile) {
                tiles++
                val tx1 = minOf(tx + tile, w); val ty1 = minOf(ty + tile, h)
                val inside = tx + ox >= 0 && ty + oy >= 0 && tx1 + ox <= w && ty1 + oy <= h
                var d = 0f; var m = 0f; var n = 0
                if (inside) for (y in ty / 2 until ty1 / 2) for (x in tx / 2 until tx1 / 2) {
                    d += abs(refB[y * bw + x] - exB[(y + fy) * bw + (x + fx)]); m += refB[y * bw + x]; n++
                }
                val meanD = if (n > 0) d / n else Float.MAX_VALUE
                val meanL = if (n > 0) m / n else 0f
                // shot noise grows with the light (~sqrt), plus a floor; four photosites are binned
                val allowed = 3f * kotlin.math.sqrt(maxOf(meanL, 0f) * 2f) + 0.004f * range * 4f + 0.04f * meanL
                val take = inside && meanD <= allowed
                if (take) kept++
                for (y in ty until minOf(ty + tile, h)) {
                    val row = y * w
                    for (x in tx until minOf(tx + tile, w)) {
                        sum[row + x] += if (take) (ex[(y + oy) * w + (x + ox)].toInt() and 0xFFFF) else (ref[row + x].toInt() and 0xFFFF)
                    }
                }
            }
            used++
            Trace.i("raw merge: frame ${k + 2} shifted %d,%d photosites, %d of %d tiles kept".format(ox, oy, kept, tiles))
        }
        // Back to 16 bits: the sum as it is when it fits (levels scaled), else the mean.
        val keepSum = white * used <= 65535f
        val out = ByteBuffer.allocate(w * h * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (v in sum) out.putShort((if (keepSum) v else (v + used / 2) / used).coerceAtMost(65535).toShort())
        bin.writeBytes(out.array())
        if (keepSum) {
            meta.put("black", JSONArray((0 until 4).map { blackArr.getDouble(it) * used }))
            meta.put("white", white.toDouble() * used)
        }
        meta.put("mergedFrames", used)
        json.writeText(meta.toString())
        Trace.i("raw merge: $used frames in ${System.currentTimeMillis() - t0} ms (${if (keepSum) "sum kept" else "mean"})")
        return used
    }
}
