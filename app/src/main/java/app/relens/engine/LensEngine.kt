package app.relens.engine

import android.opengl.GLES30
import app.relens.film.FilmStock
import app.relens.optics.Lens
import app.relens.optics.LensFilter
import app.relens.optics.lensFilters
import android.opengl.GLES31
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** What the photographer sets for one development. */
data class Look(
    val lens: Lens,
    val fNumber: Float,
    /**
     * Disparity (the network's, normalised 0...1, near = 1) to dioptres:
     * D = dispA + dispB * disparity. The app anchors it on the camera's own
     * focus distance at the tapped point (see Developer); the bench on a near/far guess.
     */
    val dispA: Float,
    val dispB: Float,
    /** Where the lens is focused, dioptres (1 / metres). */
    val focusD: Float,
    /** A tilt lens: dioptres of focus change at the frame's half-diagonal, across and up. */
    val tiltD: FloatArray = floatArrayOf(0f, 0f),
    /** A shift lens: the picture plane turned (radians) about its horizontal and vertical axes. */
    val keystone: FloatArray = floatArrayOf(0f, 0f),
    /** The camera picture's focal length as a fraction of its long side (the Nothing main camera: 5.56 / 8.19 mm). */
    val viewFocal: Float = 0.68f,
    val stock: FilmStock,
    val push: Int = 0,
    val exposure: Float = 1f,
    val samples: Int = 96,
    /** Mip level offset for the gather: 0 for the preview, -1 for a print (finer, less smearing). */
    val levelBias: Float = 0f,
    /** A JPEG's clipped point lights are restored this much (0 for RAW, which isn't clipped). */
    val pointBoost: Float = 8f,
    /** The filter on the lens. */
    val filter: LensFilter = lensFilters[0],
    /** Flare from the lights found in the picture; [flareGain] restores clipped JPEG light (RAW ~1). */
    val flare: Boolean = true,
    val flareGain: Float = 3f,
    /** A tilt lens's tilt, degrees (a whacked lens's leak opens on the side it's rocked away from). */
    val tiltDeg: FloatArray = floatArrayOf(0f, 0f),
    /** Where the frame is in the source texture (uv origin, uv size): the lens's crop of the camera's frame. */
    val srcRect: FloatArray = floatArrayOf(0f, 0f, 1f, 1f),
    /** The source is scene-linear (developed from RAW) rather than an sRGB picture. */
    val srcLinear: Boolean = false,
) {
    val focusM: Float get() = 1f / maxOf(focusD, 1e-3f)

    companion object {
        /** The bench's mapping: disparity 0...1 spans far...near metres. */
        fun nearFar(lens: Lens, fNumber: Float, nearM: Float, farM: Float, focusDisparity: Float,
                    stock: FilmStock, samples: Int): Look {
            val dFar = 1f / farM; val dNear = 1f / nearM
            return Look(lens, fNumber, dFar, dNear - dFar, dFar + (dNear - dFar) * focusDisparity,
                stock = stock, samples = samples)
        }
    }
}

/**
 * The GPU lens: picture + low-res disparity in, developed print out, one
 * compute pass at a time, each timed (glFinish between passes).
 */
class LensEngine(private val gl: Gl) {
    val timings = LinkedHashMap<String, Double>()
    /** The lights the last development found (for the darkroom). */
    var lights: List<LightSource> = emptyList(); private set
    /** Lights found beforehand over a larger frame (the camera's uncropped one), in this frame's pixels. */
    var extraLights: List<LightSource>? = null

