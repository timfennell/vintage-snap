package app.relens.ui

import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.relens.R

/** Jost (SIL OFL), a free geometric face after Futura (licenses/Jost-OFL.txt). */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
val Futuraish = FontFamily(
    Font(R.font.jost, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.jost, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.jost, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.jost, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

/**
 * The eyepiece (Vintage Click's finder): what's on the screen is seen with a little pincushion,
 * a touch more in red than in blue (EYEPIECE_K, EYEPIECE_CA), and bright light near the
 * picture's edge spills as a glow into the dark surround (the game's surround()). An AGSL
 * shader over the whole finder (Android 13+); [inner] is the picture inside it (l, t, r, b, px).
 */
private const val EYEPIECE_AGSL = """
uniform shader image;
uniform float2 size;
uniform float4 inner;
uniform float k;
uniform float ca;

half3 at(float2 p) { return image.eval(p).rgb; }

// the picture's light just inside its edge nearest p, spread along the edge (a glow, not a copy)
half3 edgeLight(float2 p) {
    float2 e = clamp(p, inner.xy + 2.0, inner.zw - 2.0);
    float2 inward = normalize((inner.xy + inner.zw) * 0.5 - e);
    float2 along = float2(-inward.y, inward.x);
    half3 s = half3(0);
    for (int i = -3; i <= 3; i++) {
        float2 q = e + along * float(i) * 10.0 + inward * 4.0;
        q = clamp(q, inner.xy + 1.0, inner.zw - 1.0);
        s += at(q);
    }
    return s / 7.0;
}

half4 main(float2 p) {
    float2 c = size * 0.5;
    float R = 0.5 * length(size);
    float2 d = (p - c) / R;
    float r2 = dot(d, d);
    // pincushion: the edges stretched outward, so each channel is read nearer the middle
    float2 sr = c + (p - c) * (1.0 - k * (1.0 + ca * 40.0) * r2);
    float2 sg = c + (p - c) * (1.0 - k * r2);
    float2 sb = c + (p - c) * (1.0 - k * (1.0 - ca * 40.0) * r2);
    half3 col = half3(at(sr).r, at(sg).g, at(sb).b);
    // outside the picture: the surround, lit by flare spilling from the picture's edge
    float2 q = sg;
    float2 outv = max(max(inner.xy - q, q - inner.zw), float2(0));
    float dist = length(outv);
    if (dist > 0.0) {
        half3 e = edgeLight(q);
        float lum = dot(e, half3(0.2126, 0.7152, 0.0722));
        // only bright light spills far; everything casts a little
        float spill = 0.55 * exp(-dist / (0.025 * size.y)) + 0.25 * smoothstep(0.5, 1.0, lum) * exp(-dist / (0.10 * size.y));
        col = col + e * spill;
    }
    return half4(col, 1.0);
}
"""

/** The eyepiece as a graphics-layer effect (no effect before Android 13). */
@Composable
fun Modifier.eyepiece(innerInset: Dp): Modifier {
    if (Build.VERSION.SDK_INT < 33) return this
    val shader = remember { RuntimeShader(EYEPIECE_AGSL) }
    return this.graphicsLayer {
        val inset = innerInset.toPx()
        shader.setFloatUniform("size", size.width, size.height)
        shader.setFloatUniform("inner", inset, inset, size.width - inset, size.height - inset)
        shader.setFloatUniform("k", 0.018f)
        shader.setFloatUniform("ca", 0.0035f)
        renderEffect = RenderEffect.createRuntimeShaderEffect(shader, "image").asComposeRenderEffect()
    }
}

// ---- red seven-segment readouts with their glow ---------------------------------------------

/** Segments a..g lit for a character (a top, b upper right, c lower right, d bottom, e lower left, f upper left, g middle). */
private fun segments(ch: Char): String? = when (ch) {
    '0' -> "abcdef"; '1' -> "bc"; '2' -> "abdeg"; '3' -> "abcdg"; '4' -> "bcfg"
    '5' -> "acdfg"; '6' -> "acdefg"; '7' -> "abc"; '8' -> "abcdefg"; '9' -> "abcdfg"
    'F' -> "aefg"; '-' -> "g"; 'A' -> "abcefg"; 'E' -> "adefg"; 'L' -> "def"; 'P' -> "abefg"
    'r' -> "eg"; 'n' -> "ceg"; 'o' -> "cdeg"; 'H' -> "bcefg"; ' ' -> ""
    else -> null
}

/**
 * A red LED readout drawn as seven segments, slanted, each lit segment with a soft halo and the
 * unlit ones faintly there (a real LED module behind the finder glass). Other characters (m, /, ")
 * are drawn small in Jost with the same glow.
 */
@Composable
fun SevenSegment(text: String, height: Dp = 22.dp, color: Color = Palette.led, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val digitW = height * 0.55f
    val widths = text.map { c -> when { c == '.' -> height * 0.18f; segments(c) != null -> digitW + height * 0.12f; else -> height * 0.5f } }
    val total = widths.fold(0.dp) { a, b -> a + b }
    Canvas(modifier.width(total).height(height)) {
        var x = 0f
        val h = size.height
        val dw = digitW.toPx()
        val th = h * 0.12f
        for ((i, c) in text.withIndex()) {
            val seg = segments(c)
            when {
                c == '.' -> {
                    glowDot(Offset(x + h * 0.06f, h * 0.92f), th * 0.7f, color)
                }
                seg != null -> drawDigit(x, dw, h, th, seg, color)
                else -> {
                    val t = measurer.measure(c.toString(), TextStyle(color = color, fontSize = (h * 0.55f).toSp(), fontFamily = Futuraish, fontWeight = FontWeight.Medium))
                    val glow = measurer.measure(c.toString(), TextStyle(color = color, fontSize = (h * 0.55f).toSp(), fontFamily = Futuraish, fontWeight = FontWeight.Medium,
                        shadow = androidx.compose.ui.graphics.Shadow(color.copy(alpha = 0.9f), Offset.Zero, th * 2.2f)))
                    drawText(glow, topLeft = Offset(x, h - t.size.height.toFloat()))
                }
            }
            x += widths[i].toPx()
        }
    }
}

private fun DrawScope.glowDot(c: Offset, r: Float, color: Color) {
    drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(listOf(color.copy(alpha = 0.4f), Color.Transparent), c, r * 3.5f), r * 3.5f, c)
    drawCircle(color, r, c)
}

/** One slanted seven-segment digit at x, width w, height h, segment thickness th. */
private fun DrawScope.drawDigit(x: Float, w: Float, h: Float, th: Float, lit: String, color: Color) {
    val slant = 0.12f * h
    fun pt(u: Float, v: Float) = Offset(x + u * w + slant * (1f - v / h), v)     // v top->bottom, leaning right
    val m = h / 2
    val g = th * 0.35f
    val segs = mapOf(
        'a' to listOf(pt(0.12f, 0f), pt(0.88f, 0f), pt(0.76f, th), pt(0.24f, th)),
        'b' to listOf(pt(1f - 0f, g), pt(1f, m - g), pt(0.86f, m - th / 2 - g), pt(0.86f, th + g)),
        'c' to listOf(pt(1f, m + g), pt(1f, h - g), pt(0.86f, h - th - g), pt(0.86f, m + th / 2 + g)),
        'd' to listOf(pt(0.24f, h - th), pt(0.76f, h - th), pt(0.88f, h), pt(0.12f, h)),
        'e' to listOf(pt(0f, m + g), pt(0.14f, m + th / 2 + g), pt(0.14f, h - th - g), pt(0f, h - g)),
        'f' to listOf(pt(0f, g), pt(0.14f, th + g), pt(0.14f, m - th / 2 - g), pt(0f, m - g)),
        'g' to listOf(pt(0.12f, m), pt(0.24f, m - th / 2), pt(0.76f, m - th / 2), pt(0.88f, m), pt(0.76f, m + th / 2), pt(0.24f, m + th / 2)),
    )
    val litPath = Path()
    for ((name, poly) in segs) {
        val path = Path().apply { moveTo(poly[0].x, poly[0].y); for (p in poly.drop(1)) lineTo(p.x, p.y); close() }
        if (name in lit) litPath.addPath(path)
        else drawPath(path, color.copy(alpha = 0.07f))                           // an unlit segment, faintly there
    }
    if (lit.isEmpty()) return
    // The glow: the lit segments blurred (a wide faint haze, then a tight bright one),
    // then the segments themselves with a hotter core, as a red LED behind a filter.
    drawIntoCanvas { cv ->
        val ap = litPath.asAndroidPath()
        for ((radius, alpha) in listOf(th * 2.2f to 0.45f, th * 0.8f to 0.7f)) {
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color.copy(alpha = alpha).toArgb()
                maskFilter = android.graphics.BlurMaskFilter(radius, android.graphics.BlurMaskFilter.Blur.NORMAL)
            }
            cv.nativeCanvas.drawPath(ap, paint)
        }
    }
    drawPath(litPath, color)
    drawPath(litPath, Color(0xFFFFB08A).copy(alpha = 0.35f), style = Stroke(width = th * 0.25f))
}
