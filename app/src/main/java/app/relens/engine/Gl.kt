package app.relens.engine

import android.content.res.AssetManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.GLES31
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** A headless OpenGL ES 3.1 context for compute work, with small helpers. */
class Gl(private val assets: AssetManager) : AutoCloseable {
    private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val context: EGLContext
    private val surface: EGLSurface
    private val programs = HashMap<String, Int>()
    private val common = assets.open("shaders/common.glsl").bufferedReader().readText()

    init {
        val v = IntArray(2)
        check(EGL14.eglInitialize(display, v, 0, v, 1)) { "eglInitialize failed" }
        val attrs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, 0x40 /* EGL_OPENGL_ES3_BIT_KHR */,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        check(EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, n, 0) && n[0] > 0) { "no ES3 config" }
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
        surface = EGL14.eglCreatePbufferSurface(display, configs[0],
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent failed" }
    }

    val renderer: String get() = GLES30.glGetString(GLES30.GL_RENDERER) ?: "?"
    val version: String get() = GLES30.glGetString(GLES30.GL_VERSION) ?: "?"
    val vendor: String get() = GLES30.glGetString(GLES30.GL_VENDOR) ?: "?"
    val maxTextureSize: Int get() = IntArray(1).also { GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, it, 0) }[0]
    fun hasExtension(name: String): Boolean {
        val n = IntArray(1).also { GLES30.glGetIntegerv(GLES30.GL_NUM_EXTENSIONS, it, 0) }[0]
        return (0 until n).any { GLES30.glGetStringi(GLES30.GL_EXTENSIONS, it) == name }
    }

    fun program(name: String): Int = programs.getOrPut(name) {
        val body = assets.open("shaders/$name.comp").bufferedReader().readText()
        val src = "#version 310 es\n$common\n#line 1\n$body"
        val sh = GLES31.glCreateShader(GLES31.GL_COMPUTE_SHADER)
        GLES31.glShaderSource(sh, src)
        GLES31.glCompileShader(sh)
        val ok = IntArray(1).also { GLES31.glGetShaderiv(sh, GLES31.GL_COMPILE_STATUS, it, 0) }[0]
        if (ok == 0) error("$name: ${GLES31.glGetShaderInfoLog(sh)}")
        val p = GLES31.glCreateProgram()
        GLES31.glAttachShader(p, sh)
        GLES31.glLinkProgram(p)
        val linked = IntArray(1).also { GLES31.glGetProgramiv(p, GLES31.GL_LINK_STATUS, it, 0) }[0]
        if (linked == 0) error("$name link: ${GLES31.glGetProgramInfoLog(p)}")
        GLES31.glDeleteShader(sh)
        p
    }

    fun use(name: String): Int = program(name).also { GLES31.glUseProgram(it) }

    fun texture(w: Int, h: Int, format: Int, levels: Int = 1, linear: Boolean = true): Int {
        val t = IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t)
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, levels, format, w, h)
        val minF = when {
            !linear -> GLES30.GL_NEAREST
            levels > 1 -> GLES30.GL_LINEAR_MIPMAP_LINEAR
            else -> GLES30.GL_LINEAR
        }
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, minF)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, if (linear) GLES30.GL_LINEAR else GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_BASE_LEVEL, 0)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAX_LEVEL, levels - 1)
        checkErr("texture ${w}x$h")
        return t
    }

    fun upload(tex: Int, w: Int, h: Int, format: Int, type: Int, data: Buffer) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, w, h, format, type, data)
        checkErr("upload")
    }

    fun deleteTextures(vararg t: Int) = GLES30.glDeleteTextures(t.size, t, 0)

    fun sampler(prog: Int, name: String, unit: Int, tex: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(prog, name), unit)
    }

    fun image(unit: Int, tex: Int, format: Int, level: Int = 0) {
        GLES31.glBindImageTexture(unit, tex, level, false, 0, GLES31.GL_WRITE_ONLY, format)
    }

    fun u1f(p: Int, n: String, x: Float) = GLES30.glUniform1f(GLES30.glGetUniformLocation(p, n), x)
    fun u2f(p: Int, n: String, x: Float, y: Float) = GLES30.glUniform2f(GLES30.glGetUniformLocation(p, n), x, y)
    fun u4f(p: Int, n: String, a: Float, b: Float, c: Float, d: Float) = GLES30.glUniform4f(GLES30.glGetUniformLocation(p, n), a, b, c, d)
    fun u1i(p: Int, n: String, x: Int) = GLES30.glUniform1i(GLES30.glGetUniformLocation(p, n), x)
    fun u1ui(p: Int, n: String, x: Int) = GLES30.glUniform1ui(GLES30.glGetUniformLocation(p, n), x)
    fun u2i(p: Int, n: String, x: Int, y: Int) = GLES30.glUniform2i(GLES30.glGetUniformLocation(p, n), x, y)
    fun u4fv(p: Int, n: String, v: FloatArray) = GLES30.glUniform4fv(GLES30.glGetUniformLocation(p, n), v.size / 4, v, 0)

    fun ubo(data: FloatArray): Int {
        val b = IntArray(1).also { GLES30.glGenBuffers(1, it, 0) }[0]
        GLES30.glBindBuffer(GLES31.GL_UNIFORM_BUFFER, b)
        val fb = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(data)
        fb.position(0)
        GLES30.glBufferData(GLES31.GL_UNIFORM_BUFFER, data.size * 4, fb, GLES30.GL_STATIC_DRAW)
        return b
    }

    fun dispatch(w: Int, h: Int) {
        GLES31.glDispatchCompute((w + 7) / 8, (h + 7) / 8, 1)
        GLES31.glMemoryBarrier(GLES31.GL_ALL_BARRIER_BITS)
    }

    fun finish() = GLES30.glFinish()

    /** Reads an RGBA8 texture back (the export path). */
    fun readRgba8(tex: Int, w: Int, h: Int, into: ByteBuffer) {
        val fbo = IntArray(1).also { GLES30.glGenFramebuffers(1, it, 0) }[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex, 0)
        GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 1)
        into.position(0)
        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, into)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        checkErr("readPixels")
    }

    /** Reads a float texture level back (debug): RGBA floats. */
    fun readFloat(tex: Int, w: Int, h: Int, level: Int = 0): FloatArray {
        val fbo = IntArray(1).also { GLES30.glGenFramebuffers(1, it, 0) }[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex, level)
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        val buf = ByteBuffer.allocateDirect(w * h * 16).order(ByteOrder.nativeOrder())
        if (status == GLES30.GL_FRAMEBUFFER_COMPLETE)
            GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_FLOAT, buf)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        checkErr("readFloat status 0x${Integer.toHexString(status)}")
        return FloatArray(w * h * 4).also { buf.asFloatBuffer().get(it) }
    }

    fun checkErr(what: String) {
        val e = GLES30.glGetError()
        if (e != GLES30.GL_NO_ERROR) error("GL error 0x${Integer.toHexString(e)} at $what")
    }

    override fun close() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, surface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglTerminate(display)
    }
}

fun floatBuffer(a: FloatArray): FloatBuffer =
    ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(a).also { it.position(0) }