    /** Finds the lights in an RGBA8 picture (sRGB): tile energies above the threshold, joined. */
    fun findLights(tex: Int, w: Int, h: Int, srgb: Boolean = true, threshold: Float = 0.9f): List<LightSource> {
        val ltw = (w + 15) / 16; val lth = (h + 15) / 16
        val eTex = gl.texture(ltw, lth, GLES30.GL_RGBA32F, linear = false)
        val wTex = gl.texture(ltw, lth, GLES30.GL_RGBA32F, linear = false)
        val p = gl.use("lights")
        gl.sampler(p, "uWork", 0, tex)
        gl.u1f(p, "uThreshold", threshold)
        gl.u1i(p, "uSrgb", if (srgb) 1 else 0)
        gl.image(0, eTex, GLES30.GL_RGBA32F)
        GLES31.glBindImageTexture(1, wTex, 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA32F)
        gl.dispatch(ltw, lth)
        gl.finish()
        val e = gl.readFloat(eTex, ltw, lth); val wh = gl.readFloat(wTex, ltw, lth)
        gl.deleteTextures(eTex, wTex)
        return Flare.join(ltw, lth, e, wh)
    }
    /** Debug: called after a pass with (pass, texture, w, h, level). */
    var dump: ((String, Int, Int, Int, Int) -> Unit)? = null
    /** Debug: no mip chain (to isolate mip problems). */
    var singleLevel = false
    /**
     * 2 = glGenerateMipmap (default). 0 = the compute downsample, which samples
     * `work` while writing another of its levels as an image: undefined in GL ES,
     * and on the Adreno 810 it left every later read of `work` returning zeros.
     * 1 = no mips (debug).
     */
    var mipMode = 2

    private inline fun <T> timed(name: String, block: () -> T): T {
        val t0 = System.nanoTime()
        val r = block()
        gl.finish()
        gl.checkErr(name)            // e.g. GL_OUT_OF_MEMORY at 50 MP
        timings[name] = (timings[name] ?: 0.0) + (System.nanoTime() - t0) / 1e6
        return r
    }

    /**
     * Develops at [w] x [h]. [src] is the camera picture (RGBA8 texture, any
     * size: it is resampled). Returns the RGBA8 print texture (caller deletes it).
     */
    /** With develop(linear = true): the relensed picture before the film (RGBA16F, linear sRGB); the caller deletes it. */
    var lastLinear = 0; private set

