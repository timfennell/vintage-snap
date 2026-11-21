package app.relens.engine

import android.opengl.GLES30
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.abs

/** An upright, full-frame, scene-linear picture developed from a RAW negative (an RGBA16F texture with mips). */
class RawPicture(val tex: Int, val width: Int, val height: Int, val levels: Int)

/**
 * Develops a RAW negative (camera/RawNegative: .raw16 + .rawmeta.json) on the
 * GPU with rawdev.comp: levels, lens shading, demosaic, white balance, colour.
 */
object RawDeveloper {
    private fun mat(a: org.json.JSONArray?): FloatArray? = a?.let { FloatArray(9) { i -> it.getDouble(i).toFloat() } }
    private fun mul(m: FloatArray, v: FloatArray) = FloatArray(3) { r -> m[3 * r] * v[0] + m[3 * r + 1] * v[1] + m[3 * r + 2] * v[2] }

    /** The white point (XYZ, Y = 1) of an EXIF light source code: A for tungsten, D65 for daylight (else D50-ish). */
    private fun white(code: Int): FloatArray = when (code) {
        17, 3 -> floatArrayOf(1.0985f, 1f, 0.3558f)          // standard light A / tungsten
        21, 1, 9 -> floatArrayOf(0.9505f, 1f, 1.0889f)        // D65 / daylight / fine weather
        20 -> floatArrayOf(0.9568f, 1f, 0.9214f)              // D55
        23 -> floatArrayOf(0.9642f, 1f, 0.8251f)              // D50
        else -> floatArrayOf(0.9642f, 1f, 0.8251f)
    }

    fun develop(gl: Gl, raw16: File, meta: File): RawPicture {
        val m = JSONObject(meta.readText())
        val w = m.getInt("width"); val h = m.getInt("height")
        val rot = m.optInt("orientation", 90)
        val bin = RandomAccessFile(raw16, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()) }
        val rawTex = IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, rawTex)
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_R16UI, w, h)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 2)
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, w, h, GLES30.GL_RED_INTEGER, GLES30.GL_UNSIGNED_SHORT, bin.order(ByteOrder.LITTLE_ENDIAN))
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        gl.checkErr("raw upload")

        // Lens shading: a small gain map per colour (bilinear).
        val sw = m.optInt("shadingW", 0); val sh = m.optInt("shadingH", 0)
        val shading = if (sw > 0 && sh > 0) {
            val a = m.getJSONArray("shading")
            val f = FloatArray(sw * sh * 4) { a.getDouble(it).toFloat() }
            gl.texture(sw, sh, GLES30.GL_RGBA16F).also { gl.upload(it, sw, sh, GLES30.GL_RGBA, GLES30.GL_FLOAT, floatBuffer(f)) }
        } else gl.texture(1, 1, GLES30.GL_RGBA16F).also { gl.upload(it, 1, 1, GLES30.GL_RGBA, GLES30.GL_FLOAT, floatBuffer(floatArrayOf(1f, 1f, 1f, 1f))) }

        // The forward matrix for this light: between the two calibrations, by which of their
        // camera-space whites the as-shot neutral is nearer (in blue / red).
        val neutral = m.optJSONArray("neutral")?.let { a -> FloatArray(3) { a.getDouble(it).toFloat() } } ?: floatArrayOf(0.5f, 1f, 0.6f)
        val fm1 = mat(m.optJSONArray("fm1")); val fm2 = mat(m.optJSONArray("fm2")) ?: fm1
        val cm1 = mat(m.optJSONArray("cm1")); val cm2 = mat(m.optJSONArray("cm2")) ?: cm1
        var t = 1f
        if (cm1 != null && cm2 != null) {
            fun br(v: FloatArray) = v[2] / maxOf(v[0], 1e-4f)
            val n1 = br(mul(cm1, white(m.optInt("illum1", 17)))); val n2 = br(mul(cm2, white(m.optInt("illum2", 21))))
            t = if (abs(n2 - n1) > 1e-4f) ((br(neutral) - n1) / (n2 - n1)).coerceIn(0f, 1f) else 1f
        }
        val fm = if (fm1 != null && fm2 != null) FloatArray(9) { fm1[it] + (fm2[it] - fm1[it]) * t }
            else floatArrayOf(0.4361f, 0.3851f, 0.1431f, 0.2225f, 0.7169f, 0.0606f, 0.0139f, 0.0971f, 0.7141f)   // sRGB D50, a fallback

        val uw = if (rot == 90 || rot == 270) h else w; val uh = if (rot == 90 || rot == 270) w else h
        val levels = 6
        val out = gl.texture(uw, uh, GLES30.GL_RGBA16F, levels = levels)
        // Hot and stuck photosites out first (hotpix.comp), into a clean copy the demosaic reads.
        val clean = IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, clean)
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_R32UI, w, h)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        run {
            val hp = gl.use("hotpix")
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, rawTex)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(hp, "uRaw"), 0)
            val bk = m.getJSONArray("black")
            gl.u1f(hp, "uBlack", ((0 until 4).sumOf { bk.getDouble(it) } / 4).toFloat())
            gl.u1f(hp, "uWhite", m.getDouble("white").toFloat())
            android.opengl.GLES31.glBindImageTexture(0, clean, 0, false, 0, android.opengl.GLES31.GL_WRITE_ONLY, GLES30.GL_R32UI)
            gl.dispatch(w, h)
            android.opengl.GLES31.glMemoryBarrier(android.opengl.GLES31.GL_TEXTURE_FETCH_BARRIER_BIT)
        }
        val p = gl.use("rawdev")
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, clean)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(p, "uRaw"), 0)
        gl.sampler(p, "uShading", 1, shading)
        gl.u1f(p, "uHasShading", if (sw > 0) 1f else 0f)
        val bl = m.getJSONArray("black")
        gl.u4f(p, "uBlack", bl.getDouble(0).toFloat(), bl.getDouble(1).toFloat(), bl.getDouble(2).toFloat(), bl.getDouble(3).toFloat())
        gl.u1f(p, "uWhite", m.getDouble("white").toFloat())
        gl.u1i(p, "uCfa", m.optInt("cfa", 0))
        gl.u3f(p, "uNeutral", neutral[0], neutral[1], neutral[2])
        // GLSL mat3 is column-major: transpose the row-major matrix.
        GLES30.glUniformMatrix3fv(GLES30.glGetUniformLocation(p, "uCamToXyz"), 1, true, fm, 0)
        gl.u1i(p, "uRotate", rot)
        gl.image(0, out, GLES30.GL_RGBA16F)
        // In strips (a whole 12 MP frame in one dispatch is fine at ~30 ms, but keep it safe).
        gl.dispatch(w, h)
        gl.finish()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, out)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        gl.deleteTextures(rawTex, clean, shading)
        gl.checkErr("raw develop")
        app.relens.Trace.i("raw developed ${w}x$h -> ${uw}x$uh, rotate $rot, illuminant mix %.2f, neutral %s".format(t, neutral.joinToString { "%.3f".format(it) }))
        return RawPicture(out, uw, uh, levels)
    }
}
