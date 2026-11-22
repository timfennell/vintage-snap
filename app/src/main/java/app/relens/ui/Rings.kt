package app.relens.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * One ring of a lens barrel, seen from above the camera: a cylinder whose
 * engraved scale turns under a fixed index mark. Drag sideways to turn it.
 *
 * [position] is in scale marks (0 = the first label); [detents] snap to marks
 * when the finger lifts (an aperture ring's clicks), else it rests where left
 * (a focus ring). [onDetent] fires as each mark passes the index (haptics).
 * [extraMarks] draws small coloured ticks at fractional positions (the DoF
 * scale); [highlight] tints the index (e.g. orange when the ring follows AF).
 */
@Composable
fun BarrelRing(
    labels: List<String>,
    position: Float,
    onPosition: (Float) -> Unit,
    modifier: Modifier = Modifier,
    detents: Boolean,
    onDetent: () -> Unit,
    onTap: (() -> Unit)? = null,
    caption: String = "",
    extraMarks: List<Pair<Float, Color>> = emptyList(),
    labelColor: (Int) -> Color = { Palette.engraving },
    height: Dp = 64.dp,
    spacing: Dp = 64.dp,
    knurled: Boolean = true,
    /** Light from the finder above falling on the ring's upper surface (0: none). */
    finderLight: Float = 0f,
) {
    val measurer = rememberTextMeasurer()
    val scope = rememberCoroutineScope()
    val anim = remember { Animatable(position) }
    val pos by rememberUpdatedState(position)
    val onPos by rememberUpdatedState(onPosition)
    val onDet by rememberUpdatedState(onDetent)
    // Follow outside changes (a new lens, the subject tap) when not being dragged.
    LaunchedEffect(position) { if (!anim.isRunning && anim.value != position && !position.isNaN()) anim.snapTo(position) }
    val max = (labels.size - 1).coerceAtLeast(0).toFloat()
    // each figure engraved once (its own wear), and the ring's scratches, dust and brass-worn spots
    val engraved = remember(labels, labelColor) { HashMap<Int, androidx.compose.ui.graphics.ImageBitmap>() }
    val wear = remember(labels.size, caption) { Wear.make(labels.size, caption.hashCode()) }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .pointerInput(labels) {
                val step = spacing.toPx()
                detectHorizontalDragGestures(
                    onDragStart = { scope.launch { anim.stop(); if (!pos.isNaN()) anim.snapTo(pos) } },
                    onDragEnd = {
                        if (detents) scope.launch {
                            val target = anim.value.roundToInt().toFloat().coerceIn(0f, max)
                            anim.animateTo(target, spring(stiffness = 900f)) { onPos(value) }
                            onPos(target)
                        }
                    },
                ) { change, dx ->
                    change.consume()
                    val start = if (pos.isNaN()) anim.value else pos
                    val next = (start - dx / step).coerceIn(-0.35f, max + 0.35f)
                    if (floor(next + 0.5f) != floor(start + 0.5f)) onDet()
                    scope.launch { anim.snapTo(next) }
                    onPos(next.coerceIn(0f, max))
                }
            }
            .pointerInput(onTap) { if (onTap != null) detectTapGestures { onTap() } },
    ) {
        val p = if (position.isNaN()) anim.value else position
        drawBarrel(knurled)
        val step = spacing.toPx()
        val r = size.width * 0.62f                       // the cylinder's radius, px
        val cx = size.width / 2
        // the ring's own wear, turning with it: scratches, dust, black paint rubbed through to brass
        drawWear(wear, p, step, r, cx)
        for ((i, label) in labels.withIndex()) {
            val theta = (i - p) * step / r
            if (abs(theta) > 1.45f) continue
            val x = cx + r * sin(theta)
            val fade = cos(theta).coerceIn(0f, 1f)
            // engraved and enamel-filled, foreshortened toward the cylinder's sides
            val img = engraved.getOrPut(i) { Engraving.render(label, labelColor(i), density, i * 7919 + label.hashCode()) }
            val sx = (0.75f + 0.25f * fade) * (0.35f + 0.65f * fade)
            val sy = 0.75f + 0.25f * fade
            val w = img.width * sx; val h = img.height * sy
            drawImage(img, dstOffset = androidx.compose.ui.unit.IntOffset((x - w / 2).roundToInt(), (size.height * 0.52f - h / 2).roundToInt()),
                dstSize = androidx.compose.ui.unit.IntSize(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1)), alpha = fade)
            // a minor tick between marks
            val tx = cx + r * sin(theta + 0.5f * step / r)
            if (i < labels.size - 1) drawLine(Palette.engraving.copy(alpha = 0.35f * fade),
                Offset(tx, size.height * 0.78f), Offset(tx, size.height * 0.88f), 1.5f)
        }
        // the finder's glow from above, on the ring's upper surface: strongest under the finder's
        // middle and on the near (upper) edge, warm, fading over the cylinder's curve
        if (finderLight > 0.01f) {
            drawRect(Brush.radialGradient(listOf(Color(0xFFFFE4BE).copy(alpha = 0.16f * finderLight), Color(0xFFFFD9A8).copy(alpha = 0.05f * finderLight), Color.Transparent),
                center = Offset(cx, -size.height * 0.25f), radius = size.width * 0.55f),
                size = Size(size.width, size.height * 0.62f))
            drawRect(Color(0xFFFFE8C8).copy(alpha = 0.10f * finderLight), topLeft = Offset(size.width * 0.18f, size.height * 0.02f),
                size = Size(size.width * 0.64f, 1.2f))
        }
        for ((at, colour) in extraMarks) {
            val theta = (at - p) * step / r
            if (abs(theta) > 1.45f) continue
            val x = cx + r * sin(theta)
            drawLine(colour, Offset(x, size.height * 0.12f), Offset(x, size.height * 0.3f), 3f)
        }
        // the index: a fixed triangle on the barrel above the ring
        val tri = Path().apply {
            moveTo(cx - 7f, 0f); lineTo(cx + 7f, 0f); lineTo(cx, 12f); close()
        }
        drawPath(tri, Palette.orange)
        if (caption.isNotEmpty()) {
            val cap = measurer.measure(caption, TextStyle(color = Palette.dim, fontSize = 10.sp, letterSpacing = 1.5.sp))
            drawText(cap, topLeft = Offset(10f, 4f))
        }
    }
}

