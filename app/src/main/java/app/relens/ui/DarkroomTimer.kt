package app.relens.ui

import android.graphics.Bitmap
import android.graphics.Canvas as ACanvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * A darkroom timer in the dark, as a photograph of one looks rather than a drawing: luminous
 * green figures 0 to 55, a dot for every second and a bar every five, hand-painted (mottled,
 * the edges a little ragged), glowing into a halo, the black face faintly lit by its own paint,
 * all a touch soft and grainy as a phone sees it in the dark. Two dark metal hands, lumed toward
 * their tips: the short one the minutes left (under a minute: straight up at 0), the long one
 * the seconds, wound to how long a development is expected to take (learnt from the last ones)
 * and counting back down to 0 as the frame develops. A progress bar, the way a darkroom has one.
 *
 * The dial and the hands are rendered once per size into bitmaps (paint texture, blur, halo,
 * grain worked per pixel) and the hands are only turned each frame.
 */
object DarkroomTimer {
    // lume: the paint's green, and the paler green of its brightest cores
    private const val LR = 0.36f; private const val LG = 0.94f; private const val LB = 0.52f

    private class Parts(val n: Int, val dial: Bitmap, val minute: Bitmap, val second: Bitmap)
    private val cache = LinkedHashMap<Int, Parts>()            // a few sizes (the camera corner, the light table)
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /** The rendered parts for a dial [n] pixels across (sizes rounded so zooming doesn't re-render constantly). */
    private fun parts(px: Float): Parts {
        val n = (((px.coerceIn(48f, 480f)) / 32f).toInt() + 1) * 32
        cache[n]?.let { return it }
        if (cache.size >= 3) cache.remove(cache.keys.first())
        return Parts(n, renderDial(n), renderHand(n, 0.58f, 0.075f, 0.10f, 11), renderHand(n, 0.84f, 0.05f, 0.16f, 23)).also { cache[n] = it }
    }

