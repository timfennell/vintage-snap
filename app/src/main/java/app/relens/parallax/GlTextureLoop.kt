package app.relens.parallax

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.view.TextureView

/**
 * A GL ES 3 render loop into a TextureView (not a SurfaceView): the picture stays
 * an ordinary part of the window, so the finder's clip and the eyepiece effect
 * (pincushion, bloom) apply to it as they do to the camera's own preview.
 */
class GlTextureLoop(private val r: Renderer) : TextureView.SurfaceTextureListener {
    interface Renderer {
        fun created()
        fun changed(w: Int, h: Int)
        /** Draw one frame; return false to stop the loop. */
        fun draw(): Boolean
        fun destroyed()
    }

    @Volatile private var running = false
    @Volatile private var size = 0 to 0
    @Volatile private var sizeChanged = false
    private var thread: Thread? = null

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        size = w to h; sizeChanged = true
        running = true
        thread = Thread({ loop(st) }, "parallax-gl").also { it.start() }
    }
    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) { size = w to h; sizeChanged = true }
    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { stop(); return true }
    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    fun stop() {
        running = false
        thread?.let { if (it != Thread.currentThread()) runCatching { it.join(1500) } }
        thread = null
    }

    private fun loop(st: SurfaceTexture) {
        val dpy: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val v = IntArray(2); EGL14.eglInitialize(dpy, v, 0, v, 1)
        val attrs = intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR, EGL14.EGL_NONE)
        val cfgs = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
        EGL14.eglChooseConfig(dpy, attrs, 0, cfgs, 0, 1, n, 0)
        val ctx: EGLContext = EGL14.eglCreateContext(dpy, cfgs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        val surf: EGLSurface = EGL14.eglCreateWindowSurface(dpy, cfgs[0], st, intArrayOf(EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(dpy, surf, surf, ctx)
        try {
            r.created()
            while (running) {
                if (sizeChanged) { sizeChanged = false; r.changed(size.first, size.second) }
                if (!r.draw()) break
                EGL14.eglSwapBuffers(dpy, surf)
            }
        } catch (t: Throwable) {
            app.relens.Trace.e("parallax GL loop", t)
        } finally {
            runCatching { r.destroyed() }
            EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(dpy, surf)
            EGL14.eglDestroyContext(dpy, ctx)
            EGL14.eglTerminate(dpy)
        }
    }
}
