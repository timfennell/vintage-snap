package app.relens.ui

import android.graphics.RuntimeShader
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The camera body's materials, lit on the GPU by one shared studio (Vintage Snap UI shader
 * guide): overhead softboxes, a sharp horizon, a warm bounce from below, tinted by the scene the
 * finder sees (sky, horizon, ground) and turned by the phone's tilt ([BrassLight.dir]). Every
 * surface shader includes [ENV_CORE] and reflects the same environment, so brass, enamel and
 * glass read as one object.
 *
 * AGSL has no screen-space derivatives: shaders take the size of a pixel in their own units
 * ("uPx") to soften their edges.
 */
object Chassis {
    /** The scene through the finder, in three bands (linear-ish RGB, 0..1), smoothed. */
    var sky by mutableStateOf(floatArrayOf(0.62f, 0.66f, 0.72f))
    var horizon by mutableStateOf(floatArrayOf(0.40f, 0.38f, 0.34f))
    var ground by mutableStateOf(floatArrayOf(0.30f, 0.25f, 0.20f))

    /**
     * Samples the finder's picture (a small bitmap the live depth already grabbed) into the three
     * bands, eased; only a clear change updates the state (each update redraws the brass).
     */
    /** The finder's picture, tiny and blurred: the bloom round the glass is made of it. */
    var glowImage by mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)

    /** Ticks when [glowImage]'s pixels change (the bitmap is reused). */
    var glowTick by mutableStateOf(0)
    private var glowBmp: android.graphics.Bitmap? = null
    private val glowAcc = FloatArray(24 * 36 * 3)
    private var glowPrimed = false
    private var glowDark = 0
    private val glowPx = IntArray(24 * 36)
    private val glowMid = android.graphics.Bitmap.createBitmap(24, 36, android.graphics.Bitmap.Config.ARGB_8888)
    private val glowCv = android.graphics.Canvas(glowMid)
    private val glowPaint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)

    /**
     * Folds a fresh preview frame into [glowImage]: the part the finder shows ([crop]), shrunk
     * to 24 x 36, softened (through 8 x 12) and blended over the last ones so the glow moves
     * smoothly rather than in steps.
     */
    fun sampleGlow(bmp: android.graphics.Bitmap, crop: Float) {
        val w = bmp.width; val h = bmp.height
        val cw = (w / (1.125f * crop)).toInt().coerceIn(4, w); val ch = (h / crop).toInt().coerceIn(4, h)
        glowCv.drawBitmap(bmp, android.graphics.Rect((w - cw) / 2, (h - ch) / 2, (w + cw) / 2, (h + ch) / 2), android.graphics.Rect(0, 0, 24, 36), glowPaint)
        val small = android.graphics.Bitmap.createScaledBitmap(glowMid, 8, 12, true)
        val soft = android.graphics.Bitmap.createScaledBitmap(small, 24, 36, true); small.recycle()
        soft.getPixels(glowPx, 0, 24, 0, 0, 24, 36); soft.recycle()
        // (a black frame from the preview isn't the scene: skip it unless the dark lasts)
        var sum = 0L; for (c in glowPx) sum += ((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF)
        if (sum / (glowPx.size * 3) < 6 && glowPrimed) { if (++glowDark < 8) return } else glowDark = 0
        val k = if (glowPrimed) 0.40f else 1f
        for (i in glowPx.indices) {
            val c = glowPx[i]
            glowAcc[i * 3] += (((c shr 16) and 0xFF) - glowAcc[i * 3]) * k
            glowAcc[i * 3 + 1] += (((c shr 8) and 0xFF) - glowAcc[i * 3 + 1]) * k
            glowAcc[i * 3 + 2] += ((c and 0xFF) - glowAcc[i * 3 + 2]) * k
            glowPx[i] = (0xFF shl 24) or (glowAcc[i * 3].toInt() shl 16) or (glowAcc[i * 3 + 1].toInt() shl 8) or glowAcc[i * 3 + 2].toInt()
        }
        glowPrimed = true
        val b = glowBmp ?: android.graphics.Bitmap.createBitmap(24, 36, android.graphics.Bitmap.Config.ARGB_8888).also { glowBmp = it; glowImage = it.asImageBitmap() }
        b.setPixels(glowPx, 0, 24, 0, 0, 24, 36)
        glowTick++
    }

    private var darkRun = 0
    fun sample(bmp: android.graphics.Bitmap) {
        val w = bmp.width; val h = bmp.height
        if (w < 4 || h < 4) return
        fun band(y0: Int, y1: Int): FloatArray {
            var r = 0f; var g = 0f; var b = 0f; var n = 0
            var y = y0
            while (y < y1) {
                var x = 0
                while (x < w) {
                    val c = bmp.getPixel(x, y)
                    r += (c shr 16 and 0xFF) / 255f; g += (c shr 8 and 0xFF) / 255f; b += (c and 0xFF) / 255f; n++
                    x += 8
                }
                y += 8
            }
            return if (n == 0) floatArrayOf(0.5f, 0.5f, 0.5f) else floatArrayOf(r / n, g / n, b / n)
        }
        val s = band(0, h * 3 / 8); val m = band(h * 3 / 8, h * 5 / 8); val gr = band(h * 5 / 8, h)
        // a frame that comes back (near) black while the camera is busy or re-opening isn't the
        // scene: ignore a sudden collapse unless it lasts (a real dark scene persists)
        val lum = (s.sum() + m.sum() + gr.sum()) / 9f
        val was = (sky.sum() + horizon.sum() + ground.sum()) / 9f
        if (lum < 0.04f || lum < was * 0.4f) { if (++darkRun < 6) return } else darkRun = 0
        fun ease(old: FloatArray, new: FloatArray) = FloatArray(3) { old[it] + (new[it] - old[it]) * 0.25f }
        fun far(a: FloatArray, b: FloatArray) = (0..2).sumOf { kotlin.math.abs(a[it] - b[it]).toDouble() } > 0.03
        val ns = ease(sky, s); val nm = ease(horizon, m); val ng = ease(ground, gr)
        if (far(ns, sky) || far(nm, horizon) || far(ng, ground)) { sky = ns; horizon = nm; ground = ng }
    }

    /** Sets the shared environment's uniforms on [sh] (read in a draw scope: redraws follow the light and the scene). */
    fun bind(sh: RuntimeShader) {
        val l = BrassLight.dir
        sh.setFloatUniform("uLightDir", l.x, l.y)
        sh.setFloatUniform("uSkyTone", sky[0], sky[1], sky[2])
        sh.setFloatUniform("uHorizonTone", horizon[0], horizon[1], horizon[2])
        sh.setFloatUniform("uGroundTone", ground[0], ground[1], ground[2])
        runCatching { sh.setFloatUniform("uSteel", 1f) }      // (absent where a shader has no plain metal)
        val u = BrassLight.up
        sh.setFloatUniform("uUp", u.x, u.y)
    }
}

