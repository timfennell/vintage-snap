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
    private const val LR = 0.23f; private const val LG = 0.56f; private const val LB = 0.19f
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
        tilt.save(); tilt.setLocation(0f, 0f, -r * 7f / 72f); tilt.rotateX(6f); tilt.rotateY(-4f); tilt.getMatrix(m); tilt.restore()
        m.preTranslate(-c.x, -c.y); m.postTranslate(c.x, c.y); cv.concat(m)
        cv.drawBitmap(p.dial.core, null, dst, bmp)
        cv.drawBitmap(p.dial.glow, null, dst, add)
        // three painted marks on the case by the switch, at the lower right: a crooked little row
        switchMark.maskFilter = android.graphics.BlurMaskFilter(maxOf(r * 0.006f * zoom, 0.6f) / zoom, android.graphics.BlurMaskFilter.Blur.NORMAL)
        switchMark.alpha = (235 * breathe).toInt()
        for ((dx, dy, a) in listOf(Triple(0.70f, 0.905f, 18f), Triple(0.825f, 0.85f, 24f), Triple(0.95f, 0.91f, 14f))) {
            val x = c.x + dx * r; val y = c.y + dy * r; val h = r * 0.026f
            cv.save(); cv.rotate(a, x, y); cv.drawRect(x - h, y - h, x + h, y + h, switchMark); cv.restore()
        }
        val left = remainingS.coerceAtLeast(0f)
        for ((l, s) in listOf(p.minute to (left / 60f % 60f), p.second to (left % 60f))) {
            cv.save(); cv.rotate(s / 60f * 360f, c.x, c.y)
            cv.drawBitmap(l.core, null, dst, bmp)
            cv.drawBitmap(l.glow, null, dst, add)
            cv.restore()
        }
        // the knob at the centre: unpainted, so as dark as the face
        cv.drawCircle(c.x, c.y, r * 0.075f, hub)
        cv.restore()
    }

    private val tilt = android.graphics.Camera()
    private val m = android.graphics.Matrix()
    private val switchMark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb((LR * 255 * 0.9f).toInt(), (LG * 255 * 0.9f).toInt(), (LB * 255 * 0.9f).toInt()) }
    private val hub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF010201.toInt() }

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
                val (ax, ay) = at(s.toFloat(), r * 1.0f); val (bx, by) = at(s.toFloat(), r * 0.89f)
                p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.044f; cv.drawLine(ax, ay, bx, by, p)
            } else {
                val (dx, dy) = at(s.toFloat(), r * 0.95f)
                p.style = Paint.Style.FILL
                cv.save(); cv.rotate(s * 6f, dx, dy)
                cv.drawRoundRect(dx - r * 0.017f, dy - r * 0.02f, dx + r * 0.017f, dy + r * 0.02f, r * 0.004f, r * 0.004f, p); cv.restore()
            }
        }
        p.style = Paint.Style.FILL
        p.typeface = Typeface.create("sans-serif", Typeface.BOLD); p.textAlign = Paint.Align.CENTER
        p.textSize = r * 0.22f; p.letterSpacing = -0.05f
        for (s in 0 until 60 step 5) { val (x, y) = at(s.toFloat(), r * 0.745f); cv.drawText("$s", x, y + r * 0.08f, p) }
    }

    /** The paint on a hand: nearly its whole length, tapering toward the tip. */
    private fun handLume(n: Int, h: Hand) = mask(n) { cv, p, c, r ->
        p.style = Paint.Style.FILL
        val w0 = r * h.width * 0.78f; val w1 = r * h.width * 0.48f
        val y0 = c - r * h.len * 0.14f; val y1 = c - r * h.len * 0.965f
        val path = android.graphics.Path().apply {
            moveTo(c - w0 / 2, y0); lineTo(c - w1 / 2, y1)
            quadTo(c, y1 - w1 * 0.6f, c + w1 / 2, y1); lineTo(c + w0 / 2, y0)
            quadTo(c, y0 + w0 * 0.5f, c - w0 / 2, y0); close()
        }
        cv.drawPath(path, p)
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
        val lo = Noise(seed, 18); val mid = Noise(seed + 1, 110); val blot = Noise(seed + 5, 170)
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
            // mottled, as the paint really is under a lens: broad unevenness, darker blotches, fine speckle
            val bl = blot.at(u, w)
            val density = (0.84f + 0.16f * lo.at(u, w) + 0.22f * (mid.at(u, w) - 0.5f)) * (if (bl < 0.32f) 0.72f + bl * 0.85f else 1f)
            // a pinhole now and then
            val ph = pin.at(u, w); if (ph > 0.88f) e *= (1f - (ph - 0.88f) * 6f).coerceIn(0.25f, 1f)
            // the crystals: each cell its own brightness, rarely a bright one
            val cx = (u * cells).toInt(); val cy = (w * cells).toInt()
            var h = (cx * 73856093) xor (cy * 19349663) xor (seed * 83492791); h = h xor (h ushr 13); h *= 0x5bd1e995; h = h xor (h ushr 15)
            val hv = (h and 0xFFFF) / 65535f
            val grain = 0.80f + 0.34f * hv + (if (hv > 0.99f) 0.25f else 0f)
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

    /** Paint as a phone photographs it in the dark: a dim, saturated green (not a screen's mint). */
    private fun lumeColour(v: Float, out: FloatArray) {
        out[0] = v * LR; out[1] = v * LG; out[2] = v * LB
    }
    private fun argb(a: Float, r: Float, g: Float, b: Float) =
        ((a.coerceIn(0f, 1f) * 255).toInt() shl 24) or ((r.coerceIn(0f, 1f) * 255).toInt() shl 16) or
            ((g.coerceIn(0f, 1f) * 255).toInt() shl 8) or (b.coerceIn(0f, 1f) * 255).toInt()

    /** The dial's surface: the black face (grain, its rim just seen) and the paint on it, sharp. */
    private fun renderDialCore(n: Int): Bitmap {
        val lume = blur(dialLume(n).also { painted(it, n, 7) }, n, n / 420)   // a lens's softness at the paint's edges
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
            val f = 0.006f
            px[i] = argb(disc, col[0] + f * 0.6f + grain * 0.6f, col[1] + f + grain * 0.6f, col[2] + f * 0.8f + grain * 0.6f)
        }
        return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
    }

    /** A hand's surface, pointing up (turned when drawn): unseen dark metal, the paint toward its tip. */
    private fun renderHandCore(n: Int, h: Hand): Bitmap {
        val lume = blur(handLume(n, h).also { painted(it, n, h.seed) }, n, n / 420)
        // streaks down the hand's length, where the paint was brushed along it
        val streak = Noise(h.seed + 9, 420)
        for (y in 0 until n) for (x in 0 until n) { val i = y * n + x
            if (lume[i] > 0f) lume[i] *= 0.80f + 0.32f * streak.at((x + 0.5f) / n, (y + 0.5f) / n * 0.06f) }
        val metal = handMetal(n, h)
        val col = FloatArray(3)
        val px = IntArray(n * n)
        for (i in 0 until n * n) {
            val m = metal[i]; if (m <= 0f) continue
            lumeColour(lume[i], col)
            val k = 1f - lume[i]
            // unpainted metal is as dark as the face: unseen, except where it covers the paint
            px[i] = argb(m, col[0] + 0.0036f * k, col[1] + 0.006f * k, col[2] + 0.0048f * k)
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
            val e = halo[i] * 0.07f + wide[i] * (if (faceLight) 0.02f else 0.012f)
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
