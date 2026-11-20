package app.relens.parallax

import android.opengl.GLES30
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * World-space wireframes drawn as Vintage Click draws its light heads (Shaders.swift
 * wire_vertex / wire_fragment): light projected into the finder, not paint. Each
 * segment is a soft band, brightest along its middle, drawn twice (a faint wide
 * glow, then a fine core) and added onto the picture.
 */
class Wires {
    private var prog = 0
    private var vbo = 0
    private var buf: FloatBuffer = ByteBuffer.allocateDirect(4 * 3 * 6 * 4096).order(ByteOrder.nativeOrder()).asFloatBuffer()

    fun create() {
        prog = Gles.program("""#version 300 es
            layout(location = 0) in vec3 a;       // ndc x, y; across (-1..1)
            out float across;
            void main() { across = a.z; gl_Position = vec4(a.xy, 0.0, 1.0); }""",
            """#version 300 es
            precision mediump float;
            in float across;
            uniform vec4 colour;
            out vec4 o;
            void main() {
                float x = abs(across);
                float profile = exp(-x * x * 3.2) * (1.0 - smoothstep(0.85, 1.0, x));
                float a = profile * colour.a;
                o = vec4(colour.rgb * a, a);
            }""")
        val ids = IntArray(1); GLES30.glGenBuffers(1, ids, 0); vbo = ids[0]
    }

    /** A line strip's worth of segments in world space, with its colour (rgb, alpha). */
    class Shape(val segments: List<Pair<FloatArray, FloatArray>>, val colour: FloatArray)

    /**
     * Draws [shapes] with [view] and [proj] (column-major, as ARCore gives them) into a
     * w x h viewport; [glowPx] and [corePx] are the half widths of the two passes.
     */
    fun draw(shapes: List<Shape>, view: FloatArray, proj: FloatArray, w: Int, h: Int, glowPx: Float, corePx: Float) {
        GLES30.glUseProgram(prog)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)           // added light
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glEnableVertexAttribArray(0)
        val colourLoc = GLES30.glGetUniformLocation(prog, "colour")
        for (s in shapes) {
            for ((half, gain) in listOf(glowPx to 0.16f, corePx to 1f)) {
                val n = build(s.segments, view, proj, w, h, half)
                if (n == 0) continue
                GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, n * 12, buf.position(0), GLES30.GL_STREAM_DRAW)
                GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 12, 0)
                GLES30.glUniform4f(colourLoc, s.colour[0], s.colour[1], s.colour[2], s.colour[3] * gain)
                GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, n)
            }
        }
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private val tmp = FloatArray(4)
    private val va = FloatArray(4)
    private val vb = FloatArray(4)

    /** Segments to screen-space bands (two triangles each); returns the vertex count. */
    private fun build(segs: List<Pair<FloatArray, FloatArray>>, view: FloatArray, proj: FloatArray, w: Int, h: Int, half: Float): Int {
        buf.clear()
        var count = 0
        val near = 0.03f
        for ((p0, p1) in segs) {
            Matrix.multiplyMV(va, 0, view, 0, floatArrayOf(p0[0], p0[1], p0[2], 1f), 0)
            Matrix.multiplyMV(vb, 0, view, 0, floatArrayOf(p1[0], p1[1], p1[2], 1f), 0)
            // camera looks down -z: keep what is in front of the lens
            val za = -va[2]; val zb = -vb[2]
            if (za < near && zb < near) continue
            if (za < near) lerpTo(va, vb, (near - za) / (zb - za))
            if (zb < near) lerpTo(vb, va, (near - zb) / (za - zb))
            val a = toPx(va, proj, w, h); val b = toPx(vb, proj, w, h)
            val dx = b[0] - a[0]; val dy = b[1] - a[1]
            val len = kotlin.math.hypot(dx, dy).coerceAtLeast(1e-3f)
            val ux = dx / len; val uy = dy / len
            val nx = -uy; val ny = ux
            if (buf.remaining() < 18) break
            fun put(x: Float, y: Float, side: Float) {
                buf.put(x / w * 2f - 1f); buf.put(1f - y / h * 2f); buf.put(side); count++
            }
            val ax = a[0] - ux * half; val ay = a[1] - uy * half
            val bx = b[0] + ux * half; val by = b[1] + uy * half
            put(ax - nx * half, ay - ny * half, -1f); put(bx - nx * half, by - ny * half, -1f); put(ax + nx * half, ay + ny * half, 1f)
            put(ax + nx * half, ay + ny * half, 1f); put(bx - nx * half, by - ny * half, -1f); put(bx + nx * half, by + ny * half, 1f)
        }
        return count
    }

    /** Moves view-space point [p] toward [q] by fraction t. */
    private fun lerpTo(p: FloatArray, q: FloatArray, t: Float) { for (i in 0..2) p[i] = p[i] + (q[i] - p[i]) * t }

    private fun toPx(v: FloatArray, proj: FloatArray, w: Int, h: Int): FloatArray {
        Matrix.multiplyMV(tmp, 0, proj, 0, floatArrayOf(v[0], v[1], v[2], 1f), 0)
        val x = tmp[0] / tmp[3]; val y = tmp[1] / tmp[3]
        return floatArrayOf((x + 1f) / 2f * w, (1f - y) / 2f * h)
    }
}

/** Small GL helpers for the parallax view. */
object Gles {
    fun program(vs: String, fs: String): Int {
        fun shader(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src); GLES30.glCompileShader(s)
            val ok = IntArray(1); GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) error("shader: " + GLES30.glGetShaderInfoLog(s))
            return s
        }
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, shader(GLES30.GL_VERTEX_SHADER, vs))
        GLES30.glAttachShader(p, shader(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glLinkProgram(p)
        val ok = IntArray(1); GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) error("link: " + GLES30.glGetProgramInfoLog(p))
        return p
    }
}