/** The shared studio every surface reflects (the guide's evalEnvironment), and brass's colour. */
const val ENV_CORE = """
uniform float2 uLightDir;
uniform float3 uSkyTone;
uniform float3 uHorizonTone;
uniform float3 uGroundTone;
uniform float2 uUp;          // which way is up on the screen (unit): the world turns with the phone

float eHash(float2 q) { return fract(sin(dot(q, float2(127.1, 311.7))) * 43758.5453); }
float eNoise(float2 q) {
    float2 i = floor(q); float2 f = fract(q);
    f = f * f * (3.0 - 2.0 * f);
    float a = eHash(i), b = eHash(i + float2(1.0, 0.0)), c = eHash(i + float2(0.0, 1.0)), d = eHash(i + float2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}
float eFbm(float2 q) { return 0.55 * eNoise(q) + 0.28 * eNoise(q * 2.03 + 7.1) + 0.17 * eNoise(q * 4.11 + 3.3); }

// An outdoor world for the metal to mirror: sky and clouds and the sun, a tree line on the
// horizon, water reflecting the sky below it, grass and earth nearer; tinted by the scene the
// finder sees. Rougher surfaces see it blurred (detail fades, bands soften).
half3 evalEnvironment(float3 N, float roughness) {
    float3 R = reflect(float3(0.0, 0.0, -1.0), normalize(N));
    R.xy += uLightDir * 0.55;
    R = normalize(R);
    // into the world's frame: up is the way gravity says, not the screen's top
    float2 hAx = float2(-uUp.y, uUp.x);
    R.xy = float2(dot(R.xy, hAx), -dot(R.xy, uUp));
    float el = -R.y;                                   // up is up
    float az = atan(R.x, max(R.z, 0.05));
    // the world seen softly even in mirror polish (an impression, not a picture)
    float detail = (1.0 - smoothstep(0.02, 0.45, roughness)) * 0.35;
    float soft = 0.06 + roughness * 0.5;
    half3 skyT = half3(uSkyTone); half3 horT = half3(uHorizonTone); half3 gndT = half3(uGroundTone);
    // the sky: deeper blue overhead, paler down to the haze; clouds drifting across
    half3 skyCol = mix(half3(0.72, 0.80, 0.90), half3(0.36, 0.52, 0.78), smoothstep(0.0, 0.8, el));
    skyCol = mix(skyCol, skyT * 1.1, 0.35);
    float cl = 0.7 * eNoise(float2(az * 1.3, el * 2.6 + 1.7)) + 0.3 * eNoise(float2(az * 2.6, el * 5.2 + 4.1));
    float cloud = smoothstep(0.42, 0.85, cl) * smoothstep(0.02, 0.25, el);
    skyCol = mix(skyCol, half3(0.97, 0.96, 0.93), cloud * (0.35 + 0.65 * detail));
    skyCol -= half3(0.08) * smoothstep(0.62, 0.9, cl) * cloud * detail;          // their shaded undersides
    // the tree line: an uneven canopy along the horizon
    float canopy = 0.04 + 0.09 * eNoise(float2(az * 3.5, 3.1));
    float tree = smoothstep(canopy + soft, canopy - soft, el) * smoothstep(-0.06, 0.0, el + soft * 0.5);
    half3 treeCol = mix(half3(0.08, 0.13, 0.08), horT * 0.45, 0.35) * (0.85 + 0.3 * eNoise(float2(az * 8.0, el * 10.0)) * detail);
    // water: a band below the horizon mirroring the sky, broken by ripples
    float wv = smoothstep(0.0, -soft, el) * smoothstep(-0.34, -0.18, el);
    float ripple = eNoise(float2(az * 2.0, el * 20.0)) * detail;
    float3 Rw = float3(R.x + (ripple - 0.5) * 0.12, -R.y, R.z);
    half3 waterCol = mix(half3(0.30, 0.42, 0.52), skyCol * 0.85, 0.6) * (0.85 + 0.25 * ripple);
    // nearer ground: grass and earth
    float gr = eNoise(float2(az * 2.0, el * 3.5));
    half3 grass = mix(half3(0.20, 0.27, 0.12), half3(0.36, 0.30, 0.18), gr);
    grass = mix(grass, gndT * 0.8, 0.35);
    half3 env = mix(mix(grass, waterCol, wv), skyCol, smoothstep(-soft, soft, el));
    env = mix(env, treeCol, tree);
    // blur for rough metal: everything drifts toward the scene's average
    half3 avg = (skyT + horT + gndT) / 3.0 * 0.8 + half3(0.06);
    env = mix(env, avg, smoothstep(0.15, 0.6, roughness) * 0.6);
    // the sun, and a soft rim light
    float3 keyDir = normalize(float3(uLightDir.x * 1.1, uLightDir.y * 1.1, 0.65));
    float3 rimDir = normalize(float3(-uLightDir.x * 0.9, -uLightDir.y * 0.4, 0.60));
    float specExp = mix(90.0, 3.0, roughness * roughness);
    float specGain = mix(1.30, 0.18, roughness);
    float keySpec = pow(max(dot(R, keyDir), 0.0), specExp);
    float rimSpec = pow(max(dot(R, rimDir), 0.0), specExp * 0.6);
    env += half3(1.0, 0.97, 0.90) * (keySpec * specGain + rimSpec * specGain * 0.40);
    return env;
}

half3 toBrass(half3 envColor) { return envColor * half3(1.00, 0.81, 0.40); }
uniform float uSteel;        // 1: the body's metal is stainless steel (brass stays only where paint wears through)
half3 toMetal(half3 envColor) { return uSteel > 0.5 ? envColor * half3(0.86, 0.89, 0.93) + half3(0.015) : toBrass(envColor); }

// glossy black enamel: deep black, only the bright parts of the world caught in it
half3 enamel(float3 N, float roughness) { half3 e = evalEnvironment(N, roughness); return half3(0.014, 0.015, 0.017) + e * e * 0.18; }
"""

/**
 * A soft release in mirror-polished brass, after the classic ones: a stepped outer collar with
 * a dark seating groove, a convex shoulder, a smoothly dished centre, and (uSocket) the threaded
 * cable-release socket. Drawn in a square; uv runs -1..1 across it.
 */