    /**
     * Draws the timer in a circle at [c], radius [r]: [remainingS] seconds left; [setS] what it
     * was wound to (the hands show what is left); [nowMs] for the paint's faint breathing.
     */
    fun DrawScope.drawTimer(c: Offset, r: Float, remainingS: Float, @Suppress("UNUSED_PARAMETER") setS: Float, nowMs: Long) {
        val cv = drawContext.canvas.nativeCanvas
        val p = parts(2f * r)
        val breathe = 0.93f + 0.07f * sin(nowMs / 900.0).toFloat()
        bmpPaint.alpha = (255 * breathe).toInt()
        val dst = android.graphics.RectF(c.x - r, c.y - r, c.x + r, c.y + r)
        cv.drawBitmap(p.dial, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
        val left = remainingS.coerceAtLeast(0f)
        for ((bmp, s) in listOf(p.minute to (left / 60f % 60f), p.second to (left % 60f))) {
            cv.save(); cv.rotate(s / 60f * 360f, c.x, c.y)
            cv.drawBitmap(bmp, null, dst, bmpPaint)
            cv.restore()
        }
    }

    /** Seconds left on the timer for a development started at [startMs], expected to take [setS]. */
    fun remaining(startMs: Long, setS: Float, nowMs: Long) = maxOf(setS - (nowMs - startMs) / 1000f, 0f)

    // ---------------------------------------------------------------- rendering

    /** The paint's coverage, drawn crisp: figures, bars, dots (white on nothing). */
    private fun lumeMask(n: Int, draw: (ACanvas, Paint, Float, Float) -> Unit): FloatArray {
        val b = Bitmap.createBitmap(n, n, Bitmap.Config.ALPHA_8)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
        draw(ACanvas(b), p, n / 2f, n / 2f * 0.96f)
        val bytes = java.nio.ByteBuffer.allocate(n * n); b.copyPixelsToBuffer(bytes); b.recycle()
        val a = bytes.array()
        return FloatArray(n * n) { (a[it].toInt() and 0xFF) / 255f }
    }

    /** Hand-painted: mottled thickness, the edges eaten a little unevenly. */
    private fun paintTexture(a: FloatArray, n: Int, seed: Int) {
        val rng = Random(seed)
        val g = 24; val lat = FloatArray(g * g) { rng.nextFloat() }
        val g2 = 64; val lat2 = FloatArray(g2 * g2) { rng.nextFloat() }
        fun vn(lat: FloatArray, g: Int, x: Float, y: Float): Float {
            val fx = x * (g - 1); val fy = y * (g - 1)
            val ix = fx.toInt().coerceIn(0, g - 2); val iy = fy.toInt().coerceIn(0, g - 2)
            val tx = fx - ix; val ty = fy - iy
            val p = lat[iy * g + ix]; val q = lat[iy * g + ix + 1]; val r = lat[(iy + 1) * g + ix]; val s = lat[(iy + 1) * g + ix + 1]
            return (p + (q - p) * tx) + ((r + (s - r) * tx) - (p + (q - p) * tx)) * ty
        }
        for (y in 0 until n) for (x in 0 until n) {
            val i = y * n + x; val v = a[i]; if (v <= 0f) continue
            val u = x / n.toFloat(); val w = y / n.toFloat()
            val thick = 0.70f + 0.36f * vn(lat, g, u, w) + 0.10f * (vn(lat2, g2, u, w) - 0.5f)
            // edges: coverage below 1 is pushed up or down by the fine noise (ragged, not round)
            val edge = if (v < 0.98f) (v + (vn(lat2, g2, u, w) - 0.5f) * 0.9f).coerceIn(0f, 1f) else v
            a[i] = edge * thick
        }
    }

    /** A separable box blur, three passes (close to a Gaussian) of radius [rad] pixels. */
    private fun blur(src: FloatArray, n: Int, rad: Int): FloatArray {
        if (rad < 1) return src.copyOf()
        var a = src.copyOf(); val t = FloatArray(n * n)
        repeat(3) {
            for (y in 0 until n) { var s = 0f; val row = y * n
                for (x in -rad..rad) s += a[row + x.coerceIn(0, n - 1)]
                for (x in 0 until n) { t[row + x] = s / (2 * rad + 1)
                    s += a[row + (x + rad + 1).coerceAtMost(n - 1)] - a[row + (x - rad).coerceAtLeast(0)] } }
            for (x in 0 until n) { var s = 0f
                for (y in -rad..rad) s += t[y.coerceIn(0, n - 1) * n + x]
                for (y in 0 until n) { a[y * n + x] = s / (2 * rad + 1)
                    s += t[(y + rad + 1).coerceAtMost(n - 1) * n + x] - t[(y - rad).coerceAtLeast(0) * n + x] } }
        }
        return a
    }

    /** Lume (and optionally the face beneath it) to pixels: soft core, halo, colour, grain. */
    private fun compose(n: Int, lume: FloatArray, face: FloatArray?, metal: FloatArray?, seed: Int): Bitmap {
        val soft = blur(lume, n, maxOf(1, n / 420))                 // a little out of focus
        val halo = blur(lume, n, maxOf(2, n / 30))                  // glowing into the dark
        val wide = blur(lume, n, maxOf(3, n / 9))                   // and lighting the face
        val rng = Random(seed)
        val px = IntArray(n * n)
        for (i in 0 until n * n) {
            val core = soft[i]
            var e = core * 1.05f + halo[i] * 0.55f + wide[i] * 0.22f
            val f = face?.get(i) ?: 0f                              // the dial's black, barely there
            val m = metal?.get(i) ?: 0f                             // a hand's dark metal
            // brightest paint goes paler (the sensor saturates), the rest stays green
            val pale = ((core - 0.55f) * 1.6f).coerceIn(0f, 0.55f)
            var r = e * (LR + (1f - LR) * pale * 0.6f)
            var g = e * (LG + (1f - LG) * pale * 0.6f)
            var b = e * (LB + (1f - LB) * pale * 0.6f)
            // grain, more where it's dark (a phone in the dark)
            val grain = (rng.nextFloat() - 0.5f) * 0.035f
            val base = f * 0.030f
            r = r + base * 0.6f + grain; g = g + base + grain; b = b + base * 0.8f + grain
            // the hands are seen against their own glow and the face: darker metal, lume on top
            val alpha = if (face != null) 1f else (m * 0.92f + (e * 1.4f).coerceAtMost(1f)).coerceIn(0f, 1f)
            if (face == null && m > 0f) { val k = 1f - m * (1f - core); r = r * k + 0.025f * m; g = g * k + 0.032f * m; b = b * k + 0.028f * m }
            px[i] = ((alpha * 255).toInt().coerceIn(0, 255) shl 24) or
                ((r.coerceIn(0f, 1f) * 255).toInt() shl 16) or ((g.coerceIn(0f, 1f) * 255).toInt() shl 8) or (b.coerceIn(0f, 1f) * 255).toInt()
        }
        return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
    }

    private fun renderDial(n: Int): Bitmap {
        val lume = lumeMask(n) { cv, p, c, r ->
            fun at(s: Float, rad: Float) = Pair(c + rad * sin(s / 60f * 2f * Math.PI.toFloat()), c - rad * cos(s / 60f * 2f * Math.PI.toFloat()))
            p.strokeCap = Paint.Cap.BUTT
            for (s in 0 until 60) {
                if (s % 5 == 0) {
                    val (ax, ay) = at(s.toFloat(), r * 0.97f); val (bx, by) = at(s.toFloat(), r * 0.86f)
                    p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.038f; cv.drawLine(ax, ay, bx, by, p)
                } else {
                    val (dx, dy) = at(s.toFloat(), r * 0.92f)
                    p.style = Paint.Style.FILL
                    cv.save(); cv.rotate(s * 6f, dx, dy)
                    cv.drawRect(dx - r * 0.017f, dy - r * 0.02f, dx + r * 0.017f, dy + r * 0.02f, p); cv.restore()
                }
            }
            p.style = Paint.Style.FILL
            p.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); p.textAlign = Paint.Align.CENTER
            p.textSize = r * 0.2f
            for (s in 0 until 60 step 5) { val (x, y) = at(s.toFloat(), r * 0.70f); cv.drawText("$s", x, y + r * 0.072f, p) }
        }
        paintTexture(lume, n, 7)
        // the face: a disc, faintly visible, its rim a little lighter
        val face = FloatArray(n * n)
        val c = n / 2f; val rr = n / 2f * 0.985f
        for (y in 0 until n) for (x in 0 until n) {
            val d = kotlin.math.hypot(x + 0.5f - c, y + 0.5f - c) / rr
            face[y * n + x] = if (d < 1f) 0.6f + 0.4f * (1f - d) else 0f
        }
        return compose(n, lume, face, null, 99)
    }

