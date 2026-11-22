package app.relens.ui

import android.graphics.Bitmap
import android.graphics.Canvas as ACanvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * A darkroom timer in the dark, as a photograph of one looks rather than a drawing: luminous
 * green figures 0 to 55, a dot for every second and a bar every five, hand-painted (mottled,
 * the edges a little ragged) and sharp however close the loupe goes, glowing into a soft halo
 * that faintly lights the black face, with a fine grain over it. Two dark metal hands, lumed
 * toward their tips: the short one the minutes left (under a minute: straight up at 0), the long
 * one the seconds, wound to how long a development is expected to take (learnt from the last
 * ones) and counting back down to 0 as the frame develops. A progress bar, the way a darkroom
 * has one.
 *
 * Two layers each for the dial and the hands: the painted surface, rendered at the size it is
 * seen (the loupe's zoom included; in the background, the nearest size meanwhile), and the glow,
 * soft by nature, rendered small and added on top. Only the hands turn each frame.
 */
object DarkroomTimer {
    // lume: the paint's green; its brightest cores go paler
    private const val LR = 0.36f; private const val LG = 0.94f; private const val LB = 0.52f
    private const val GLOW_N = 256

    private class Layers(val core: Bitmap, val glow: Bitmap)
    private class Parts(val dial: Layers, val minute: Layers, val second: Layers)

    private val sharp = ConcurrentHashMap<Int, Triple<Bitmap, Bitmap, Bitmap>>()   // size -> dial, minute, second surfaces
    @Volatile private var glows: Triple<Bitmap, Bitmap, Bitmap>? = null
    private val pending = ConcurrentHashMap.newKeySet<Int>()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "darkroom-timer").apply { priority = Thread.MIN_PRIORITY } }

    private val SIZES = intArrayOf(192, 384, 768, 1536)
    private fun wanted(px: Float) = SIZES.firstOrNull { it >= px } ?: SIZES.last()

    /** The layers for a dial [px] pixels across on screen: the right size if made, else the nearest one there is. */
    private fun parts(px: Float): Parts? {
        val n = wanted(px)
        if (glows == null && pending.add(-1)) worker.execute { glows = Triple(renderDialGlow(GLOW_N), renderHandGlow(GLOW_N, MIN), renderHandGlow(GLOW_N, SEC)) }
        if (!sharp.containsKey(n) && pending.add(n)) worker.execute {
            sharp[n] = Triple(renderDialCore(n), renderHandCore(n, MIN), renderHandCore(n, SEC))
            // keep the two nearest the size in use: the rest go
            while (sharp.size > 2) sharp.keys.maxByOrNull { kotlin.math.abs(it - n) }?.let { sharp.remove(it) }
            pending.remove(n)
        }
        val g = glows ?: return null
        val have = sharp[n] ?: sharp.entries.minByOrNull { kotlin.math.abs(it.key - n) }?.value ?: return null
        return Parts(Layers(have.first, g.first), Layers(have.second, g.second), Layers(have.third, g.third))
    }

    private val bmp = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val add = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
    private val black = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF040605.toInt() }

    /**
     * Draws the timer in a circle at [c], radius [r]: [remainingS] seconds left; [setS] what it was
     * wound to (the hands show what is left); [nowMs] for the paint's faint breathing; [zoom] how
     * much the canvas is magnified (the loupe), so the paint is rendered sharp enough for it.
     */
    fun DrawScope.drawTimer(c: Offset, r: Float, remainingS: Float, @Suppress("UNUSED_PARAMETER") setS: Float, nowMs: Long, zoom: Float = 1f) {
        val cv = drawContext.canvas.nativeCanvas
        val dst = RectF(c.x - r, c.y - r, c.x + r, c.y + r)
        val p = parts(2f * r * zoom) ?: run { cv.drawCircle(c.x, c.y, r, black); return }
        val breathe = 0.93f + 0.07f * sin(nowMs / 900.0).toFloat()
        add.alpha = (255 * breathe).toInt()
        cv.save()
        // seen a little from one side, as a timer on a shelf is: a slight tilt in depth
        tilt.save(); tilt.setLocation(0f, 0f, -r * 7f / 72f); tilt.rotateX(9f); tilt.rotateY(-6f); tilt.getMatrix(m); tilt.restore()
        m.preTranslate(-c.x, -c.y); m.postTranslate(c.x, c.y); cv.concat(m)
        // the case's bezel: dark metal round the face, its inner edge catching the paint's light
        bezel.shader = android.graphics.RadialGradient(c.x, c.y, r * 1.07f, intArrayOf(0xFF0E1511.toInt(), 0xFF0A0D0B.toInt(), 0xFF050605.toInt()),
            floatArrayOf(0.90f, 0.95f, 1f), android.graphics.Shader.TileMode.CLAMP)
        cv.drawCircle(c.x, c.y, r * 1.07f, bezel)
        cv.drawBitmap(p.dial.core, null, dst, bmp)
        cv.drawBitmap(p.dial.glow, null, dst, add)
        val left = remainingS.coerceAtLeast(0f)
        for ((l, s) in listOf(p.minute to (left / 60f % 60f), p.second to (left % 60f))) {
            cv.save(); cv.rotate(s / 60f * 360f, c.x, c.y)
            cv.drawBitmap(l.core, null, dst, bmp)
            cv.drawBitmap(l.glow, null, dst, add)
            cv.restore()
        }
        // the knob at the centre: unpainted, so as dark as the face
        cv.drawCircle(c.x, c.y, r * 0.075f, hub)
        // the glass over it all: a faint second reflection of the glow, and a soft sheen at the top left
        ghost.alpha = (22 * breathe).toInt()
        cv.save(); cv.translate(r * 0.018f, r * 0.026f); cv.scale(1.015f, 1.015f, c.x, c.y)
        cv.drawBitmap(p.dial.glow, null, dst, ghost); cv.restore()
        sheen.shader = android.graphics.RadialGradient(c.x - r * 0.45f, c.y - r * 0.55f, r * 0.9f,
            intArrayOf(0x16FFFFFF, 0x06FFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.5f, 1f), android.graphics.Shader.TileMode.CLAMP)
        cv.drawCircle(c.x, c.y, r * 0.99f, sheen)
        cv.restore()
    }

    private val tilt = android.graphics.Camera()
    private val m = android.graphics.Matrix()
    private val bezel = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF020403.toInt() }
    private val ghost = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Seconds left on the timer for a development started at [startMs], expected to take [setS]. */
    fun remaining(startMs: Long, setS: Float, nowMs: Long) = maxOf(setS - (nowMs - startMs) / 1000f, 0f)

    // ---------------------------------------------------------------- the shapes

    private class Hand(val len: Float, val width: Float, val tail: Float, val seed: Int)
    private val MIN = Hand(0.58f, 0.075f, 0.10f, 11)
    private val SEC = Hand(0.84f, 0.05f, 0.16f, 23)

    /** Coverage of what [draw] paints, at [n] pixels (white on nothing), as 0..1 floats. */
    private fun mask(n: Int, draw: (ACanvas, Paint, Float, Float) -> Unit): FloatArray {
        val b = Bitmap.createBitmap(n, n, Bitmap.Config.ALPHA_8)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
        draw(ACanvas(b), p, n / 2f, n / 2f * 0.96f)
        val row = b.rowBytes
        val bytes = java.nio.ByteBuffer.allocate(row * n); b.copyPixelsToBuffer(bytes); b.recycle()
        val a = bytes.array()
        return FloatArray(n * n) { (a[(it / n) * row + it % n].toInt() and 0xFF) / 255f }
    }

    private fun dialLume(n: Int) = mask(n) { cv, p, c, r ->
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
                cv.drawRoundRect(dx - r * 0.017f, dy - r * 0.02f, dx + r * 0.017f, dy + r * 0.02f, r * 0.004f, r * 0.004f, p); cv.restore()
            }
        }
        p.style = Paint.Style.FILL
        p.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); p.textAlign = Paint.Align.CENTER
        p.textSize = r * 0.2f
        for (s in 0 until 60 step 5) { val (x, y) = at(s.toFloat(), r * 0.70f); cv.drawText("$s", x, y + r * 0.072f, p) }
    }

    private fun handLume(n: Int, h: Hand) = mask(n) { cv, p, c, r ->
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.strokeWidth = r * h.width * 0.62f
        cv.drawLine(c, c - r * h.len * 0.52f, c, c - r * h.len * 0.95f, p)
    }

    private fun handMetal(n: Int, h: Hand) = mask(n) { cv, p, c, r ->
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.strokeWidth = r * h.width
        cv.drawLine(c, c + r * h.tail, c, c - r * h.len, p)
        p.style = Paint.Style.FILL; cv.drawCircle(c, c, r * 0.085f, p)
    }

    // ---------------------------------------------------------------- the paint

    /** Smooth noise on a [g]-cell lattice, the same pattern at any resolution (it's in dial units). */
    private class Noise(seed: Int, val g: Int) {
        private val lat = Random(seed).let { r -> FloatArray(g * g) { r.nextFloat() } }
        fun at(u: Float, v: Float): Float {
            val fx = u * (g - 1); val fy = v * (g - 1)
            val ix = fx.toInt().coerceIn(0, g - 2); val iy = fy.toInt().coerceIn(0, g - 2)
            val tx = fx - ix; val ty = fy - iy
            val tx2 = tx * tx * (3 - 2 * tx); val ty2 = ty * ty * (3 - 2 * ty)
            val p = lat[iy * g + ix]; val q = lat[iy * g + ix + 1]; val r = lat[(iy + 1) * g + ix]; val s = lat[(iy + 1) * g + ix + 1]
            return (p + (q - p) * tx2) + ((r + (s - r) * tx2) - (p + (q - p) * tx2)) * ty2
        }
    }

    /** Hand-painted: mottled thickness, edges eaten a little unevenly (the pattern fixed to the dial, not the pixels). */
    /**
     * Stamped on, as the paint of these dials is: the ink a touch denser where it gathered at the
     * edges of each mark, the edges finely uneven, a pinhole here and there, the glow varying
     * gently across the dial, and the phosphor's crystals showing under the loupe. The pattern is
     * fixed to the dial, not the pixels, so it looks the same at every size.
     */
    private fun painted(a: FloatArray, n: Int, seed: Int) {
        val lo = Noise(seed, 18); val mid = Noise(seed + 1, 90)
        val edgeN = Noise(seed + 2, 520)                            // the stamp's fine edge
        val charge = Noise(seed + 3, 5)                             // the glow, gently uneven across the dial
        val pin = Noise(seed + 4, 900)                              // pinholes
        val near = blur(a, n, maxOf(1, n / 380))                    // how far inside a mark (its rim, where the ink gathers)
        val cells = 1400                                            // phosphor crystals, in dial units
        for (y in 0 until n) for (x in 0 until n) {
            val i = y * n + x; val v = a[i]; if (v <= 0f) continue
            val u = (x + 0.5f) / n; val w = (y + 0.5f) / n
            // the edge: sharpened a little and nudged in or out by the stamp's fine unevenness
            var e = if (v < 0.999f) ((v - 0.5f) * 1.8f + 0.5f + (edgeN.at(u, w) - 0.5f) * 0.55f).coerceIn(0f, 1f) else v
            // ink density: even, with the rim of each mark a little denser (brighter)
            val rimBoost = 1f + 0.16f * ((1f - near[i]) / 0.45f).coerceIn(0f, 1f) * (if (v > 0.5f) 1f else 0f)
            val density = 0.90f + 0.10f * lo.at(u, w) + 0.05f * (mid.at(u, w) - 0.5f)
            // a pinhole now and then
            val ph = pin.at(u, w); if (ph > 0.88f) e *= (1f - (ph - 0.88f) * 6f).coerceIn(0.25f, 1f)
            // the crystals: each cell its own brightness, rarely a bright one
            val cx = (u * cells).toInt(); val cy = (w * cells).toInt()
            var h = (cx * 73856093) xor (cy * 19349663) xor (seed * 83492791); h = h xor (h ushr 13); h *= 0x5bd1e995; h = h xor (h ushr 15)
            val hv = (h and 0xFFFF) / 65535f
            val grain = 0.87f + 0.22f * hv + (if (hv > 0.99f) 0.3f else 0f)
            a[i] = e * density * rimBoost * grain * (0.88f + 0.20f * charge.at(u, w))
        }
    }

    /** A separable box blur, three passes (close to a Gaussian) of radius [rad] pixels. */
    private fun blur(src: FloatArray, n: Int, rad: Int): FloatArray {
        if (rad < 1) return src.copyOf()
        val a = src.copyOf(); val t = FloatArray(n * n)
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

    private fun lumeColour(v: Float, out: FloatArray) {
        val pale = ((v - 0.6f) * 1.5f).coerceIn(0f, 0.5f) * 0.6f
        out[0] = v * (LR + (1f - LR) * pale); out[1] = v * (LG + (1f - LG) * pale); out[2] = v * (LB + (1f - LB) * pale)
    }
    private fun argb(a: Float, r: Float, g: Float, b: Float) =
        ((a.coerceIn(0f, 1f) * 255).toInt() shl 24) or ((r.coerceIn(0f, 1f) * 255).toInt() shl 16) or
            ((g.coerceIn(0f, 1f) * 255).toInt() shl 8) or (b.coerceIn(0f, 1f) * 255).toInt()

    /** The dial's surface: the black face (grain, its rim just seen) and the paint on it, sharp. */
    private fun renderDialCore(n: Int): Bitmap {
        val lume = dialLume(n); painted(lume, n, 7)
        val rng = Random(99); val col = FloatArray(3)
        val c = n / 2f; val rr = n / 2f * 0.985f
        val px = IntArray(n * n)
        for (y in 0 until n) for (x in 0 until n) {
            val i = y * n + x
            val d = kotlin.math.hypot(x + 0.5f - c, y + 0.5f - c) / rr
            val disc = (1f - (d - 1f) * n * 0.5f).coerceIn(0f, 1f)          // the face's edge, anti-aliased
            if (disc <= 0f) continue
            val grain = (rng.nextFloat() - 0.5f) * 0.03f
            lumeColour(lume[i], col)
            val f = 0.016f + 0.006f * (1f - d)
            px[i] = argb(disc, col[0] + f * 0.6f + grain, col[1] + f + grain, col[2] + f * 0.8f + grain)
        }
        return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
    }

    /** A hand's surface, pointing up (turned when drawn): unseen dark metal, the paint toward its tip. */
    private fun renderHandCore(n: Int, h: Hand): Bitmap {
        val lume = handLume(n, h); painted(lume, n, h.seed)
        val metal = handMetal(n, h)
        val col = FloatArray(3)
        val px = IntArray(n * n)
        for (i in 0 until n * n) {
            val m = metal[i]; if (m <= 0f) continue
            lumeColour(lume[i], col)
            val k = 1f - lume[i]
            // unpainted metal is as dark as the face: unseen, except where it covers the paint
            px[i] = argb(m, col[0] + 0.0096f * k, col[1] + 0.016f * k, col[2] + 0.0128f * k)
        }
        return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
    }

    /** The glow, soft by nature (small, scaled up): a tight halo and a wide spill, added onto the surface. */
    private fun glow(lume: FloatArray, n: Int, faceLight: Boolean): Bitmap {
        val halo = blur(lume, n, maxOf(1, n / 70))
        val wide = blur(lume, n, maxOf(2, n / 14))
        val col = FloatArray(3)
        val px = IntArray(n * n)
        for (i in 0 until n * n) {
            val e = halo[i] * 0.62f + wide[i] * (if (faceLight) 0.30f else 0.18f)
            lumeColour(e, col)
            px[i] = argb(1f, col[0], col[1], col[2])
        }
        return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
    }
    private fun renderDialGlow(n: Int) = glow(dialLume(n).also { painted(it, n, 7) }, n, true)
    private fun renderHandGlow(n: Int, h: Hand) = glow(handLume(n, h).also { painted(it, n, h.seed) }, n, false)
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