/** The cylinder: dark at its edges, a soft highlight along it, knurled grip bands. */
private fun DrawScope.drawBarrel(knurled: Boolean) {
    drawRect(Brush.verticalGradient(
        0f to Color(0xFF050505), 0.18f to Color(0xFF161514), 0.42f to Color(0xFF201F1D),
        0.6f to Color(0xFF121110), 1f to Color(0xFF040403)))
    if (knurled) {
        val band = size.height * 0.16f
        val step = 7f
        var x = 0f
        while (x < size.width) {
            drawLine(Color(0x1FFFFFFF), Offset(x, size.height - band), Offset(x + 3f, size.height), 1.2f)
            drawLine(Color(0x55000000), Offset(x + 2f, size.height - band), Offset(x + 5f, size.height), 1.2f)
            x += step
        }
    }
    drawRect(Color(0x22FFFFFF), topLeft = Offset(0f, size.height * 0.36f), size = Size(size.width, 1.5f))
}

/**
 * A figure as a lens barrel has it: cut into the metal and filled with enamel. The cut's upper
 * wall in shadow, a catch-light along its lower lip, the dark groove, and the enamel in it with
 * a faint gloss, chipped here and there (the groove showing through), some figures more worn.
 */
private object Engraving {
    fun render(text: String, colour: Color, density: Float, seed: Int): androidx.compose.ui.graphics.ImageBitmap {
        val rng = kotlin.random.Random(seed)
        val tp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
            textSize = 15f * density * 1.0f
            textAlign = android.graphics.Paint.Align.LEFT
        }
        val pad = 3f * density
        val bounds = android.graphics.Rect(); tp.getTextBounds(text, 0, text.length, bounds)
        val w = (bounds.width() + 2 * pad).toInt().coerceAtLeast(2); val h = (bounds.height() + 2 * pad).toInt().coerceAtLeast(2)
        val ox = pad - bounds.left; val oy = pad - bounds.top
        val out = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(out)
        val d = 0.75f * density
        // the lower lip catching the light, then the cut's shadowed upper wall, then the groove
        tp.color = android.graphics.Color.argb(46, 255, 245, 230); cv.drawText(text, ox, oy + d, tp)
        tp.color = android.graphics.Color.argb(230, 0, 0, 0); cv.drawText(text, ox, oy - d * 0.8f, tp)
        // the cut itself, a little wider than the enamel in it: a thin dark line round each figure
        tp.color = android.graphics.Color.rgb(10, 9, 8); cv.drawText(text, ox, oy, tp)
        tp.style = android.graphics.Paint.Style.STROKE; tp.strokeWidth = 0.9f * density; cv.drawText(text, ox, oy, tp)
        tp.style = android.graphics.Paint.Style.FILL
        // the enamel, on its own layer so its chips show the groove beneath
        val en = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val ec = android.graphics.Canvas(en)
        val c = colour
        tp.color = android.graphics.Color.argb(255, (c.red * 235).toInt(), (c.green * 235).toInt(), (c.blue * 235).toInt())
        // a hair inside the cut: the fill doesn't quite reach the edges
        tp.style = android.graphics.Paint.Style.FILL
        ec.drawText(text, ox, oy + d * 0.15f, tp)
        tp.style = android.graphics.Paint.Style.STROKE; tp.strokeWidth = 0.35f * density
        tp.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT)
        ec.drawText(text, ox, oy + d * 0.15f, tp)
        tp.style = android.graphics.Paint.Style.FILL
        // chips and wear: small irregular bites out of the enamel; one figure in five much more worn
        val worn = rng.nextFloat() < 0.2f
        val chips = ((if (worn) 14 else 4) * (bounds.width() / (12f * density))).toInt() + rng.nextInt(3)
        val chip = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT)
        }
        repeat(chips) {
            val x = pad + rng.nextFloat() * bounds.width(); val y = pad + rng.nextFloat() * bounds.height()
            val rr = (0.35f + rng.nextFloat() * rng.nextFloat() * (if (worn) 1.6f else 1.0f)) * density
            chip.alpha = (150 + rng.nextInt(105))
            val path = android.graphics.Path()
            for (k in 0 until 7) {
                val a = k / 7f * 6.2832f; val rad = rr * (0.6f + 0.6f * rng.nextFloat())
                val px = x + rad * kotlin.math.cos(a); val py = y + rad * kotlin.math.sin(a)
                if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            path.close(); ec.drawPath(path, chip)
        }
        // the enamel's faint gloss, brighter toward the top
        val gloss = android.graphics.Paint().apply {
            shader = android.graphics.LinearGradient(0f, 0f, 0f, h.toFloat(), android.graphics.Color.argb(60, 255, 255, 255), android.graphics.Color.argb(0, 255, 255, 255), android.graphics.Shader.TileMode.CLAMP)
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_ATOP)
        }
        ec.drawRect(0f, 0f, w.toFloat(), h * 0.55f, gloss)
        cv.drawBitmap(en, 0f, 0f, null)
        en.recycle()
        return out.asImageBitmap()
    }
}