    /** A hand pointing up (turned on drawing): dark metal, painted for the outer half. */
    private fun renderHand(n: Int, len: Float, width: Float, tail: Float, seed: Int): Bitmap {
        val c = n / 2f; val r = n / 2f * 0.96f
        val metal = lumeMask(n) { cv, p, _, _ ->
            p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.strokeWidth = r * width
            cv.drawLine(c, c + r * tail, c, c - r * len, p)
            p.style = Paint.Style.FILL; cv.drawCircle(c, c, r * 0.085f, p)
        }
        val lume = lumeMask(n) { cv, p, _, _ ->
            p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.strokeWidth = r * width * 0.62f
            cv.drawLine(c, c - r * len * 0.52f, c, c - r * len * 0.95f, p)
        }
        paintTexture(lume, n, seed)
        return compose(n, lume, null, blur(metal, n, maxOf(1, n / 300)), seed)
    }
}

/** A clock for animations that only runs while [running]: the time in ms, a frame at a time. */
@Composable
fun frameClock(running: Boolean): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running) {
        while (running) { withFrameMillis { now = System.currentTimeMillis() } }
    }
    return now
}

/** The timer on its own: the camera screen's corner while the latest frame develops. */
@Composable
fun DarkroomTimerView(startMs: Long, setS: Float, modifier: Modifier = Modifier) {
    val now = frameClock(true)
    Canvas(modifier) {
        with(DarkroomTimer) {
            drawTimer(Offset(size.width / 2, size.height / 2), minOf(size.width, size.height) / 2 * 0.96f,
                DarkroomTimer.remaining(startMs, setS, now), setS, now)
        }
    }
}
