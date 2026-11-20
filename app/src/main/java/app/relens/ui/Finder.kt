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
uniform float4 inner;   // the ground glass (px, in this layer)
uniform float wall;      // how deep the eyepiece's walls stand round it (px)
uniform float k;
uniform float ca;
uniform float glowPass;  // 0: the sharp pass (glass and body); 1: the light only, read from a blurred copy

half3 at(float2 p) { return image.eval(p).rgb; }
half3 lin(half3 c) { return c * c; }               // near enough sRGB -> linear for a glow
half3 srgb(half3 c) { return sqrt(max(c, half3(0))); }

// Vintage Click's surround (Shaders.swift `surround`): the lit screen glows past its
// mask. Each point of the dark surround gathers light from a patch just inside the
// picture's nearest edge, a patch that widens and moves inward the further out the
// point is, so the glow blooms (soft and broad) rather than copying the edge.
half3 bloom(float2 q, float dist) {
    float h = inner.w - inner.y;
    float2 e = clamp(q, inner.xy + 3.0, inner.zw - 3.0);
    float spread = h * 0.05 + dist * 1.2;
    float2 inward = normalize((inner.xy + inner.zw) * 0.5 - e + 1e-3);
    float2 centre = e + inward * spread * 0.6;
    half3 s = half3(0);
    for (int i = 0; i < 24; i++) {
        float r = sqrt((float(i) + 0.5) / 24.0);
        float a = float(i) * 2.39996323;
        float2 t = clamp(centre + float2(cos(a), sin(a)) * r * spread, inner.xy + 1.0, inner.zw - 1.0);
        s += lin(at(t));
    }
    s /= 24.0;
    // a tight halo hugging the glass and a broad faint one reaching well out; bright
    // light (a window, a lamp near the edge) carries further
    float lum = dot(s, half3(0.2126, 0.7152, 0.0722));
    float halo = 0.12 * exp(-dist / (h * 0.025)) + 0.045 * exp(-dist / (h * 0.11))
               + 0.12 * smoothstep(0.15, 0.8, lum) * exp(-dist / (h * 0.07));
    return s * halo;
}

// The eyepiece's inner walls: dark, a little glossy, standing round the glass. Each
// wall mirrors the picture beside it (reflected across the glass's edge, so the
// corners show it doubly), the reflection blurring and dimming the further it is
// from the glass, as a reflection in a matt-black tunnel does.
half3 walls(float2 q, float dist) {
    float h = inner.w - inner.y;
    float2 m = q;
    if (q.x < inner.x) m.x = inner.x + (inner.x - q.x);
    if (q.x > inner.z) m.x = inner.z - (q.x - inner.z);
    if (q.y < inner.y) m.y = inner.y + (inner.y - q.y);
    if (q.y > inner.w) m.y = inner.w - (q.y - inner.w);
    float blur = 2.0 + dist * 0.45;
    half3 s = half3(0);
    for (int i = 0; i < 12; i++) {
        float r = sqrt((float(i) + 0.5) / 12.0);
        float a = float(i) * 2.39996323;
        float2 t = clamp(m + float2(cos(a), sin(a)) * r * blur, inner.xy + 1.0, inner.zw - 1.0);
        s += lin(at(t));
    }
    s /= 12.0;
    return s * (0.20 * exp(-dist / (h * 0.045)));
}

half4 main(float2 p) {
    float2 outv = max(max(inner.xy - p, p - inner.zw), float2(0));
    float dist = length(outv);
    float h = inner.w - inner.y;
    if (glowPass > 0.5) {
        // the light round the glass, added over the body: the bloom, and the walls' reflection
        if (dist <= 0.0 || dist > h * 0.3) return half4(0);
        half3 add = bloom(p, dist);
        if (dist < wall) add += walls(p, dist) * smoothstep(wall, wall * 0.6, dist);
        return half4(srgb(add) * 0.8, 0.0);
    }
    if (dist > 0.0) return image.eval(p);
    // the glass: pincushion about its own centre, each channel a touch apart
    float2 c = (inner.xy + inner.zw) * 0.5;
    float R = 0.5 * length(inner.zw - inner.xy);
    float2 d = (p - c) / R;
    float r2 = dot(d, d);
    float2 sr = c + (p - c) * (1.0 - k * (1.0 + ca * 40.0) * r2);
    float2 sg = c + (p - c) * (1.0 - k * r2);
    float2 sb = c + (p - c) * (1.0 - k * (1.0 - ca * 40.0) * r2);
    return half4(at(sr).r, at(sg).g, at(sb).b, 1.0);
}
"""

/**
 * The eyepiece as a graphics-layer effect over everything round the finder (no effect before
 * Android 13): [glass] is the ground glass in this layer's pixels, [wall] how deep the
 * eyepiece's walls stand round it. The glass gets the pincushion; the walls mirror it; and its
 * light blooms out over the body beside it, the lettering and the LEDs.
 */
@Composable
fun Modifier.eyepiece(glass: androidx.compose.ui.geometry.Rect?, wall: Dp): Modifier {
    if (Build.VERSION.SDK_INT < 33 || glass == null || glass.isEmpty) return this
    val sharp = remember { RuntimeShader(EYEPIECE_AGSL) }
    val glow = remember { RuntimeShader(EYEPIECE_AGSL) }
    return this.graphicsLayer {
        for ((sh, pass) in listOf(sharp to 0f, glow to 1f)) {
            sh.setFloatUniform("size", size.width, size.height)
            sh.setFloatUniform("inner", glass.left, glass.top, glass.right, glass.bottom)
            sh.setFloatUniform("wall", wall.toPx())
            sh.setFloatUniform("k", 0.018f)
            sh.setFloatUniform("ca", 0.0035f)
            sh.setFloatUniform("glowPass", pass)
        }
        // The glow reads a blurred copy, so its gathered light is a smooth wash, never
        // ghost copies of sharp things (the meter's figures); it is added over the sharp pass.
        val blurPx = 14.dp.toPx()
        val blurred = RenderEffect.createBlurEffect(blurPx, blurPx, android.graphics.Shader.TileMode.CLAMP)
        val light = RenderEffect.createRuntimeShaderEffect(glow, "image")
        val glowFx = RenderEffect.createChainEffect(light, blurred)
        val sharpFx = RenderEffect.createRuntimeShaderEffect(sharp, "image")
        clip = true
        renderEffect = RenderEffect.createBlendModeEffect(sharpFx, glowFx, android.graphics.BlendMode.PLUS).asComposeRenderEffect()
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
