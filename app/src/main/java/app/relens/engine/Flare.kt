package app.relens.engine

import app.relens.optics.Lens
import app.relens.optics.LensFilter
import app.relens.optics.Movement
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sqrt

/** A light found in the picture: where (px), how big (px), its light (sum of linear rgb), its blur (px). */
class LightSource(val x: Float, val y: Float, val radius: Float, val energy: FloatArray, val blur: Float, val pixels: Int) {
    val luma get() = 0.2126f * energy[0] + 0.7152f * energy[1] + 0.0722f * energy[2]
}

/**
 * Finding the lights (lights.comp's tiles, joined) and the flare rows the
 * shader reads: Vintage Click's Flare.uniforms, with the light sources found in
 * the photo instead of placed in a scene.
 */
object Flare {
    const val MAX_LIGHTS = 8
    const val ROWS = 4 + 3 * MAX_LIGHTS + 1      // lens, filter, 3 per light, the leak row
    /** How much brighter than white a clipped light really is: a small lamp, a big window. */
    var HOT_SMALL = 400f
    var HOT_LARGE = 40f

    /** Joins bright tiles (8-neighbours) into lights; the [MAX_LIGHTS] brightest. */
    fun join(tw: Int, th: Int, energy: FloatArray, where: FloatArray): List<LightSource> {
        val seen = BooleanArray(tw * th)
        val out = ArrayList<LightSource>()
        val stack = ArrayDeque<Int>()
        for (start in 0 until tw * th) {
            if (seen[start] || energy[4 * start + 3] <= 0f) continue
            var r = 0.0; var g = 0.0; var b = 0.0; var n = 0.0; var sx = 0.0; var sy = 0.0; var sc = 0.0; var se = 0.0
            stack.addLast(start); seen[start] = true
            while (stack.isNotEmpty()) {
                val i = stack.removeLast()
                r += energy[4 * i]; g += energy[4 * i + 1]; b += energy[4 * i + 2]; n += energy[4 * i + 3]
                sx += where[4 * i]; sy += where[4 * i + 1]; sc += where[4 * i + 2]; se += where[4 * i + 3]
                val x = i % tw; val y = i / tw
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx; val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= tw || ny >= th) continue
                    val j = ny * tw + nx
                    if (!seen[j] && energy[4 * j + 3] > 0f) { seen[j] = true; stack.addLast(j) }
                }
            }
            if (se <= 0 || n < 2) continue
            out.add(LightSource((sx / se).toFloat(), (sy / se).toFloat(), sqrt(n / Math.PI).toFloat(),
                floatArrayOf(r.toFloat(), g.toFloat(), b.toFloat()), (sc / se).toFloat(), n.toInt()))
        }
        return out.sortedByDescending { it.luma }.take(MAX_LIGHTS)
    }

    private fun smoothstep(a: Float, b: Float, x: Float): Float { val t = ((x - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3 - 2 * t) }

    private fun seedOf(l: Lens): Int {
        var h = 2166136261L.toInt()
        for (c in l.name.toByteArray()) h = (h xor (c.toInt() and 0xff)) * 16777619
        return h.toLong().and(0xffffffffL).rem(10000).toInt()
    }

    /** Older and odd optics are single-coated: brighter, more colourful ghosts. */
    fun singleCoated(l: Lens) = l.collection in listOf("Exotic", "Toys & Mods", "Screw-Mount Classics") ||
        listOf("Petzval", "Hoga", "Solnce", "Triplan", "f/0.95 Dream", "Noctar").any { l.name.contains(it) }

    /**
     * How prone each lens is to flare, after the real lens it is modelled on (Tim): the
     * multi-coated magazine kit and the macros hold contrast; the single-coated screw-mount
     * primes flare more; among the bokeh monsters the Soviet 58, the dream f/0.95 and the
     * Trioplan-style 100 flare hard while the Noct-style 58 and the 85 stay moderate; the
     * rangefinder's f/1.0 and pre-aspherical f/1.4s glow and flare; old exotics flare.
     * Multiplies the lens's own flareBoost (the whacked lenses' gap already has its own).
     */
    fun flareProne(l: Lens): Float {
        val byName = listOf(
            "Solnce" to 2.2f, "f/0.95 Dream" to 2.0f, "Triplan" to 1.8f, "Noctar" to 2.2f, "Summilar 50mm" to 1.8f,
            "Summilar 75mm" to 1.5f, "Summax 35mm" to 1.2f, "Petzval" to 1.8f, "Imago" to 1.5f, "Apollo" to 1.5f,
            "Fisheye" to 1.5f, "Kor 13mm" to 1.3f, "Reflex" to 0.8f, "200mm f/2 White" to 0.6f, "Nocturne" to 1.0f,
            "Kor 85mm f/1.4" to 0.9f, "135mm f/2 DC" to 0.9f, "Elmarit 90mm" to 0.9f, "Tele-Elmax" to 0.8f,
        ).firstOrNull { l.name.contains(it.first) }?.second
        return byName ?: when (l.collection) {
            "Field Assignment" -> 0.7f
            "Macro" -> 0.7f
            "Screw-Mount Classics" -> 1.6f
            "Toys & Mods" -> 1.4f
            "Exotic" -> 1.3f
            else -> 1f
        }
    }
    /** Dust and cleaning marks: every lens some, the old, rare and home-made more. */
    fun dustGrade(l: Lens) = when (l.collection) { "Toys & Mods" -> 3; "Exotic", "Screw-Mount Classics", "Bokeh Monsters" -> 2; else -> 1 }
    /** Seed bubbles in the glass: the old Soviet and Jena lenses'. */
    fun bubbles(l: Lens) = listOf("Krasnogorsk", "Leningrad", "Jena").any { l.name.startsWith(it) }

    /**
     * The rows (vec4s, flat): (lights, blades, blade rotation, openness),
     * (single-coated, seed + dust + bubbles, obstruction, squeeze), (axis uv,
     * dispersion, field softness), (filter glass, -, -, -); per light (uv,
     * radius / diagonal, visible), (flare rgb, own blur / diagonal), (unused);
     * then the leak row (strength, gap direction, seed).
     * [gain]: how much of a light's light reaches the flare (JPEG highlights are
     * clipped: their energy is restored roughly).
     */
    fun rows(lights: List<LightSource>, w: Int, h: Int, lens: Lens, fNumber: Float, filter: LensFilter,
             tiltDeg: FloatArray, gain: Float): FloatArray {
        val f = FloatArray(ROWS * 4)
        val seed = seedOf(lens)
        val diag = hypot(w.toFloat(), h.toFloat())
        val npx = w.toFloat() * h
        fun row(i: Int, a: Float, b: Float, c: Float, d: Float) { f[4 * i] = a; f[4 * i + 1] = b; f[4 * i + 2] = c; f[4 * i + 3] = d }
        row(0, lights.size.toFloat(), lens.blades.toFloat(), lens.bladeRotation, min(1f, lens.maxAperture / fNumber))
        row(1, if (singleCoated(lens)) 1f else 0f, seed + 10000f * dustGrade(lens) + if (bubbles(lens)) 40000f else 0f,
            lens.obstruction, lens.squeeze)
        row(2, 0.5f, 0.5f, 0.008f + lens.lateralCA * 4,
            0.15f + (kotlin.math.abs(lens.curvatureRadial) + kotlin.math.abs(lens.curvatureTangential)) * 0.6f + kotlin.math.abs(lens.sphericalAberration) * 0.3f)
        row(3, if (filter.isNone) 0f else 1f, 0f, 0f, 0f)
        for ((i, s) in lights.withIndex()) {
            // A lamp's face clips the sensor: what was measured is "white", while the light is
            // tens of times brighter (Vintage Click's lights carry their true radiance). A
            // clipped light gets that back: most for a small, compact source, less for a big
            // bright area (a window), which would otherwise veil everything.
            val perPx = s.luma / maxOf(s.pixels, 1)
            val area = s.pixels / npx
            val hot = if (perPx < 0.85f) 1f
                else (HOT_SMALL * Math.pow((HOT_LARGE / HOT_SMALL).toDouble(), smoothstep(0.002f, 0.03f, area).toDouble()).toFloat())
            val k = gain * lens.flareBoost * flareProne(lens) * hot / npx
            // Off the picture (found in the camera's wider frame): its flare fades as it goes,
            // rather than stopping at a line (Flare.swift).
            val u = s.x / w; val v = s.y / h
            val beyond = maxOf(maxOf(-u, u - 1), maxOf(-v, v - 1))
            val fade = 1f - smoothstep(0.2f, 0.6f, beyond)
            row(4 + 3 * i, u, v, s.radius / diag, fade)
            row(5 + 3 * i, s.energy[0] * k, s.energy[1] * k, s.energy[2] * k, s.blur / diag)
        }
        // A loose lens's leak: the gap opens on the side it is rocked away from.
        if (lens.leak > 0) {
            val reach = (lens.movement as? Movement.Tilt)?.maxDegrees ?: 0f
            val t = hypot(tiltDeg[0], tiltDeg[1])
            val open = if (reach > 0) min(1f, t / reach) else 0f
            val dx = if (t > 1e-3f) tiltDeg[0] / t * open else 0f
            val dy = if (t > 1e-3f) -tiltDeg[1] / t * open else 0f
            row(ROWS - 1, lens.leak * (0.35f + 0.65f * open), dx, dy, (seed % 1000).toFloat())
        }
        return f
    }
}