const val SOFT_RELEASE_AGSL = ENV_CORE + """
uniform float2 iResolution;
uniform float uPx;          // a pixel, in uv units
uniform float uPressed;     // 0..1
uniform float uSocket;      // 1: the threaded socket at the centre
uniform float uEnamel;      // 1: black enamel, brass showing only at the worn outer edge

half4 main(float2 p) {
    float2 uv = (p / iResolution) * 2.0 - 1.0;
    float r = length(uv);
    if (r > 1.0 + uPx) return half4(0.0);
    float2 dir = uv / max(r, 1e-4);
    float3 N = float3(0.0, 0.0, 1.0);
    float rough = 0.03;
    float ao = 1.0;
    // pressed, the button (inside the collar) sinks: it is seen smaller and a touch lower, the
    // seating groove widening round it; the collar doesn't move
    if (r <= 0.845) {
        float2 ub = (uv - float2(0.0, 0.035 * uPressed)) * (1.0 + 0.10 * uPressed);
        float rb = length(ub);
        if (rb > 0.845) {                                // the widened groove: dark
            half3 g = toMetal(evalEnvironment(normalize(float3(dir * 0.6, 0.8)), 0.3)) * 0.18;
            return half4(g, 1.0);
        }
        uv = ub; r = rb; dir = uv / max(r, 1e-4);
    }
    if (r > 0.95) {                                  // the outer chamfer, falling away
        N = normalize(float3(dir * 0.75, 0.66)); rough = 0.06;
    } else if (r > 0.885) {                          // the collar's flat top: satin
        N = float3(0.0, 0.0, 1.0); rough = 0.22;
    } else if (r > 0.845) {                          // the seating groove: dark
        float t = (r - 0.845) / 0.04;
        N = normalize(float3(dir * (t - 0.5) * 1.4, 0.8)); ao = 0.25 + 0.5 * abs(t - 0.5) * 2.0;
    } else if (r > 0.66) {                           // the convex shoulder
        float t = (r - 0.66) / 0.185;
        N = normalize(float3(dir * t * 0.9, 1.0));
    } else {                                         // the dished centre
        float t = r / 0.66;
        N = normalize(float3(-dir * t * 0.26, 1.0));
        if (uSocket > 0.5) {
            if (r < 0.10) { return half4(half3(0.02, 0.015, 0.01), 1.0); }          // the bore
            if (r < 0.15) {                                                        // threads
                float th = 0.5 + 0.5 * sin(r * 260.0);
                half3 c = toMetal(evalEnvironment(normalize(float3(-dir * 0.6, 0.8)), 0.35)) * (0.25 + 0.35 * th);
                return half4(c * (1.0 - 0.15 * uPressed), 1.0);
            }
            if (r < 0.20) { N = normalize(float3(-dir * 0.8, 0.6)); rough = 0.05; } // its chamfer
        }
    }
    half3 col = toMetal(evalEnvironment(N, rough)) * ao;
    if (uEnamel > 0.5 && r < 0.955) col = enamel(N, 0.10) * ao;          // black, the brass only at the rim
    // pressed: only the button sinks (the collar stays put): its face darkens under the thumb
    // and the seating groove round it opens up
    if (r < 0.845) col *= 1.0 - 0.20 * uPressed;
    else if (r < 0.885) col *= 1.0 - 0.5 * uPressed;
    float a = smoothstep(1.0 + uPx, 1.0 - uPx, r);
    return half4(col * a, a);
}
"""

/**
 * A lens ring: a black-enamel cylinder over brass, turning (uRingOffsetPx) under the engraved
 * figures. In its grip band (uKnurled), staggered rows of square pyramid studs, as on the classic
 * rubber-and-metal focus rings. Wear in three stages: glossy enamel, dull exposed brass where it
 * has rubbed through, burnished brass on the stud tips and the rims' edges.
 */
const val BARREL_CYLINDER_AGSL = ENV_CORE + """
uniform float2 iResolution;
uniform float uRingOffsetPx;
uniform float uCylRadius;
uniform float uKnurled;
uniform float uSeed;
uniform float uPx;           // a pixel, in v units (0..1 down the ring)

float hash(float2 q) { return fract(sin(dot(q, float2(12.9898, 78.233)) + uSeed) * 43758.5453); }

half4 main(float2 fragCoord) {
    float w = iResolution.x;
    float h = iResolution.y;
    float cx = w * 0.5;
    float v = fragCoord.y / h;
    float dx = clamp((fragCoord.x - cx) / uCylRadius, -0.999, 0.999);
    float theta = asin(dx);
    float cylFade = cos(theta);
    float arcX = theta * uCylRadius + uRingOffsetPx;
    // the barrel's profile: a gentle bulge, rolling off at the rims
    float ny = (v - 0.5) * 1.15;
    float3 N = normalize(float3(sin(theta) * 0.85, ny, cos(theta)));
    float apexDist = 1.0;
    float grooveAO = 1.0;
    if (uKnurled > 0.5 && v > 0.74 && v < 0.96) {
        float gripV = (v - 0.74) / 0.22;
        float cellSize = (h * 0.22) * 0.5;
        float row = floor(gripV * 2.0);
        float2 g = float2(arcX / cellSize + 0.5 * row, gripV * 2.0);     // odd rows half a stud along
        float2 cell = fract(g) - 0.5;
        apexDist = max(abs(cell.x), abs(cell.y)) * 2.0;
        float edge = uPx / 0.11;                                          // a pixel, in cell units
        grooveAO = mix(1.0, 0.18, smoothstep(0.78 - edge, 1.0, apexDist));
        float2 facet = (abs(cell.x) > abs(cell.y)) ? float2(sign(cell.x) * 0.95, 0.0) : float2(0.0, sign(cell.y) * 0.95);
        // soften the facet crease over a pixel or two so the studs don't shimmer
        float crease = smoothstep(0.0, edge * 2.0, abs(abs(cell.x) - abs(cell.y)));
        N = normalize(N + float3(facet * crease, 0.0));
    }
    // the surface itself, mapped to the ring (it turns with it): enamel's fine orange-peel
    // ripple, hairline scratches running round the ring, now and then a deeper one
    float2 sp = float2(arcX, v * h);
    float peel = eNoise(sp * 0.45) - 0.5;
    float hair = eNoise(float2(sp.x * 0.012, sp.y * 1.6)) - 0.5;                 // long, along the ring
    float deep = smoothstep(0.985, 1.0, eNoise(float2(sp.x * 0.004 + uSeed, sp.y * 0.9)));
    N = normalize(N + float3(peel * 0.018, peel * 0.018 + hair * 0.03, 0.0));
    // where it wears: the rims, the stud tips, unevenly round the ring
    float rimWear = smoothstep(0.025, 0.0, v) + smoothstep(0.975, 1.0, v);
    float tipWear = smoothstep(0.42, 0.05, apexDist) * uKnurled;
    float organic = 0.40 + 0.60 * (0.55 + 0.45 * sin(arcX * 0.018 + uSeed + sin(arcX * 0.047)))
                  * (0.7 + 0.3 * hash(floor(float2(arcX * 0.08, v * 30.0))));
    float wear = clamp((rimWear * 0.85 + tipWear * 0.95) * organic, 0.0, 1.0);
    // glossy black enamel: deep black, only the bright parts of the studio show in it
    half3 e = evalEnvironment(N, 0.30);                   // the enamel sees the world softly: no busy streaks
    half3 enamel = half3(0.014, 0.015, 0.017) + e * e * 0.16;
    float burnish = smoothstep(0.35, 0.85, wear);
    half3 brass = toBrass(evalEnvironment(N, mix(0.42, 0.05, burnish)));
    half3 col = mix(enamel, brass, smoothstep(0.20, 0.48, wear)) * grooveAO * (0.25 + 0.75 * cylFade);
    col += half3(0.05, 0.045, 0.04) * deep * cylFade;                                // a deeper scratch catching light
    // the ring's edges: a hairline gap to the next ring
    col *= smoothstep(0.0, uPx * 2.0, v) * smoothstep(1.0, 1.0 - uPx * 2.0, v);
    return half4(col, 1.0);
}
"""

/**
 * The film rewind knob, seen from above, in mirror-polished brass: a 45-degree chamfered drum,
 * its top crossed by the fold-out crank bar sitting in a milled slot, a hinge pin at one end of
 * it and the rewind arrow engraved and enamelled black on the face. It turns (uKnobAngle) as the
 * film winds on; the light stays where it is.
 */