    fun develop(src: Int, dispLo: Int, guideLo: Int, w: Int, h: Int, look: Look, linear: Boolean = false): Int {
        lastLinear = 0
        timings.clear()
        val levels = if (singleLevel) 1 else min(7, floor(log2(max(w, h).toFloat())).toInt() + 1)
        var lin = gl.texture(w, h, GLES30.GL_RGBA16F)
        var disp = gl.texture(w, h, GLES30.GL_R32F, linear = false)
        val work = gl.texture(w, h, GLES30.GL_RGBA16F, levels = levels)
        val tw = (w + 15) / 16; val th = (h + 15) / 16
        val tilesA = gl.texture(tw, th, GLES30.GL_R32F, linear = false)
        val tilesB = gl.texture(tw, th, GLES30.GL_R32F, linear = false)
        val out = gl.texture(w, h, GLES30.GL_RGBA8)
        val axisX = w / 2f; val axisY = h / 2f
        val halfDiag = hypot(w / 2f, h / 2f)

        val warp = warpUniforms(look, w, h)
        timed("linearize") {
            val p = gl.use("linearize")
            gl.u4f(p, "uWarp", warp[0], warp[1], warp[2], warp[3])
            gl.u2f(p, "uWarpFZ", warp[4], warp[5])
            gl.u4f(p, "uSrcRect", look.srcRect[0], look.srcRect[1], look.srcRect[2], look.srcRect[3])
            gl.u1i(p, "uSrcLinear", if (look.srcLinear) 1 else 0)
            gl.sampler(p, "uSrc", 0, src)
            gl.image(0, lin, GLES30.GL_RGBA16F)
            gl.u1f(p, "uGain", look.exposure)
            gl.u1f(p, "uHighlight", 0f)   // point lights are restored in the coc pass instead
            gl.dispatch(w, h)
        }
        dump?.invoke("linearize", lin, w, h, 0)
        timed("depth upsample (JBU)") {
            val p = gl.use("jbu")
            gl.u4f(p, "uWarp", warp[0], warp[1], warp[2], warp[3])
            gl.u2f(p, "uWarpFZ", warp[4], warp[5])
            gl.sampler(p, "uDispLo", 0, dispLo)
            gl.sampler(p, "uGuideLo", 1, guideLo)
            gl.sampler(p, "uSrc", 2, lin)
            gl.image(0, disp, GLES30.GL_R32F)
            gl.u1f(p, "uSigmaRange", 0.08f)
            gl.dispatch(w, h)
        }
        timed("depth edge snap") {
            // a-trous cross-bilateral at full resolution, steps 1, 2, 4 (disp -> tmp -> disp ...)
            val tmp = gl.texture(w, h, GLES30.GL_R32F, linear = false)
            val p = gl.use("refine")
            gl.u1f(p, "uSigmaRange", 0.05f)
            var from = disp; var to = tmp
            for (step in intArrayOf(1, 2, 4)) {
                gl.sampler(p, "uDisp", 0, from)
                gl.sampler(p, "uSrc", 1, lin)
                gl.u1i(p, "uStep", step)
                gl.image(0, to, GLES30.GL_R32F)
                gl.dispatch(w, h)
                val t = from; from = to; to = t
            }
            if (from != disp) {     // odd number of passes: the result is in tmp; swap names
                gl.deleteTextures(disp); disp = from
            } else gl.deleteTextures(tmp)
        }
        dump?.invoke("jbu disp", disp, w, h, 0)
        val maxCoc = h * 0.08f
        timed("coc") {
            val p = gl.use("coc")
            gl.sampler(p, "uSrc", 0, lin)
            gl.sampler(p, "uDisp", 1, disp)
            gl.image(0, work, GLES30.GL_RGBA16F)
            gl.u2f(p, "uDispToD", look.dispA, look.dispB)
            gl.u1f(p, "uFocusD", look.focusD)
            // A pinhole has no focus: the same blur everywhere (its spot on film).
            gl.u1f(p, "uBaseCoc", if (look.lens.isPinhole) pinholeRadiusPx(look.lens, maxOf(w, h), look.viewFocal * 36f) else 0f)
            gl.u1f(p, "uPointBoost", look.pointBoost)
            gl.u2f(p, "uTiltD", look.tiltD[0], look.tiltD[1])
            gl.u1f(p, "uCocPerD", cocRadiusPxPerDioptre(look.lens, look.fNumber, look.focusM, maxOf(w, h), look.viewFocal * 36f))
            gl.u1f(p, "uMaxCoc", maxCoc)
            gl.u2f(p, "uAxis", axisX, axisY)
            gl.u1f(p, "uHalfDiag", halfDiag)
            gl.dispatch(w, h)
        }
        dump?.invoke("coc work L0", work, w, h, 0)
        timed("mips") {
          if (mipMode == 2) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, work)
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
          } else if (mipMode == 0 && levels > 1) {
            val p = gl.use("downsample")
            gl.sampler(p, "uIn", 0, work)
            for (l in 1 until levels) {
                gl.u1i(p, "uLevel", l)
                gl.image(0, work, GLES30.GL_RGBA16F, level = l)
                gl.dispatch(max(1, w shr l), max(1, h shr l))
            }
          }
        }
        timed("tiles + dilate") {
            var p = gl.use("tiles")
            gl.sampler(p, "uWork", 0, work)
            gl.image(0, tilesA, GLES30.GL_R32F)
            gl.dispatch(tw, th)
            p = gl.use("dilate")
            val radius = ceil(maxCoc / 16f).toInt() + 1
            gl.u1i(p, "uRadius", radius)
            gl.sampler(p, "uIn", 0, tilesA); gl.image(0, tilesB, GLES30.GL_R32F)
            gl.u2i(p, "uDir", 1, 0); gl.dispatch(tw, th)
            gl.sampler(p, "uIn", 0, tilesB); gl.image(0, tilesA, GLES30.GL_R32F)
            gl.u2i(p, "uDir", 0, 1); gl.dispatch(tw, th)
        }
        dump?.invoke("work L0 after mips", work, w, h, 0)
        if (levels > 3) dump?.invoke("work L3", work, max(1, w shr 3), max(1, h shr 3), 3)
        dump?.invoke("tiles", tilesA, tw, th, 0)
        val n = min(256, look.samples)
        val ubo = gl.ubo(gatherPattern(n, look.lens).copyOf(256 * 4))
        timed("gather blur (N=$n)") {
            val p = gl.use("gather")
            GLES31.glUniformBlockBinding(p, GLES31.glGetUniformBlockIndex(p, "Pattern"), 0)
            GLES30.glBindBufferBase(GLES31.GL_UNIFORM_BUFFER, 0, ubo)
            gl.sampler(p, "uWork", 0, work)
            gl.sampler(p, "uTiles", 1, tilesA)
            gl.image(0, lin, GLES30.GL_RGBA16F)       // lin is free again: the blur goes there
            gl.u1i(p, "uN", n)
            gl.u1f(p, "uMaxLevel", (levels - 1).toFloat())
            gl.u1f(p, "uLevelBias", look.levelBias)
            gl.u2f(p, "uAxis", axisX, axisY)
            gl.u1f(p, "uHalfDiag", halfDiag)
            val l = look.lens
            gl.u4f(p, "uLens", l.blades.toFloat(), l.bladeRotation, l.catsEye, l.soapBubble)
            gl.u2f(p, "uLens2", l.obstruction, l.maxAperture / look.fNumber)
            // In strips of ~1.5 MP, each flushed: one long dispatch trips the GPU
            // watchdog (a 1.3 s 50 MP gather lost the context on the Adreno 810).
            val rows = max(64, (1_500_000 / w) / 8 * 8)
            var y = 0
            while (y < h) {
                gl.u2i(p, "uOffset", 0, y)
                gl.dispatch(w, min(rows, h - y))
                GLES30.glFlush()
                y += rows
            }
        }
        GLES30.glDeleteBuffers(1, intArrayOf(ubo), 0)
        timed("smooth") {
            // The gather wrote into lin; smooth it into a fresh texture, then swap.
            val sm = gl.texture(w, h, GLES30.GL_RGBA16F)
            val p = gl.use("smooth")
            gl.sampler(p, "uIn", 0, lin)
            gl.sampler(p, "uWork", 1, work)
            gl.image(0, sm, GLES30.GL_RGBA16F)
            val rows = max(64, (3_000_000 / w) / 8 * 8)
            var y = 0
            while (y < h) { gl.u2i(p, "uOffset", 0, y); gl.dispatch(w, min(rows, h - y)); GLES30.glFlush(); y += rows }
            gl.deleteTextures(lin); lin = sm
        }
        dump?.invoke("gather", lin, w, h, 0)
        lights = emptyList()
        if (look.flare && (look.lens.flareBoost > 0f || look.lens.leak > 0f)) timed("lights + flare") {
            // Find the lights: tile energies above a threshold, joined on the CPU.
            val ltw = (w + 15) / 16; val lth = (h + 15) / 16
            val eTex = gl.texture(ltw, lth, GLES30.GL_RGBA32F, linear = false)
            val wTex = gl.texture(ltw, lth, GLES30.GL_RGBA32F, linear = false)
            var p = gl.use("lights")
            gl.sampler(p, "uWork", 0, work)
            gl.u1f(p, "uThreshold", 0.9f)
            gl.u1i(p, "uSrgb", 0)
            gl.image(0, eTex, GLES30.GL_RGBA32F)
            GLES31.glBindImageTexture(1, wTex, 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA32F)
            gl.dispatch(ltw, lth)
            gl.finish()
            val e = gl.readFloat(eTex, ltw, lth); val wh = gl.readFloat(wTex, ltw, lth)
            gl.deleteTextures(eTex, wTex)
            // Found over the camera's whole frame when there is one (lights just off the picture too).
            lights = extraLights?.sortedByDescending { it.luma }?.take(Flare.MAX_LIGHTS) ?: Flare.join(ltw, lth, e, wh)
            val rows = Flare.rows(lights, w, h, look.lens, look.fNumber, look.filter, look.tiltDeg, look.flareGain)
            if (lights.isNotEmpty() || look.lens.leak > 0f) {
                val fo = gl.texture(w, h, GLES30.GL_RGBA16F)
                p = gl.use("flare")
                gl.sampler(p, "uIn", 0, lin)
                gl.sampler(p, "uWork", 1, work)
                gl.image(0, fo, GLES30.GL_RGBA16F)
                gl.u4fv(p, "uF", rows)
                val stripRows = max(64, (1_000_000 / w) / 8 * 8)
                var y = 0
                while (y < h) { gl.u2i(p, "uOffset", 0, y); gl.dispatch(w, min(stripRows, h - y)); GLES30.glFlush(); y += stripRows }
                gl.deleteTextures(lin); lin = fo
            }
        }
        val fl = look.filter
        if (!fl.isNone) timed("filter") {
            val fo = gl.texture(w, h, GLES30.GL_RGBA16F)
            val p = gl.use("filter")
            gl.sampler(p, "uIn", 0, lin)
            gl.image(0, fo, GLES30.GL_RGBA16F)
            val tl = maxOf(fl.transmittance, 1e-4f)
            gl.u3f(p, "uColour", fl.transmission[0] / tl, fl.transmission[1] / tl, fl.transmission[2] / tl)
            gl.u4f(p, "uStar", fl.starPoints.toFloat(), fl.starStrength, if (fl.spectral) 1f else 0f, 0.35f)
            gl.u4f(p, "uGlow", fl.glow, fl.soften, fl.veil, 0.9f)
            val rows = max(64, (1_000_000 / w) / 8 * 8)
            var y = 0
            while (y < h) { gl.u2i(p, "uOffset", 0, y); gl.dispatch(w, min(rows, h - y)); GLES30.glFlush(); y += rows }
            gl.deleteTextures(lin); lin = fo
        }
        if (linear) timed("linear") {
            val lo = gl.texture(w, h, GLES30.GL_RGBA16F)
            val p = gl.use("linear")
            gl.sampler(p, "uIn", 0, lin)
            gl.image(0, lo, GLES30.GL_RGBA16F)
            gl.u1f(p, "uExposure", 1f)
            val open = look.lens.maxAperture / look.fNumber
            gl.u1f(p, "uVignette", look.lens.vignette * open.pow(1.5f))
            gl.u1f(p, "uLateralCA", look.lens.lateralCA)
            gl.u2f(p, "uAxis", axisX, axisY)
            gl.u1f(p, "uHalfDiag", halfDiag)
            gl.dispatch(w, h)
            lastLinear = lo
        }
        timed("film develop") {
            val p = gl.use("develop")
            gl.sampler(p, "uIn", 0, lin)
            gl.image(0, out, GLES30.GL_RGBA8)
            gl.u4fv(p, "uF", look.stock.developed(look.push).packed())
            gl.u1f(p, "uExposure", 1f)
            val open = look.lens.maxAperture / look.fNumber
            gl.u1f(p, "uVignette", look.lens.vignette * open.pow(1.5f))
            gl.u1f(p, "uLateralCA", look.lens.lateralCA)
            gl.u2f(p, "uAxis", axisX, axisY)
            gl.u1f(p, "uHalfDiag", halfDiag)
            gl.u1ui(p, "uSeed", 1234)
            gl.u4fv(p, "uPat", vogel32)
            gl.dispatch(w, h)
        }
        gl.deleteTextures(lin, disp, work, tilesA, tilesB)
        return out
    }

    /**
     * (tan ax, cos ax, tan ay, cos ay, focal px, zoom): the keystone warp, zoomed
     * in just enough that no corner of the print falls outside the picture.
     */
    private fun warpUniforms(look: Look, w: Int, h: Int): FloatArray {
        val ax = look.keystone[0]; val ay = look.keystone[1]
        val ta = kotlin.math.tan(ax); val ca = kotlin.math.cos(ax)
        val tb = kotlin.math.tan(ay); val cb = kotlin.math.cos(ay)
        val d = look.viewFocal * maxOf(w, h)
        fun inside(z: Float): Boolean {
            for ((cx0, cy0) in listOf(-w / 2f to -h / 2f, w / 2f to -h / 2f, -w / 2f to h / 2f, w / 2f to h / 2f)) {
                var cx = cx0 / z; var cy = cy0 / z
                val l1 = d / (d - cy * ta); cx *= l1; cy = l1 * cy / ca
                val l2 = d / (d - cx * tb); cx = l2 * cx / cb; cy *= l2
                if (kotlin.math.abs(cx) > w / 2f + 0.5f || kotlin.math.abs(cy) > h / 2f + 0.5f) return false
            }
            return true
        }
        var z = 1f
        while (!inside(z) && z < 3f) z += 0.01f
        return floatArrayOf(ta, ca, tb, cb, d, z)
    }

    companion object {
        val vogel32 = FloatArray(32 * 4).also {
            for (k in 0 until 32) {
                val r = sqrt((k + 0.5f) / 32f); val th = k * 2.39996323f
                it[4 * k] = r * cos(th); it[4 * k + 1] = r * sin(th)
            }
        }
        @Suppress("unused") private val pi = PI
    }
}

/** Reads a print texture back to a direct buffer (allocated once per size). */
fun readBack(gl: Gl, tex: Int, w: Int, h: Int): ByteBuffer {
    val buf = ByteBuffer.allocateDirect(w * h * 4)
    gl.readRgba8(tex, w, h, buf)
    buf.position(0)
    return buf
}
