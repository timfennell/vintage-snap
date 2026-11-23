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

half3 evalEnvironment(float3 N, float roughness) {
    float3 R = reflect(float3(0.0, 0.0, -1.0), normalize(N));
    R.xy += uLightDir * 0.55;
    R = normalize(R);
    half3 skyCol = mix(half3(0.80, 0.83, 0.88), half3(uSkyTone), 0.55);
    half3 horCol = mix(half3(0.12, 0.13, 0.15), half3(uHorizonTone) * 0.45, 0.50);
    half3 gndCol = mix(half3(0.28, 0.22, 0.16), half3(uGroundTone) * 0.65, 0.55);
    float upperMask = smoothstep(-0.08 - roughness * 0.35, 0.12 + roughness * 0.35, -R.y);
    float lowerMask = smoothstep(0.05 - roughness * 0.30, 0.45 + roughness * 0.30, R.y);
    half3 env = mix(horCol, skyCol, upperMask);
    env = mix(env, gndCol, lowerMask * 0.85);
    // softbox bars overhead: the hard-edged reflections polished metal shows
    float bars = smoothstep(0.06 + roughness, 0.0, abs(fract(R.x * 1.6 + 0.25) - 0.5) - 0.18) * upperMask;
    env += half3(0.35, 0.34, 0.32) * bars * (1.0 - roughness);
    float3 keyDir = normalize(float3(uLightDir.x * 1.1, uLightDir.y * 1.1, 0.65));
    float3 rimDir = normalize(float3(-uLightDir.x * 0.9, -uLightDir.y * 0.4, 0.60));
    float specExp = mix(72.0, 3.0, roughness * roughness);
    float specGain = mix(1.20, 0.18, roughness);
    float keySpec = pow(max(dot(R, keyDir), 0.0), specExp);
    float rimSpec = pow(max(dot(R, rimDir), 0.0), specExp * 0.6);
    env += half3(1.0, 0.98, 0.94) * (keySpec * specGain + rimSpec * specGain * 0.50);
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