const val REWIND_KNOB_AGSL = ENV_CORE + """
uniform float2 iResolution;
uniform float uPx;
uniform float uKnobAngle;      // radians
uniform float uEnamel;         // 1: black enamel over brass (brass at the worn knurl), arrow filled cream

half4 main(float2 p) {
    float2 uv = (p / iResolution) * 2.0 - 1.0;
    float r = length(uv);
    if (r > 1.0 + uPx) return half4(0.0);
    float ca = cos(uKnobAngle), sa = sin(uKnobAngle);
    float2 q = float2(ca * uv.x - sa * uv.y, sa * uv.x + ca * uv.y);     // the knob's own frame
    float2 dir = uv / max(r, 1e-4);
    float3 N = float3(0.0, 0.0, 1.0);
    float rough = 0.03;
    float ao = 1.0;
    half3 ink = half3(-1.0);
    if (r > 0.80) {                                          // the chamfer, 45 degrees, polished
        N = normalize(float3(dir * 0.70, 0.71)); rough = 0.04;
        // fine knurl on the drum's edge glimpsed past the chamfer
        float k = 0.5 + 0.5 * sin(atan(q.y, q.x) * 90.0);
        if (r > 0.95) { N = normalize(float3(dir * 0.9 + float2(-dir.y, dir.x) * (k - 0.5) * 0.4, 0.4)); rough = 0.12; }
    } else {
        // the top face, very slightly domed
        N = normalize(float3(uv * 0.10, 1.0));
        // the crank bar: a flat strip straight across the face, its top flat and polished, with
        // crisp edges (a hairline of shadow either side) and a small engraved circle at one end
        float barW = 0.27;
        float slotW = barW + 0.035;
        if (abs(q.y) < slotW) {
            if (abs(q.y) < barW) {
                N = float3(0.0, 0.0, 1.0); rough = 0.03;
                if (abs(q.y) > barW - 0.035) {                       // its edge, a narrow bevel
                    float2 nrm = float2(0.0, sign(q.y) * 0.6);
                    float2 nw = float2(ca * nrm.x + sa * nrm.y, -sa * nrm.x + ca * nrm.y);
                    N = normalize(float3(nw, 0.8));
                }
                float cr = length(q - float2(-0.50, 0.0));
                if (abs(cr - 0.075) < 0.016) ink = half3(0.10, 0.08, 0.05);      // the engraved circle
            } else {
                ao = 0.22;                                           // the gap either side of the bar
            }
        }
        // the engraved rewind arrow: an arc round the face with a head, black enamel
        float ar = length(q); float an = atan(q.y, q.x);
        if (abs(q.y) > slotW + 0.03) {
            float band = abs(ar - 0.58);
            if (band < 0.035 && an > 0.5 && an < 2.4) ink = half3(0.02);
            // the head at the arc's end
            float2 h0 = float2(cos(2.4), sin(2.4)) * 0.58;
            float2 dh = q - h0;
            float2 t = float2(-sin(2.4), cos(2.4));          // along the arc
            float2 nn = float2(cos(2.4), sin(2.4));          // across it
            float u = dot(dh, t); float w = abs(dot(dh, nn));
            if (u > 0.0 && u < 0.16 && w < (0.16 - u) * 0.7) ink = half3(0.02);
        }
    }
    half3 col = toMetal(evalEnvironment(N, rough)) * ao;
    if (uEnamel > 0.5) {
        // black enamel everywhere but the knurled edge, where it has worn through to brass
        if (r < 0.95) col = enamel(N, 0.10) * ao;
        if (ink.r >= 0.0) ink = half3(0.86, 0.82, 0.74);                  // the engraving filled cream
    }
    if (ink.r >= 0.0) col = mix(col, ink + half3(0.03) * evalEnvironment(N, 0.1).r, 0.92);     // enamel keeps a faint gloss
    float a = smoothstep(1.0 + uPx, 1.0 - uPx, r);
    // the drum's shadow edge outside the knob is left to the deck
    return half4(col * a, a);
}
"""

/** A pilot jewel: a chamfered polished-brass bezel round a domed emerald glass, lit from inside when on. */
const val JEWEL_AGSL = ENV_CORE + """
uniform float2 iResolution;
uniform float uPx;
uniform float uLit;         // 0..1

half4 main(float2 p) {
    float2 uv = (p / iResolution) * 2.0 - 1.0;
    float r = length(uv);
    if (r > 1.0 + uPx) return half4(0.0);
    float2 dir = uv / max(r, 1e-4);
    half3 col;
    if (r > 0.68) {                                          // the bezel
        float t = (r - 0.68) / 0.32;
        float3 N = normalize(float3(dir * (t - 0.35) * 1.3, 0.8));
        col = toMetal(evalEnvironment(N, 0.04)) * (r < 0.71 ? 0.4 : 1.0);
    } else {                                                 // the glass dome
        float gr = r / 0.68;
        float gz = sqrt(max(1.0 - gr * gr, 0.0));
        float3 N = normalize(float3(uv / 0.68, gz));
        half3 glassRefl = evalEnvironment(N, 0.02);
        float fres = 0.04 + 0.96 * pow(1.0 - gz, 5.0);
        // inside: dark green unlit; lit, an emerald glow brightest at the centre
        half3 body = mix(half3(0.02, 0.06, 0.035), half3(0.10, 0.95, 0.45), uLit * (1.0 - gr * 0.65));
        body += half3(0.6, 1.0, 0.75) * uLit * smoothstep(0.35, 0.0, gr) * 0.6;      // the LED's hot core
        col = body * (1.0 - fres) + glassRefl * (fres + 0.06);
        // the sharp glint of the dome
        float3 L = normalize(float3(uLightDir * 1.1, 0.65));
        col += half3(1.0) * pow(max(dot(reflect(float3(0.0, 0.0, -1.0), N), L), 0.0), 160.0) * 0.9;
    }
    float a = smoothstep(1.0 + uPx, 1.0 - uPx, r);
    return half4(col * a, a);
}
"""

/**
 * A slide switch: a polished-brass pill bezel round a recessed track of brushed brass (fine
 * grooves along it), and a polished brass ball that slides along (uKnob -1..1) casting a
 * contact shadow on the track.
 */