/**
 * The ring's own history, in its own coordinates (marks along it, height across it) so it turns
 * with the ring: fine scratches, dust, and places along its edges where the black paint has worn
 * through to the brass beneath.
 */
private class Wear(val scratches: List<FloatArray>, val dust: List<FloatArray>, val brass: List<FloatArray>) {
    companion object {
        fun make(marks: Int, seed: Int): Wear {
            val rng = kotlin.random.Random(seed)
            val span = marks + 2f
            val scratches = List((marks * 1.4f).toInt() + 3) {
                floatArrayOf(rng.nextFloat() * span - 1f, 0.15f + 0.7f * rng.nextFloat(), 0.15f + 0.9f * rng.nextFloat() * rng.nextFloat(),
                    (rng.nextFloat() - 0.5f) * 0.5f, 0.05f + 0.10f * rng.nextFloat())
            }
            val dust = List(marks * 3 + 4) { floatArrayOf(rng.nextFloat() * span - 1f, 0.1f + 0.8f * rng.nextFloat(), 0.4f + 1.1f * rng.nextFloat() * rng.nextFloat(), rng.nextFloat()) }
            val brass = List(marks / 2 + 1) {
                floatArrayOf(rng.nextFloat() * span - 1f, if (rng.nextBoolean()) 0f else 1f, 0.10f + 0.40f * rng.nextFloat() * rng.nextFloat(), 0.18f + 0.30f * rng.nextFloat())
            }
            return Wear(scratches, dust, brass)
        }
    }
}

