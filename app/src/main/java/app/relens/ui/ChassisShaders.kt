package app.relens.ui

import android.graphics.RuntimeShader
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
        fun ease(old: FloatArray, new: FloatArray) = FloatArray(3) { old[it] + (new[it] - old[it]) * 0.35f }
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
    }
}

/** The shared studio every surface reflects (the guide's evalEnvironment), and brass's colour. */
const val ENV_CORE = """
uniform float2 uLightDir;
uniform float3 uSkyTone;
uniform float3 uHorizonTone;
uniform float3 uGroundTone;

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
    float el = -R.y;                                   // up is up
    float az = atan(R.x, max(R.z, 0.05));
    float detail = 1.0 - smoothstep(0.02, 0.45, roughness);
    half3 skyT = half3(uSkyTone); half3 horT = half3(uHorizonTone); half3 gndT = half3(uGroundTone);
    // the sky: deeper blue overhead, paler down to the haze; clouds drifting across
    half3 skyCol = mix(half3(0.72, 0.80, 0.90), half3(0.36, 0.52, 0.78), smoothstep(0.0, 0.8, el));
    skyCol = mix(skyCol, skyT * 1.1, 0.35);
    float cl = eFbm(float2(az * 2.2, el * 5.0 + 1.7));
    float cloud = smoothstep(0.48, 0.78, cl) * smoothstep(0.02, 0.18, el);
    skyCol = mix(skyCol, half3(0.97, 0.96, 0.93), cloud * (0.35 + 0.65 * detail));
    skyCol -= half3(0.08) * smoothstep(0.62, 0.9, cl) * cloud * detail;          // their shaded undersides
    // the tree line: an uneven canopy along the horizon
    float canopy = 0.035 + 0.11 * eFbm(float2(az * 7.0, 3.1)) + 0.04 * eNoise(float2(az * 23.0, 9.0)) * detail;
    float tree = smoothstep(canopy + 0.012 + roughness * 0.05, canopy - 0.012 - roughness * 0.05, el) * step(0.0, el + 0.005);
    half3 treeCol = mix(half3(0.08, 0.13, 0.08), horT * 0.45, 0.35) * (0.75 + 0.5 * eNoise(float2(az * 40.0, el * 60.0)) * detail);
    // water: a band below the horizon mirroring the sky, broken by ripples
    float wv = smoothstep(0.0, -0.02, el) * smoothstep(-0.32, -0.20, el);
    float ripple = eNoise(float2(az * 3.0, el * 90.0)) * detail;
    float3 Rw = float3(R.x + (ripple - 0.5) * 0.12, -R.y, R.z);
    half3 waterCol = mix(half3(0.30, 0.42, 0.52), skyCol * 0.85, 0.6) * (0.8 + 0.35 * ripple) + half3(0.05) * step(0.82, ripple);
    // nearer ground: grass and earth
    float gr = eFbm(float2(az * 5.0, el * 9.0));
    half3 grass = mix(half3(0.20, 0.27, 0.12), half3(0.36, 0.30, 0.18), gr) * (0.85 + 0.3 * detail * eNoise(float2(az * 30.0, el * 40.0)));
    grass = mix(grass, gndT * 0.8, 0.35);
    half3 env = (el > 0.0) ? skyCol : mix(grass, waterCol, wv);
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

half4 main(float2 p) {
    float2 uv = (p / iResolution) * 2.0 - 1.0;
    float r = length(uv);
    if (r > 1.0 + uPx) return half4(0.0);
    float2 dir = uv / max(r, 1e-4);
    float3 N = float3(0.0, 0.0, 1.0);
    float rough = 0.03;
    float ao = 1.0;
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
                half3 c = toBrass(evalEnvironment(normalize(float3(-dir * 0.6, 0.8)), 0.35)) * (0.25 + 0.35 * th);
                return half4(c * (1.0 - 0.15 * uPressed), 1.0);
            }
            if (r < 0.20) { N = normalize(float3(-dir * 0.8, 0.6)); rough = 0.05; } // its chamfer
        }
    }
    half3 col = toBrass(evalEnvironment(N, rough)) * ao;
    col *= 1.0 - 0.15 * uPressed;                    // the thumb shades it
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