const val SLIDE_SWITCH_AGSL = ENV_CORE + """
uniform float2 iResolution;
uniform float uPx;          // a pixel, in uv-y units
uniform float uKnob;        // -1 left .. 1 right

half4 main(float2 p) {
    float aspect = iResolution.x / iResolution.y;
    float2 uv = float2((p.x / iResolution.x * 2.0 - 1.0) * aspect, p.y / iResolution.y * 2.0 - 1.0);
    // distance to the pill (a capsule along x)
    float hx = aspect - 1.0;
    float2 c = float2(clamp(uv.x, -hx, hx), 0.0);
    float2 d = uv - c;
    float r = length(d);
    if (r > 1.0 + uPx) return half4(0.0);
    float2 dir = d / max(r, 1e-4);
    half3 col;
    if (r > 0.70) {                                          // the bezel, rounded
        float t = (r - 0.70) / 0.30;
        float3 N = normalize(float3(dir * (t - 0.4) * 1.4, 0.75));
        col = toMetal(evalEnvironment(N, 0.04));
        col *= (r < 0.73) ? 0.35 : 1.0;                      // the step down into the track
    } else {                                                 // the brushed track
        float groove = 0.5 + 0.5 * sin(uv.y * 95.0);
        float3 N = normalize(float3(0.0, (groove - 0.5) * 0.12, 1.0));
        col = toMetal(evalEnvironment(N, 0.28)) * 0.62;
        col *= 0.78 + 0.22 * smoothstep(0.70, 0.40, r);       // shadowed under the bezel's lip
    }
    // the ball
    float2 kc = float2(uKnob * hx, 0.0);
    float kr = 0.62;
    float2 kd = (uv - kc) / kr;
    float kl = length(kd);
    // its contact shadow, offset away from the light
    float sh = smoothstep(1.25, 0.7, length(uv - kc - float2(-uLightDir.x, -uLightDir.y) * 0.18) / kr);
    col *= 1.0 - 0.55 * sh * step(0.70, 0.70 + 0.0) * step(r, 0.70);
    if (kl < 1.0) {
        float kz = sqrt(1.0 - kl * kl);
        float3 N = normalize(float3(kd, kz));
        half3 ball = toMetal(evalEnvironment(N, 0.03));
        float ka = smoothstep(1.0, 1.0 - uPx * 2.0 / kr, kl);
        col = mix(col, ball, ka);
    }
    float a = smoothstep(1.0 + uPx, 1.0 - uPx, r);
    return half4(col * a, a);
}
"""

/**
 * A polished-brass bezel round a little window of curved glass (uRound 1: a circle, 0: a pill),
 * drawn over what's behind the window: only the bezel and the glass's reflection are painted,
 * the window's middle left clear. uAr tints the glass's edge with a coated lens's magenta/cyan.
 */
const val GLASS_WINDOW_AGSL = ENV_CORE + """
uniform float2 iResolution;
uniform float uPx;
uniform float uRound;
uniform float uAr;
uniform float uBrass;        // 1: a polished metal bezel; 0: the window just set into black enamel
uniform float uKnurl;        // 1: the bezel is knurled black enamel, worn to brass on the ridges

half4 main(float2 p) {
    float aspect = iResolution.x / iResolution.y;
    float2 uv = float2((p.x / iResolution.x * 2.0 - 1.0) * aspect, p.y / iResolution.y * 2.0 - 1.0);
    float hx = uRound > 0.5 ? 0.0 : aspect - 1.0;
    float2 c = float2(clamp(uv.x, -hx, hx), 0.0);
    float2 d = uv - c;
    float r = length(d);
    if (r > 1.0 + uPx) return half4(0.0);
    float2 dir = d / max(r, 1e-4);
    if (r > 0.80) {                                          // the bezel
        float t = (r - 0.80) / 0.20;
        float3 N = normalize(float3(dir * (t - 0.4) * 1.5, 0.7));
        half3 col = uBrass > 0.5 ? toMetal(evalEnvironment(N, 0.04)) : enamel(N, 0.08);
        if (uKnurl > 0.5) {
            float ang = atan(d.y, d.x);
            float ridge = 0.5 + 0.5 * cos(ang * 72.0);
            float3 KN = normalize(N + float3(float2(-dir.y, dir.x) * sin(ang * 72.0) * 0.5, 0.0));
            float wear = smoothstep(0.80, 0.98, ridge) * (0.45 + 0.55 * sin(ang * 3.0 + 1.3) * 0.5 + 0.275) * smoothstep(0.84, 0.96, r);
            col = mix(enamel(KN, 0.10) * (0.55 + 0.45 * ridge), toBrass(evalEnvironment(KN, mix(0.42, 0.06, wear))), smoothstep(0.25, 0.6, wear));
        }
        col *= (r < 0.83) ? 0.3 : 1.0;
        float a = smoothstep(1.0 + uPx, 1.0 - uPx, r);
        return half4(col * a, a);
    }
    // the glass: curved, so its reflection grows toward the rim (Fresnel), a sweep across it
    float gr = r / 0.80;
    float gz = sqrt(max(1.0 - gr * gr * 0.6, 0.0));
    float3 N = normalize(float3(d / 0.80 * 0.6, gz));
    float fres = 0.015 + 0.30 * pow(gr, 5.0);
    half3 refl = evalEnvironment(N, 0.02);
    float sweep = smoothstep(0.06, 0.0, abs(uv.x * 0.7 + uv.y - 0.15 + uLightDir.x * 0.6)) * 0.18;
    half3 ar = mix(half3(0.0), mix(half3(0.55, 0.15, 0.55), half3(0.15, 0.55, 0.60), 0.5 + 0.5 * dir.x), smoothstep(0.6, 1.0, gr) * uAr);
    half3 col = refl * (fres + sweep) + ar * 0.22;
    float a = clamp((fres + sweep) * 0.8 + length(ar) * 0.15, 0.0, 1.0);
    return half4(col, a);
}
"""

/** A loupe's lens on what's under it: magnified toward the middle, pincushioned, colour fringing at the rim, darker at the edge. */
const val LOUPE_LENS_AGSL = """
uniform shader image;
uniform float2 size;
half4 main(float2 p) {
    float2 c = size * 0.5;
    float2 d = (p - c) / c;
    float r2 = dot(d, d);
    float k = 0.22;
    float2 sg = c + (p - c) * (0.82 - k * r2 * 0.4);
    float2 sr = c + (p - c) * (0.82 - k * r2 * 0.4 - 0.012 * r2);
    float2 sb = c + (p - c) * (0.82 - k * r2 * 0.4 + 0.012 * r2);
    half3 col = half3(image.eval(sr).r, image.eval(sg).g, image.eval(sb).b);
    col *= 1.0 - 0.45 * r2 * r2;
    return half4(col, 1.0);
}
"""

/**
 * The parallax control as one satin brass plate: a slot cut across it (dark, its walls lit at
 * the top edge), a polished ball riding in it (uKnob -1 off .. 1 on), and the round seat of the
 * little window beside it. The label is engraved on top separately.
 */
