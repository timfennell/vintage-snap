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
        val textStyle = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        for ((i, label) in labels.withIndex()) {
            val theta = (i - p) * step / r
            if (abs(theta) > 1.45f) continue
            val x = cx + r * sin(theta)
            val fade = cos(theta).coerceIn(0f, 1f)
            val layout = measurer.measure(label, textStyle.copy(color = labelColor(i).copy(alpha = fade),
                fontSize = (15 * (0.75f + 0.25f * fade)).sp))
            drawText(layout, topLeft = Offset(x - layout.size.width / 2f, size.height * 0.52f - layout.size.height / 2f))
            // a minor tick between marks
            val tx = cx + r * sin(theta + 0.5f * step / r)
            if (i < labels.size - 1) drawLine(Palette.engraving.copy(alpha = 0.35f * fade),
                Offset(tx, size.height * 0.78f), Offset(tx, size.height * 0.88f), 1.5f)
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
