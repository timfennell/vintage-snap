package app.relens.ui

import android.graphics.BlurMaskFilter
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

/**
 * A darkroom timer in the dark: a black dial with luminous green figures 0 to 55, a dot for
 * every second, and two hands: the set hand where the timer was wound to (how long a
 * development is expected to take, learnt from the last ones) and the sweep hand counting
 * back down to 0 as the frame develops. A progress bar, the way a darkroom has one.
 */
object DarkroomTimer {
    private const val LUME = 0xFF62F08E.toInt()
    private val sharp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LUME; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD); textAlign = Paint.Align.CENTER }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LUME; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD); textAlign = Paint.Align.CENTER }
    private val dial = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF040605.toInt() }

    /**
     * Draws the timer in a circle at [c], radius [r]: [remainingS] seconds left on the sweep hand,
     * [setS] where the set hand stands, [nowMs] for the glow's faint breathing.
     */
    fun DrawScope.drawTimer(c: Offset, r: Float, remainingS: Float, setS: Float, nowMs: Long) {
        val cv = drawContext.canvas.nativeCanvas
        // luminous paint breathes very slightly, and fades a touch as it has been charged a while
        val breathe = 0.92f + 0.08f * sin(nowMs / 900.0).toFloat()
        cv.drawCircle(c.x, c.y, r, dial)
        val blur = maxOf(r * 0.06f, 1f)
        halo.maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)

        fun at(s: Float, rad: Float) = Offset(c.x + rad * sin(s / 60f * 2f * Math.PI.toFloat()), c.y - rad * cos(s / 60f * 2f * Math.PI.toFloat()))
        fun both(alpha: Float, draw: (Paint) -> Unit) {
            halo.alpha = (alpha * 150 * breathe).toInt().coerceIn(0, 255); draw(halo)
            sharp.alpha = (alpha * 235 * breathe).toInt().coerceIn(0, 255); draw(sharp)
        }
        // the second dots, a bar at every five
        for (s in 0 until 60) {
            if (s % 5 == 0) {
                val a = at(s.toFloat(), r * 0.97f); val b = at(s.toFloat(), r * 0.86f)
                both(1f) { p -> p.strokeWidth = r * 0.035f; p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.BUTT; cv.drawLine(a.x, a.y, b.x, b.y, p); p.style = Paint.Style.FILL }
            } else {
                val d = at(s.toFloat(), r * 0.92f)
                both(0.9f) { p -> cv.drawRect(d.x - r * 0.016f, d.y - r * 0.016f, d.x + r * 0.016f, d.y + r * 0.016f, p) }
            }
        }
        // the figures
        sharp.textSize = r * 0.19f; halo.textSize = r * 0.19f
        for (s in 0 until 60 step 5) {
            val p0 = at(s.toFloat(), r * 0.70f)
            both(1f) { p -> cv.drawText("$s", p0.x, p0.y + r * 0.068f, p) }
        }
        // the set hand: thin, dimmer (painted less), where it was wound to
        val sh = at(setS.coerceIn(0f, 59.9f), r * 0.86f)
        both(0.45f) { p -> p.strokeWidth = r * 0.025f; p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; cv.drawLine(c.x, c.y, sh.x, sh.y, p); p.style = Paint.Style.FILL }
        // the sweep hand: broad, luminous, counting down (with a short tail past the centre)
        val s = remainingS.coerceAtLeast(0f) % 60f
        val tip = at(s, r * 0.82f); val tail = at(s + 30f, r * 0.14f)
        both(1f) { p -> p.strokeWidth = r * 0.05f; p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; cv.drawLine(tail.x, tail.y, tip.x, tip.y, p); p.style = Paint.Style.FILL }
        // the hub, dark, and the knob's rim catching a little of the glow
        cv.drawCircle(c.x, c.y, r * 0.09f, dial)
        both(0.35f) { p -> p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.012f; cv.drawCircle(c.x, c.y, r * 0.09f, p); p.style = Paint.Style.FILL }
    }

    /** Seconds left on the timer for a development started at [startMs], expected to take [setS]. */
    fun remaining(startMs: Long, setS: Float, nowMs: Long) = maxOf(setS - (nowMs - startMs) / 1000f, 0f)
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