const val PARALLAX_PLATE_AGSL = ENV_CORE + """
uniform float2 iResolution;
uniform float uPx;           // a pixel, in plate-height units (uv.y spans -1..1)
uniform float uKnob;
uniform float2 uSlot;        // slot: centre y, half length (in uv-x units, measured from slotCx)
uniform float uSlotCx;
uniform float2 uWin;         // window centre (uv)

float rrect(float2 p, float2 b, float r) { float2 q = abs(p) - b + r; return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r; }

half4 main(float2 p) {
    float aspect = iResolution.x / iResolution.y;
    float2 uv = float2((p.x / iResolution.x * 2.0 - 1.0) * aspect, p.y / iResolution.y * 2.0 - 1.0);
    float plate = rrect(uv, float2(aspect - 0.04, 0.96), 0.28);
    if (plate > uPx) return half4(0.0);
    // satin brass, the edges bevelled and polished
    float3 N = float3(0.0, 0.0, 1.0);
    float rough = 0.26;
    if (plate > -0.10) { float2 g = normalize(float2(sign(uv.x) * step(abs(uv.y) * aspect, abs(uv.x)), sign(uv.y) * step(abs(uv.x), abs(uv.y) * aspect)) + 1e-4); N = normalize(float3(g * 0.7, 0.7)); rough = 0.05; }
    half3 col = toMetal(evalEnvironment(N, rough)) + half3(0.10, 0.075, 0.035);   // satin brass keeps its warmth in the dark
    // the slot
    float2 sp = uv - float2(uSlotCx, uSlot.x);
    float slot = rrect(sp, float2(uSlot.y + 0.30, 0.30), 0.30);
    if (slot < 0.0) {
        float depth = smoothstep(0.0, -0.12, slot);
        col = toMetal(evalEnvironment(float3(0.0, 0.0, 1.0), 0.4)) * 0.10 * (1.0 - depth * 0.5);
        if (sp.y < 0.0 && slot > -0.08) col += toMetal(evalEnvironment(float3(0.0, 0.7, 0.7), 0.1)) * 0.25;   // the top wall caught by light
    } else col *= 1.0 - 0.35 * smoothstep(0.08, 0.0, slot);             // the slot's chamfered lip
    // the ball in the slot
    float2 kc = float2(uSlotCx + uKnob * uSlot.y, uSlot.x);
    float2 kd = (uv - kc) / 0.27;
    float kl = length(kd);
    if (kl < 1.0) {
        float3 KN = normalize(float3(kd, sqrt(1.0 - kl * kl)));
        col = mix(col, toMetal(evalEnvironment(KN, 0.03)), smoothstep(1.0, 1.0 - uPx * 6.0, kl));
    }
    // the window's seat: a polished ring sunk into the plate
    float wr = length(uv - uWin);
    if (wr < 0.40 && wr > 0.30) { float t = (wr - 0.30) / 0.10; col = toMetal(evalEnvironment(normalize(float3((uv - uWin) / wr * (t - 0.5) * 1.2, 0.8)), 0.04)); }
    float a = smoothstep(uPx, -uPx, plate);
    return half4(col * a, a);
}
"""

/**
 * The parallax control on a black enamel plate (portrait), its edge a polished brass chamfer:
 * a vertical slot lined in brass with a polished ball riding in it (uKnob -1 down/off .. 1 up/on),
 * and below, the polished brass seat of a pill window. Units: half the plate's width.
 */
const val PARALLAX_ENAMEL_AGSL = ENV_CORE + """
uniform float2 iResolution;
uniform float uPx;           // a pixel, in these units
uniform float uKnob;
uniform float3 uSlot;        // centre y, half length, half width
uniform float4 uWin;         // centre y, half width, half height (of the opening), seat width

float rrect(float2 p, float2 b, float r) { float2 q = abs(p) - b + r; return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r; }

half4 main(float2 p) {
    float hw = iResolution.x * 0.5;
    float2 uv = (p - iResolution * 0.5) / hw;
    float hh = iResolution.y / iResolution.x;
    float cr = 0.174; float ew = 0.087;                       // corner 4 dp, rounded edge 2 dp (as the DOF plate)
    float plate = rrect(uv, float2(0.98, hh - 0.02), cr);
    if (plate > uPx) return half4(0.0);
    half3 col;
    if (plate > -ew) {
        float t = (plate + ew) / ew;
        float e = 0.001;
        float2 g = normalize(float2(rrect(uv + float2(e, 0.0), float2(0.98, hh - 0.02), cr) - rrect(uv - float2(e, 0.0), float2(0.98, hh - 0.02), cr),
                                    rrect(uv + float2(0.0, e), float2(0.98, hh - 0.02), cr) - rrect(uv - float2(0.0, e), float2(0.98, hh - 0.02), cr)) + 1e-6);
        col = enamel(normalize(float3(g * (0.3 + t * 0.6), 0.7)), 0.10);
    } else {
        // glossy black enamel: deep, only the bright parts of the world in it
        col = enamel(float3(0.0, 0.0, 1.0), 0.18);
    }
    // the slot, cut into the enamel
    float2 sp = uv - float2(0.0, uSlot.x);
    float slot = rrect(sp, float2(uSlot.z, uSlot.y + uSlot.z), uSlot.z);
    if (slot < 0.0) {
        col = half3(0.012, 0.010, 0.008);                                   // its dark depth
    } else if (slot < 0.10) {
        float t = slot / 0.10;
        float2 g = normalize(sp - float2(0.0, clamp(sp.y, -uSlot.y, uSlot.y)) + 1e-4);
        col = enamel(normalize(float3(-g * (1.0 - t) * 0.9, 0.6)), 0.10);
    }
    // the ball
    float2 kc = float2(0.0, uSlot.x - uKnob * uSlot.y);
    float kr = uSlot.z * 0.92;
    float2 kd = (uv - kc) / kr;
    float kl = length(kd);
    // its shadow on the slot's floor
    if (slot < 0.0) col *= 1.0 - 0.5 * smoothstep(1.4, 0.8, length(uv - kc + uLightDir * 0.12) / kr);
    if (kl < 1.0) {
        float3 KN = normalize(float3(kd, sqrt(1.0 - kl * kl)));
        col = mix(col, toMetal(evalEnvironment(KN, 0.03)), smoothstep(1.0, 1.0 - uPx * 3.0 / kr, kl));
    }
    // the window's rim, a chamfer in the enamel (its opening is left to the drum behind)
    float2 wp = uv - float2(0.0, uWin.x);
    float wo = rrect(wp, uWin.yz, uWin.z);
    if (wo > 0.0 && wo < uWin.w) {
        float t = wo / uWin.w;
        float2 g = normalize(wp - float2(clamp(wp.x, -uWin.y + uWin.z, uWin.y - uWin.z), 0.0) + 1e-4);
        col = enamel(normalize(float3(g * (t - 0.45) * 1.4, 0.7)), 0.08);
    }
    float a = smoothstep(uPx, -uPx, plate);
    if (wo < 0.0) a = 0.0;                                                  // the opening: see through to the drum
    return half4(col * a, a);
}
"""

/** A small label plate: glossy black enamel, edged with a polished brass chamfer (the label is engraved on it separately). */
const val LABEL_PLATE_AGSL = ENV_CORE + """
uniform float2 iResolution;
uniform float uPx;           // a pixel, in half-height units
uniform float uCorner;
uniform float uEdge;

float rrect(float2 p, float2 b, float r) { float2 q = abs(p) - b + r; return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r; }

half4 main(float2 p) {
    float hh = iResolution.y * 0.5;
    float2 uv = (p - iResolution * 0.5) / hh;
    float hw = iResolution.x / iResolution.y;
    float cr = uCorner; float ew = uEdge;                      // in half-heights: corner 4 dp, rounded edge 2 dp
    float2 bx = float2(hw - 0.02, 0.98);
    float plate = rrect(uv, bx, cr);
    if (plate > uPx) return half4(0.0);
    half3 col;
    if (plate > -ew) {
        float t = (plate + ew) / ew;
        float e = 0.001;
        float2 g = normalize(float2(rrect(uv + float2(e, 0.0), bx, cr) - rrect(uv - float2(e, 0.0), bx, cr),
                                    rrect(uv + float2(0.0, e), bx, cr) - rrect(uv - float2(0.0, e), bx, cr)) + 1e-6);
        col = enamel(normalize(float3(g * (0.3 + t * 0.6), 0.7)), 0.10);     // its rounded edge, black too
    } else {
        col = enamel(float3(0.0, 0.0, 1.0), 0.18);
    }
    float a = smoothstep(uPx, -uPx, plate);
    return half4(col * a, a);
}
"""

