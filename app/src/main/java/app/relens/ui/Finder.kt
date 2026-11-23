package app.relens.ui

import androidx.compose.ui.draw.drawWithContent

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
        // The optical pass only (pincushion, the mirrored walls). The glow that used to be a
        // blurred second pass here (the costliest thing on screen, every camera frame) is drawn
        // as soft gradients round the glass instead: [finderGlow].
        val sharpFx = RenderEffect.createRuntimeShaderEffect(sharp, "image")
        clip = true
        renderEffect = sharpFx.asComposeRenderEffect()
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
/**
 * A red LED readout as an old SLR's: a module of fixed digit [cells] that never moves. The
 * [value] sits right-aligned in its cells, the cells it doesn't use stay dark (their segments
 * faintly there), a decimal point lights inside its digit's cell rather than taking a place
 * of its own, a [prefix] (F, T, S) has its own cells to the left, and a unit ([suffix]: m, °, ")
 * has a slot of its own on the right, kept even when it is dark.
 */
fun SevenSegment(value: String, cells: Int, prefix: String = "", suffix: String? = null,
                 height: Dp = 22.dp, color: Color = Palette.led, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val digitW = height * 0.55f
    val cellW = digitW + height * 0.12f
    // (character, point lit after it), right-aligned into the cells
    val parsed = ArrayList<Pair<Char, Boolean>>()
    for (c in value) if (c == '.' && parsed.isNotEmpty()) parsed[parsed.size - 1] = parsed.last().first to true else if (c != '.') parsed += c to false
    val shown = (List(maxOf(0, cells - parsed.size)) { ' ' to false } + parsed.takeLast(cells))
    val all = prefix.map { it to false } + shown
    val slotW = height * 0.5f
    val total = cellW * all.size + (if (suffix != null) slotW else 0.dp)
    Canvas(modifier.width(total).height(height)) {
        val h = size.height
        val dw = digitW.toPx(); val cw = cellW.toPx()
        val th = h * 0.12f
        fun glyph(ch: String, x: Float, w: Float, scale: Float) {
            val st = TextStyle(color = color, fontSize = (h * scale).toSp(), fontFamily = Futuraish, fontWeight = FontWeight.Medium,
                shadow = androidx.compose.ui.graphics.Shadow(color.copy(alpha = 0.9f), Offset.Zero, th * 2.2f))
            val t = measurer.measure(ch, st)
            drawText(t, topLeft = Offset(x + (w - t.size.width) / 2f, h - t.size.height.toFloat()))
        }
        for ((i, cell) in all.withIndex()) {
            val x = i * cw
            val seg = segments(cell.first)
            if (seg != null) drawDigit(x, dw, h, th, seg, color)
            else { drawDigit(x, dw, h, th, "", color); glyph(cell.first.toString(), x, dw, if (cell.first == '∞') 0.8f else 0.7f) }
            // the point: lit when it follows this digit, faintly there otherwise
            val dot = Offset(x + dw + h * 0.04f, h * 0.92f)
            if (cell.second) glowDot(dot, th * 0.7f, color) else drawCircle(color.copy(alpha = 0.07f), th * 0.7f, dot)
        }
        if (!suffix.isNullOrEmpty()) glyph(suffix, all.size * cw, slotW.toPx(), 0.55f)
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

// ---- the lens's blur, live in the finder: depth of field and the tilt plane -----------------

private const val LENS_BLUR_AGSL = """
uniform shader image;
uniform shader depth;     // the live depth map (relative, red), over the camera's whole preview
uniform float2 depthSize;
uniform float2 pscale;    // finder -> preview: the crop the finder shows, about the centre
uniform float hasDepth;
uniform float a;          // disparity = a + b x relative
uniform float b;
uniform float focusD;     // the focus distance, dioptres
uniform float2 focus;     // the focus point (px)
uniform float2 dir;       // a tilt's way of changing focus across the frame (unit)
uniform float dPerPx;     // a tilt's dioptres per pixel along dir (0: none)
uniform float rPerD;      // blur radius (px) per dioptre of defocus, at this aperture
uniform float maxR;
uniform float halfDof;
uniform float2 size;

half4 main(float2 p) {
    // where this point's subject is, and where the lens is focused for it
    float d = focusD;
    if (hasDepth > 0.5) {
        float2 uv = 0.5 + (p / size - 0.5) * pscale;
        d = a + b * depth.eval(uv * depthSize).r;
    }
    float plane = focusD + dot(p - focus, dir) * dPerPx;
    float r = min(max(abs(d - plane) - halfDof, 0.0) * rPerD, maxR);
    if (r < 0.6) return image.eval(p);
    half4 acc = half4(0);
    float turn = fract(sin(dot(floor(p), float2(12.9898, 78.233))) * 43758.5453) * 6.2832;
    for (int i = 0; i < 24; i++) {
        float ang = float(i) * 2.39996 + turn;
        float rr = sqrt((float(i) + 0.5) / 24.0) * r;
        acc += image.eval(p + rr * float2(cos(ang), sin(ang)));
    }
    return acc / 24.0;
}
"""

/**
 * The finder's picture blurred as the mounted lens would blur it (Android 13+): each point by
 * its defocus, from the live depth map ([live]: the scene's distances) against the focus
 * distance, and for a tilt lens against the turned plane of focus (Movements.tiltDioptres).
 * Without a depth map yet the scene is taken to be at the focus distance (only a tilt blurs).
 */
@Composable
fun Modifier.lensBlur(live: Body.LiveDepth?, crop: Float, tiltDioptres: FloatArray, focusX: Float, focusY: Float,
                      focusD: Float?, focalMM: Float, fNumber: Float): Modifier {
    if (Build.VERSION.SDK_INT < 33 || focusD == null) return this
    val g = kotlin.math.hypot(tiltDioptres[0], tiltDioptres[1])
    if (live == null && g < 1e-4f) return this
    val shader = remember { RuntimeShader(LENS_BLUR_AGSL) }
    return this.graphicsLayer {
        val hd = kotlin.math.hypot(size.width / 2, size.height / 2)
        val f = focalMM / 1000f
        val longPx = maxOf(size.width, size.height)
        val rPerD = 0.5f * (f / fNumber) * f / 0.036f * longPx
        shader.setFloatUniform("size", size.width, size.height)
        shader.setFloatUniform("focusD", focusD)
        shader.setFloatUniform("focus", focusX * size.width, focusY * size.height)
        shader.setFloatUniform("dir", if (g > 1e-4f) tiltDioptres[0] / g else 0f, if (g > 1e-4f) tiltDioptres[1] / g else 0f)
        shader.setFloatUniform("dPerPx", if (g > 1e-4f) g / hd else 0f)
        shader.setFloatUniform("rPerD", rPerD)
        shader.setFloatUniform("maxR", longPx * 0.02f)
        shader.setFloatUniform("halfDof", 1.5f / maxOf(rPerD, 1e-3f))
        shader.setFloatUniform("pscale", 1f / (1.125f * crop), 1f / crop)
        if (live != null) {
            shader.setInputShader("depth", android.graphics.BitmapShader(live.bmp, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP))
            shader.setFloatUniform("depthSize", live.bmp.width.toFloat(), live.bmp.height.toFloat())
            shader.setFloatUniform("hasDepth", 1f); shader.setFloatUniform("a", live.a); shader.setFloatUniform("b", live.b)
        } else {
            shader.setInputShader("depth", android.graphics.BitmapShader(android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888),
                android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP))
            shader.setFloatUniform("depthSize", 1f, 1f)
            shader.setFloatUniform("hasDepth", 0f); shader.setFloatUniform("a", 0f); shader.setFloatUniform("b", 0f)
        }
        clip = true
        renderEffect = RenderEffect.createRuntimeShaderEffect(shader, "image").asComposeRenderEffect()
    }
}

// ---- the split prism and its microprism collar ------------------------------------------------

private const val SPLIT_AGSL = """
uniform shader image;
uniform float2 centre;
uniform float r;          // the split spot's radius (px)
uniform float shift;      // how far the halves stand apart (px, signed: which way the focus is off)
uniform float prisms;     // the collar's shimmer (px)

float hash(float2 q) { return fract(sin(dot(q, float2(127.1, 311.7))) * 43758.5453); }

half4 main(float2 p) {
    float2 d = p - centre;
    float dist = length(d);
    if (dist < r) {
        // the split image: the top half one way, the bottom the other; a fine dark line between
        float side = d.y < 0.0 ? 1.0 : -1.0;
        half4 c = image.eval(p + float2(side * shift, 0.0));
        float line = smoothstep(0.6, 1.4, abs(d.y));
        float rim = smoothstep(r, r - 1.5, dist);
        return half4(c.rgb * (0.82 + 0.18 * line) * (0.85 + 0.15 * rim), 1.0);
    }
    if (dist < r * 1.6) {
        // the microprism collar: tiny prisms that break an out-of-focus picture into a shimmer
        float cell = max(r * 0.09, 2.0);
        float2 q = floor(d / cell);
        float2 o = (float2(hash(q), hash(q + 17.0)) - 0.5) * 2.0 * prisms;
        half4 c = image.eval(p + o);
        float edge = smoothstep(r * 1.6, r * 1.55, dist) * smoothstep(r, r + 1.5, dist);
        return half4(mix(image.eval(p).rgb, c.rgb * 0.92, edge), 1.0);
    }
    return image.eval(p);
}
"""

/**
 * The SLR's focusing aids at the finder's centre (Android 13+): a split-image spot whose halves
 * stand apart by how far the focus is off the subject there ([defocusD] dioptres, signed), and
 * a microprism collar that shimmers while it's off. The shift is the defocus blur the lens
 * would give wide open (as the prism sees it), half a spot at most.
 */
@Composable
fun Modifier.splitPrism(defocusD: Float?, focalMM: Float, maxAperture: Float): Modifier {
    if (Build.VERSION.SDK_INT < 33) return this
    val shader = remember { RuntimeShader(SPLIT_AGSL) }
    return this.graphicsLayer {
        val r = minOf(size.width, size.height) * 0.085f
        val f = focalMM / 1000f
        val rPerD = 0.5f * (f / maxAperture) * f / 0.036f * maxOf(size.width, size.height)
        val sh = ((defocusD ?: 0f) * rPerD * 0.8f).coerceIn(-0.6f * r, 0.6f * r)
        shader.setFloatUniform("centre", size.width / 2f, size.height / 2f)
        shader.setFloatUniform("r", r)
        shader.setFloatUniform("shift", sh)
        shader.setFloatUniform("prisms", minOf(kotlin.math.abs(sh) * 0.5f, r * 0.12f))
        clip = true
        renderEffect = RenderEffect.createRuntimeShaderEffect(shader, "image").asComposeRenderEffect()
    }
}


/**
 * The light from the lit glass spilling into the dark eyepiece and over the readouts round it,
 * as soft gradients (cheap: no blur pass), coloured by the scene through the finder: the sky's
 * tone off the top edge, the horizon's off the sides, the ground's off the bottom.
 */
fun Modifier.finderGlow(glass: androidx.compose.ui.geometry.Rect?): Modifier = this.drawWithContent {
    drawContent()
    val g = glass ?: return@drawWithContent
    if (g.isEmpty) return@drawWithContent
    fun tone(t: FloatArray, k: Float) = Color(t[0], t[1], t[2]).copy(alpha = k)
    val sky = Chassis.sky; val hor = Chassis.horizon; val gnd = Chassis.ground
    val reach = 26.dp.toPx(); val near = 8.dp.toPx()
    val bright = (0.30f + 0.70f * ((sky.sum() + hor.sum() + gnd.sum()) / 9f)).coerceIn(0.3f, 1f)
    fun band(c: Color, from: Offset, to: Offset, rect: androidx.compose.ui.geometry.Rect) {
        val brush = androidx.compose.ui.graphics.Brush.linearGradient(listOf(c, c.copy(alpha = c.alpha * 0.35f), Color.Transparent), from, to)
        drawRect(brush, rect.topLeft, rect.size, blendMode = androidx.compose.ui.graphics.BlendMode.Plus)
    }
    val a = 0.55f * bright
    // top, sides, bottom: a tight bright spill and a long faint one
    for ((len, k) in listOf(near to 1f, reach to 0.45f)) {
        band(tone(sky, a * k), Offset(0f, g.top), Offset(0f, g.top - len), androidx.compose.ui.geometry.Rect(g.left, g.top - len, g.right, g.top))
        band(tone(gnd, a * k), Offset(0f, g.bottom), Offset(0f, g.bottom + len), androidx.compose.ui.geometry.Rect(g.left, g.bottom, g.right, g.bottom + len))
        band(tone(hor, a * k), Offset(g.left, 0f), Offset(g.left - len, 0f), androidx.compose.ui.geometry.Rect(g.left - len, g.top, g.left, g.bottom))
        band(tone(hor, a * k), Offset(g.right, 0f), Offset(g.right + len, 0f), androidx.compose.ui.geometry.Rect(g.right, g.top, g.right + len, g.bottom))
    }
    // the corners: round spills joining them
    for ((c, t) in listOf(g.topLeft to sky, g.topRight to sky, g.bottomLeft to gnd, g.bottomRight to gnd)) {
        drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(listOf(tone(t, a * 0.7f), Color.Transparent), c, reach), reach, c,
            blendMode = androidx.compose.ui.graphics.BlendMode.Plus)
    }
}