private val BRASS = Color(0xFFB8914E)

private fun DrawScope.drawWear(w: Wear, p: Float, step: Float, r: Float, cx: Float) {
    fun xAt(m: Float): Float? { val t = (m - p) * step / r; return if (abs(t) > 1.45f) null else cx + r * sin(t) }
    fun fadeAt(m: Float) = cos((m - p) * step / r).coerceIn(0f, 1f)
    // brass showing through at the ring's edges: short, soft-ended wear along the rims
    for (b in w.brass) {
        val x0 = xAt(b[0]) ?: continue; val x1 = xAt(b[0] + b[2]) ?: continue
        val f = fadeAt(b[0]) * b[3]
        val y = if (b[1] == 0f) size.height * 0.035f else size.height * 0.965f
        drawLine(Brush.horizontalGradient(listOf(Color.Transparent, BRASS.copy(alpha = f), BRASS.copy(alpha = f * 0.8f), Color.Transparent), startX = x0, endX = x1),
            Offset(x0, y), Offset(x1, y), size.height * 0.022f)
    }
    // fine scratches: hairlines catching the light
    for (sc in w.scratches) {
        val x0 = xAt(sc[0]) ?: continue; val x1 = xAt(sc[0] + sc[2]) ?: continue
        val y = size.height * sc[1]
        drawLine(Color.White.copy(alpha = sc[4] * fadeAt(sc[0])), Offset(x0, y), Offset(x1, y + sc[3] * size.height * 0.3f), 0.8f)
    }
    // dust: pale specks, a few darker
    for (d in w.dust) {
        val x = xAt(d[0]) ?: continue
        val c = if (d[3] > 0.8f) Color(0xFF000000) else Color(0xFFD8D0C4)
        drawCircle(c.copy(alpha = (if (d[3] > 0.8f) 0.35f else 0.22f) * fadeAt(d[0])), d[2], Offset(x, size.height * d[1]))
    }
}

/** f-numbers as engraved: 1.4, 2, 2.8, 22. */
fun fLabel(n: Float): String = if (n >= 10 || n == n.toInt().toFloat()) n.roundToInt().toString() else "%.1f".format(n)

/** Distances as engraved: 0.45, 0.7, 1, 1.5, 10, ∞. */
fun mLabel(m: Float): String = when {
    m.isInfinite() -> "∞"
    m >= 10 -> m.roundToInt().toString()
    m == m.toInt().toFloat() -> m.toInt().toString()
    m < 1 -> "%.2f".format(m).trimEnd('0').trimEnd('.')
    else -> "%.1f".format(m).trimEnd('0').trimEnd('.')
}