/**
 * A toggle switch seen from above: a black enamel collar (a polished bevel catching light)
 * round a dark well, and a short brass bat-handle lever tipped toward uLever (-1 down off,
 * 1 up on), foreshortened as it leans, with a ball end.
 */
const val TOGGLE_AGSL = ENV_CORE + """
uniform float2 iResolution;
uniform float uPx;
uniform float uLever;

half4 main(float2 p) {
    float2 uv = (p / iResolution) * 2.0 - 1.0;
    float r = length(uv);
    if (r > 1.0 + uPx) return half4(0.0);
    float2 dir = uv / max(r, 1e-4);
    half3 col;
    if (r > 0.80) {                                          // the collar's rounded edge
        float t = (r - 0.80) / 0.20;
        col = enamel(normalize(float3(dir * (t * 0.9), 0.7)), 0.08);
    } else if (r > 0.42) {                                   // its flat face: black enamel
        col = enamel(float3(0.0, 0.0, 1.0), 0.16);
    } else {                                                 // the well the lever stands in
        col = half3(0.010, 0.009, 0.008) + enamel(normalize(float3(-dir * 0.5, 0.8)), 0.2) * 0.4 * smoothstep(0.30, 0.42, r);
    }
    // the lever: from the centre toward its tip, leaning up (on) or down (off)
    float lean = uLever;
    float2 tip = float2(0.0, -0.62 * lean);
    float2 pa = uv; float2 ba = tip;
    float h = clamp(dot(pa, ba) / max(dot(ba, ba), 1e-4), 0.0, 1.0);
    float w = mix(0.17, 0.11, h);                            // tapering toward the tip
    float dl = length(pa - ba * h) - w;
    // its shadow on the face, away from the light
    float2 so = -uLightDir * 0.10;
    float ds = length(uv - so - ba * clamp(dot(uv - so, ba) / max(dot(ba, ba), 1e-4), 0.0, 1.0)) - w;
    col *= 1.0 - 0.5 * smoothstep(0.10, -0.02, ds);
    if (dl < 0.0) {
        float across = clamp((length(pa - ba * h)) / w, 0.0, 1.0);
        float2 side = normalize(float2(-ba.y, ba.x) + 1e-4) * sign(dot(pa - ba * h, float2(-ba.y, ba.x)));
        float3 N = normalize(float3(side * across * 0.9, sqrt(max(1.0 - across * across * 0.8, 0.05))));
        col = mix(col, toMetal(evalEnvironment(N, 0.05)), smoothstep(0.0, -uPx * 3.0, dl));
    }
    // the ball at its end
    float kb = length(uv - tip) / 0.17;
    if (kb < 1.0) {
        float3 N = normalize(float3((uv - tip) / 0.17, sqrt(1.0 - kb * kb)));
        col = mix(col, toMetal(evalEnvironment(N, 0.03)), smoothstep(1.0, 1.0 - uPx * 6.0, kb));
    }
    float a = smoothstep(1.0 + uPx, 1.0 - uPx, r);
    return half4(col * a, a);
}
"""

/** Brushed stainless steel, the deck's top: fine streaks running across it, a soft sheen of the world. */
const val BRUSHED_STEEL_AGSL = ENV_CORE + """
uniform float2 iResolution;
uniform float uDens;

half4 main(float2 p) {
    float2 q = p / uDens;                                   // in dp: the grain keeps its scale
    float streak = eNoise(float2(q.x * 0.015, q.y * 2.2)) * 0.6 + eNoise(float2(q.x * 0.05, q.y * 5.0)) * 0.4;
    float3 N = normalize(float3(0.0, (streak - 0.5) * 0.10, 1.0));
    // the plate is gently curved front to back, catching the world across its width
    float v = p.y / iResolution.y;
    N = normalize(N + float3((p.x / iResolution.x - 0.5) * 0.25, (v - 0.4) * 0.35, 0.0));
    // matte: the world only as a soft average, a faint broad sheen, the grain doing the work
    half3 col = toMetal(evalEnvironment(N, 0.75)) * 0.30 + half3(0.34, 0.35, 0.36);
    col *= 0.93 + 0.12 * streak;
    // a soft darkening toward the edges where it rolls away
    col *= 1.0 - 0.25 * smoothstep(0.75, 1.0, v);
    return half4(col, 1.0);
}
"""

/**
 * A round button in black enamel over brass, seen from above: a gently domed top, a knurled
 * rim whose ridges have worn through to brass at their tips and here and there along it.
 */
const val KNURL_BUTTON_AGSL = ENV_CORE + """
uniform float2 iResolution;
uniform float uPx;
uniform float uPressed;

half4 main(float2 p) {
    float2 uv = (p / iResolution) * 2.0 - 1.0;
    float r = length(uv);
    if (r > 1.0 + uPx) return half4(0.0);
    float2 dir = uv / max(r, 1e-4);
    half3 col;
    if (r > 0.74) {                                          // the knurled rim
        float ang = atan(uv.y, uv.x);
        float ridge = 0.5 + 0.5 * cos(ang * 48.0);
        float t = (r - 0.74) / 0.26;
        float3 N = normalize(float3(dir * (t - 0.3) * 0.9 + float2(-dir.y, dir.x) * sin(ang * 48.0) * 0.55, 0.75));
        float organic = 0.5 + 0.5 * sin(ang * 2.0 + 0.7) * sin(ang * 5.0 + 2.1);
        float wear = smoothstep(0.78, 0.98, ridge) * (0.35 + 0.65 * organic) + smoothstep(0.9, 1.0, r) * 0.5 * organic;
        wear = clamp(wear, 0.0, 1.0);
        half3 en = enamel(N, 0.10) * (0.45 + 0.55 * ridge);
        half3 br = toBrass(evalEnvironment(N, mix(0.42, 0.05, smoothstep(0.35, 0.85, wear))));
        col = mix(en, br, smoothstep(0.22, 0.5, wear));
        col *= (r < 0.76) ? 0.45 : 1.0;                      // the step down to the face
    } else {                                                 // the face: domed black enamel
        float3 N = normalize(float3(uv * 0.35, 1.0));
        col = enamel(N, 0.08);
    }
    col *= 1.0 - 0.15 * uPressed;
    float a = smoothstep(1.0 + uPx, 1.0 - uPx, r);
    return half4(col * a, a);
}
"""

/**
 * A vertical slide switch cut into the steel: a dark slot with a chamfered steel lip, and a
 * black enamel knob with knurled grip lines (worn to brass at their edges) at the top (on) or
 * bottom (off). Units: half the width.
 */
const val SLIDE_V_AGSL = ENV_CORE + """
uniform float2 iResolution;
uniform float uPx;
uniform float uKnob;          // -1 down .. 1 up

float rrect(float2 p, float2 b, float r) { float2 q = abs(p) - b + r; return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r; }

half4 main(float2 p) {
    float hw = iResolution.x * 0.5;
    float2 uv = (p - iResolution * 0.5) / hw;
    float hh = iResolution.y / iResolution.x;
    float slot = rrect(uv, float2(0.80, hh - 0.08), 0.80);
    if (slot > 0.14 + uPx) return half4(0.0);
    half3 col;
    float a = 1.0;
    if (slot > 0.0) {                                        // the chamfered steel lip
        float t = slot / 0.14;
        float e = 0.001;
        float2 g = normalize(float2(rrect(uv + float2(e, 0.0), float2(0.80, hh - 0.08), 0.80) - rrect(uv - float2(e, 0.0), float2(0.80, hh - 0.08), 0.80),
                                    rrect(uv + float2(0.0, e), float2(0.80, hh - 0.08), 0.80) - rrect(uv - float2(0.0, e), float2(0.80, hh - 0.08), 0.80)) + 1e-6);
        col = toMetal(evalEnvironment(normalize(float3(-g * (1.0 - t) * 0.9, 0.6)), 0.10));
        a = smoothstep(0.14 + uPx, 0.14 - uPx, slot);
    } else {
        col = half3(0.012, 0.012, 0.013) * (1.0 + 2.0 * smoothstep(-0.25, 0.0, slot));     // the slot's depth
    }
    // the knob
    float travel = hh - 0.08 - 0.80;      // the ball rolls end to end inside the pill
    float2 kc = float2(0.0, -uKnob * travel);
    // its shadow in the slot
    col *= 1.0 - 0.5 * smoothstep(0.95, 0.55, length(uv - kc + uLightDir * 0.12)) * step(slot, 0.0);
    // the knob: a black enamel sphere riding in the slot, its crown worn to brass
    float2 kd = (uv - kc) / 0.75;
    float kl = length(kd);
    if (kl < 1.0) {
        float3 N = normalize(float3(kd, sqrt(max(1.0 - kl * kl, 0.0))));
        float2 w = kd - float2(-0.12, -0.18);
        float patch = smoothstep(0.42, 0.18, length(w * float2(1.0, 1.3)) + 0.06 * sin(atan(w.y, w.x) * 5.0));
        half3 k = toMetal(evalEnvironment(N, 0.03));            // polished steel
        col = mix(col, k, smoothstep(1.0, 1.0 - uPx * 3.0 / 0.75, kl));
        a = max(a, smoothstep(1.0, 1.0 - uPx * 3.0 / 0.75, kl));
    }
    return half4(col * a, a);
}
"""

/**
 * The waist-level viewer's bezel: a chamfered stainless frame round a rectangular opening, the
 * opening's glass faintly reflecting the world (a soft Fresnel sheen and a diagonal sweep); the
 * picture shows through from beneath. Units: half the height.
 */
const val VIEWER_BEZEL_AGSL = ENV_CORE + """
uniform float2 iResolution;
uniform float uPx;
uniform float2 uOpen;        // the opening's half size
uniform float uFrame;        // the frame's width

float rrect(float2 p, float2 b, float r) { float2 q = abs(p) - b + r; return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r; }

half4 main(float2 p) {
    float hh = iResolution.y * 0.5;
    float2 uv = (p - iResolution * 0.5) / hh;
    float2 outer = uOpen + uFrame;
    float o = rrect(uv, outer, 0.22);
    if (o > uPx) return half4(0.0);
    float io = rrect(uv, uOpen, 0.14);
    if (io > 0.0) {
        // the frame: rising from the deck at the outside, chamfered down into the opening
        float t = clamp(io / uFrame, 0.0, 1.0);
        float e = 0.002;
        float2 g = normalize(float2(rrect(uv + float2(e, 0.0), uOpen, 0.14) - rrect(uv - float2(e, 0.0), uOpen, 0.14),
                                    rrect(uv + float2(0.0, e), uOpen, 0.14) - rrect(uv - float2(0.0, e), uOpen, 0.14)) + 1e-6);
        float slope = t < 0.35 ? -(0.35 - t) / 0.35 * 1.1 : (t > 0.75 ? (t - 0.75) / 0.25 * 0.9 : 0.0);
        half3 col = toMetal(evalEnvironment(normalize(float3(g * slope, 0.8)), t > 0.35 && t < 0.75 ? 0.22 : 0.05));
        col *= (t < 0.05) ? 0.35 : 1.0;                       // the dark seam at the opening
        float a = smoothstep(uPx, -uPx, o);
        return half4(col * a, a);
    }
    // the glass over the picture: mostly clear, a sheen toward its edges and a soft sweep
    float2 q = uv / uOpen;
    float edge = pow(clamp(max(abs(q.x), abs(q.y)), 0.0, 1.0), 6.0);
    float sweep = smoothstep(0.10, 0.0, abs(q.x * 0.6 + q.y * 0.8 - 0.2 + uLightDir.x * 0.5)) * 0.14;
    half3 refl = evalEnvironment(normalize(float3(q * 0.3, 1.0)), 0.05);
    float a = clamp(0.04 + edge * 0.35 + sweep, 0.0, 1.0);
    return half4(refl * a, a);
}
"""

/** A green LED set flush in the steel: a thin dark ring, a flat lens, glowing from within when lit. */
const val FLUSH_LED_AGSL = ENV_CORE + """
uniform float2 iResolution;
uniform float uPx;
uniform float uLit;

half4 main(float2 p) {
    float2 uv = (p / iResolution) * 2.0 - 1.0;
    float r = length(uv);
    if (r > 1.0 + uPx) return half4(0.0);
    half3 col;
    if (r > 0.82) col = half3(0.02);                                     // the seam in the steel
    else {
        float gr = r / 0.82;
        // unlit: the lens's own dark green, a little lighter at its middle
        half3 body = mix(half3(0.05, 0.19, 0.09) * (1.0 - 0.35 * gr), half3(0.12, 0.95, 0.45), uLit * (1.0 - gr * 0.5));
        body += half3(0.6, 1.0, 0.75) * uLit * smoothstep(0.45, 0.0, gr) * 0.5;
        half3 refl = evalEnvironment(float3(0.0, 0.0, 1.0), 0.04);
        col = body + refl * 0.06 * (1.0 - uLit * 0.8);
    }
    float a = smoothstep(1.0 + uPx, 1.0 - uPx, r);
    return half4(col * a, a);
}
"""

/** A small polished steel sphere button. */
const val SPHERE_BUTTON_AGSL = ENV_CORE + """
uniform float2 iResolution;
uniform float uPx;
uniform float uPressed;

half3 sphere(float2 d) {
    float l = length(d);
    float3 N = normalize(float3(d, sqrt(max(1.0 - l * l, 0.0))));
    // wear: brass showing at the crown, a soft-edged irregular patch
    float2 w = d - float2(-0.12, -0.18);
    float patch = smoothstep(0.42, 0.18, length(w * float2(1.0, 1.3)) + 0.06 * sin(atan(w.y, w.x) * 5.0));
    return toMetal(evalEnvironment(N, 0.03));                // polished steel
}

half4 main(float2 p) {
    float2 uv = (p / iResolution) * 2.0 - 1.0;
    float r = length(uv);
    if (r > 1.0 + uPx) return half4(0.0);
    half3 col = sphere(uv) * (1.0 - 0.15 * uPressed);
    float a = smoothstep(1.0 + uPx, 1.0 - uPx, r);
    return half4(col * a, a);
}
"""
