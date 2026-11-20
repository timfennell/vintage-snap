package app.relens.parallax

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.view.Surface
import app.relens.Trace
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.Coordinates2d
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot

/**
 * Parallax capture (Tim's design, docs/PLAN.md): right after the hero shot, ARCore
 * tracks the phone from where it was held. A ghost camera, drawn as Vintage Click
 * draws its light heads (warm cream wireframes of light), waits a few centimetres to
 * the left with the frame it should see; as the phone glides over, the frame closes
 * on the finder's edges, and when position and angle line up it all turns green and
 * the frame is taken by itself. Then the right. Each frame is saved with ARCore's
 * pose and the camera's intrinsics: centre.jpg (where the hero was taken), left.jpg,
 * right.jpg and parallax.json in [dir].
 *
 * Runs on the main camera (the only one ARCore shares on this phone); the hero's
 * own camera is closed first (Rig.release) and reopened after.
 */
class ParallaxCapture(private val ctx: Context, val dir: File, subjectM: Float,
                      /** How the phone is held (0, 90, 180, 270: the orientation listener's degrees). */
                      private val rotation: () -> Int = { 0 },
                      private val onFire: () -> Unit) : GlTextureLoop.Renderer {
    enum class Phase { STARTING, LEFT, RIGHT, DONE, FAILED }

    private val _phase = MutableStateFlow(Phase.STARTING)
    val phase: StateFlow<Phase> = _phase
    private val _hint = MutableStateFlow("finding the room…")
    val hint: StateFlow<String> = _hint
    /** 0 far from the target, 1 on it. */
    private val _closeness = MutableStateFlow(0f)
    val closeness: StateFlow<Float> = _closeness
    /** How the phone is held, for turning the finder's lettering upright. */
    private val _held = MutableStateFlow(0)
    val held: StateFlow<Int> = _held

    /** Where the subject is (m), and how far apart the views are: about a fifteenth of it. */
    private val subject = subjectM.coerceIn(0.3f, 20f)
    private val baseline = (subject / 15f).coerceIn(0.03f, 0.12f)

    @Volatile private var session: Session? = null
    private val done = CompletableDeferred<Boolean>()
    private val saver = Executors.newSingleThreadExecutor()
    val loop = GlTextureLoop(this)

    fun cancel() { if (!done.isCompleted) { Trace.i("parallax cancelled"); done.complete(false) } }

    /** Runs the capture: true when both side frames were taken. */
    suspend fun run(): Boolean {
        dir.mkdirs()
        val s = withContext(Dispatchers.Main) {
            runCatching {
                Session(ctx).apply {
                    val cfgs = getSupportedCameraConfigs(com.google.ar.core.CameraConfigFilter(this))
                    cfgs.maxByOrNull { it.imageSize.width * it.imageSize.height }?.let { cameraConfig = it }
                    configure(Config(this).apply {
                        focusMode = Config.FocusMode.AUTO
                        updateMode = Config.UpdateMode.BLOCKING
                        depthMode = if (isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY)) Config.DepthMode.RAW_DEPTH_ONLY else Config.DepthMode.DISABLED
                    })
                    resume()
                }
            }.onFailure { Trace.e("parallax: ARCore session failed", it) }.getOrNull()
        }
        if (s == null) { _phase.value = Phase.FAILED; _hint.value = "AR isn't available"; return false }
        session = s
        Trace.i("parallax: subject %.2f m, baseline %.1f cm, camera ${s.cameraConfig.imageSize}".format(subject, baseline * 100))
        val ok = withTimeoutOrNull(120_000) { done.await() } ?: false
        withContext(Dispatchers.Main) {
            loop.stop()
            runCatching { s.pause() }; runCatching { s.close() }
        }
        session = null
        saver.shutdown()
        if (ok) writeMeta()
        Trace.i("parallax: ${if (ok) "done" else "not finished"}; frames ${shots.keys}")
        return ok
    }

    // ---- GL ----
    private var camTex = 0
    private var bgProg = 0
    private var quad: java.nio.FloatBuffer = floatBuf(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private var uv: java.nio.FloatBuffer = floatBuf(FloatArray(8))
    private val wires = Wires()
    private var w = 1; private var h = 1
    private var geometrySet = false
    private var density = ctx.resources.displayMetrics.density

    override fun created() {
        val t = IntArray(1); GLES30.glGenTextures(1, t, 0); camTex = t[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, camTex)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        bgProg = Gles.program("""#version 300 es
            layout(location = 0) in vec2 p; layout(location = 1) in vec2 t;
            out vec2 uv; void main() { uv = t; gl_Position = vec4(p, 0.0, 1.0); }""",
            """#version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision mediump float;
            uniform samplerExternalOES cam; in vec2 uv; out vec4 o;
            void main() { o = vec4(texture(cam, uv).rgb, 1.0); }""")
        wires.create()
    }

    override fun changed(w: Int, h: Int) { this.w = w; this.h = h; geometrySet = false; GLES30.glViewport(0, 0, w, h) }

    override fun destroyed() { cancel() }       // the view went away (or the loop failed): stop

    // ---- tracking and guidance ----
    private var origin: Pose? = null              // the display-oriented pose where the hero was taken
    private var heldFrames = 0
    private var trackedFrames = 0
    private val shots = linkedMapOf<String, JSONObject>()
    private var doneAt = 0L

    override fun draw(): Boolean {
        if (done.isCompleted) return false
        val s = session ?: run { GLES30.glClearColor(0f, 0f, 0f, 1f); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT); Thread.sleep(16); return true }
        s.setCameraTextureName(camTex)
        if (!geometrySet) { s.setDisplayGeometry(Surface.ROTATION_0, w, h); geometrySet = true }
        val frame = runCatching { s.update() }.getOrElse { Trace.w("parallax update", it); Thread.sleep(16); return true }
        drawBackground(frame)
        val cam = frame.camera
        if (cam.trackingState != TrackingState.TRACKING) {
            trackedFrames = 0
            _hint.value = if (origin == null) "finding the room… move a little" else "hold on: finding the room again"
            return true
        }
        val pose = cam.displayOrientedPose
        // ARCore's first poses jump about while it settles its scale: let it track steadily for
        // half a second before the hero's place is fixed.
        if (origin == null && ++trackedFrames < 15) { _hint.value = "hold steady…"; return true }
        val o = origin ?: run {
            origin = pose
            save(frame, "centre")
            _phase.value = Phase.LEFT
            pose
        }
        val view = FloatArray(16); val proj = FloatArray(16)
        cam.getViewMatrix(view, 0); cam.getProjectionMatrix(proj, 0, 0.02f, 100f)
        val tx = 1f / proj[0]; val ty = 1f / proj[5]

        val phase = _phase.value
        if (phase == Phase.DONE) {
            if (System.currentTimeMillis() - doneAt > 500) done.complete(true)
            return true
        }
        val side = if (phase == Phase.LEFT) -1f else 1f
        // Left and right are the room's, whichever way the phone is held: along the level
        // line across the hero's view (ARCore's world y is up, against gravity).
        val f = o.zAxis.let { z -> floatArrayOf(-z[0], -z[1], -z[2]) }
        val up = floatArrayOf(0f, 1f, 0f)
        val r = norm(cross(f, up)).takeIf { it.any { c -> c != 0f } } ?: o.xAxis
        val ot = o.translation
        val target = Pose(floatArrayOf(ot[0] + side * baseline * r[0], ot[1] + side * baseline * r[1], ot[2] + side * baseline * r[2]),
            o.rotationQuaternion)

        // How far off: across (the room's right), up, and along the view (back positive), and the turn.
        val pt = pose.translation; val tt = target.translation
        val e = floatArrayOf(pt[0] - tt[0], pt[1] - tt[1], pt[2] - tt[2])
        val ex = dot(e, r); val ey = dot(e, norm(cross(r, f))); val ez = -dot(e, f)
        val lateral = hypot(ex, ey)
        val turn = angleBetween(target, pose)
        val onIt = lateral < 0.015f && abs(ez) < 0.04f && turn < 5f
        _closeness.value = (1f - lateral / baseline).coerceIn(0f, 1f) * (if (turn < 5f) 1f else 0.6f)
        // The way to go, as the photographer sees it: the move into the screen's axes, turned
        // to how the phone is held (the hint is drawn upright for them).
        val held = rotation(); _held.value = held
        val mv = FloatArray(4)
        val viewNow = FloatArray(16); cam.getViewMatrix(viewNow, 0)
        android.opengl.Matrix.multiplyMV(mv, 0, viewNow, 0, floatArrayOf(-e[0], -e[1], -e[2], 0f), 0)
        val th = Math.toRadians(-held.toDouble())
        val ux = (mv[0] * kotlin.math.cos(th) - mv[1] * kotlin.math.sin(th)).toFloat()
        val uy = (mv[0] * kotlin.math.sin(th) + mv[1] * kotlin.math.cos(th)).toFloat()
        _hint.value = when {
            onIt -> "hold…"
            lateral >= 0.015f -> {
                val arrow = if (abs(ux) > abs(uy)) (if (ux > 0) "→" else "←") else (if (uy > 0) "↑" else "↓")
                "$arrow  %.1f cm".format(lateral * 100)
            }
            abs(ez) >= 0.04f -> if (ez > 0) "a little closer" else "a little back"
            else -> "turn back toward the subject"
        }

        // The ghost: the camera at the target, the frame it should see, the subject.
        val green = floatArrayOf(0.45f, 0.95f, 0.70f, 0.85f)
        val cream = floatArrayOf(1.0f, 0.92f, 0.80f, 0.55f)
        val warm = floatArrayOf(1.0f, 0.80f, 0.55f, 0.80f)
        val c = _closeness.value
        val frameColour = if (onIt) green else floatArrayOf(warm[0] + (green[0] - warm[0]) * c * c, warm[1] + (green[1] - warm[1]) * c * c,
            warm[2] + (green[2] - warm[2]) * c * c, warm[3])
        val shapes = listOf(
            Wires.Shape(ghostCamera(target, o), if (onIt) green else cream),
            Wires.Shape(targetFrame(target, tx, ty), frameColour),
            Wires.Shape(dashedCircle(o, subject, tx * subject * 0.12f), cream),
        )
        wires.draw(shapes, view, proj, w, h, glowPx = 4f * density, corePx = 1.1f * density)

        if (onIt) heldFrames++ else heldFrames = 0
        if (heldFrames >= 4) {
            heldFrames = 0
            save(frame, if (phase == Phase.LEFT) "left" else "right")
            onFire()
            if (phase == Phase.LEFT) _phase.value = Phase.RIGHT
            else { _phase.value = Phase.DONE; _hint.value = "done"; doneAt = System.currentTimeMillis() }
        }
        return true
    }

    private fun drawBackground(frame: Frame) {
        if (frame.hasDisplayGeometryChanged()) {
            val out = FloatArray(8)
            frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f),
                Coordinates2d.TEXTURE_NORMALIZED, out)
            uv = floatBuf(out)
        }
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glUseProgram(bgProg)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, camTex)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glEnableVertexAttribArray(0); GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, quad.position(0))
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 0, uv.position(0))
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(0); GLES30.glDisableVertexAttribArray(1)
    }

    /** The turn between two poses' orientations, in degrees. */
    private fun angleBetween(a: Pose, b: Pose): Float {
        val qa = a.rotationQuaternion; val qb = b.rotationQuaternion
        val dot = abs(qa[0] * qb[0] + qa[1] * qb[1] + qa[2] * qb[2] + qa[3] * qb[3]).coerceAtMost(1f)
        return Math.toDegrees(2.0 * acos(dot.toDouble())).toFloat()
    }

    // ---- the wireframes (world space), as Vintage Click's cameraWireframe ----
    private fun seg(p: Pose, a: FloatArray, b: FloatArray) = p.transformPoint(a) to p.transformPoint(b)

    /** A small camera body at the target: a box behind the lens, the lens ring, and its line of sight. */
    private fun ghostCamera(t: Pose, o: Pose): List<Pair<FloatArray, FloatArray>> {
        val w = 0.07f; val hh = 0.045f; val d = 0.035f
        val corners = listOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f)
        val front = corners.map { (x, y) -> floatArrayOf(x * w / 2, y * hh / 2, 0f) }
        val back = corners.map { (x, y) -> floatArrayOf(x * w / 2, y * hh / 2, d) }
        val segs = ArrayList<Pair<FloatArray, FloatArray>>()
        for (i in 0..3) {
            segs += seg(t, front[i], front[(i + 1) % 4]); segs += seg(t, back[i], back[(i + 1) % 4]); segs += seg(t, front[i], back[i])
        }
        val r = hh * 0.4f
        for (k in 0 until 16) {
            val a0 = k / 16f * 2 * Math.PI.toFloat(); val a1 = (k + 1) / 16f * 2 * Math.PI.toFloat()
            segs += seg(t, floatArrayOf(r * kotlin.math.cos(a0), r * kotlin.math.sin(a0), -0.004f), floatArrayOf(r * kotlin.math.cos(a1), r * kotlin.math.sin(a1), -0.004f))
        }
        segs += t.transformPoint(floatArrayOf(0f, 0f, -0.004f)) to o.transformPoint(floatArrayOf(0f, 0f, -subject))
        return segs
    }

    /** The frame the target view should see, at the subject's distance: it closes on the finder's edges. */
    private fun targetFrame(t: Pose, tx: Float, ty: Float): List<Pair<FloatArray, FloatArray>> {
        val x = tx * subject * 0.9f; val y = ty * subject * 0.9f; val z = -subject
        val c = listOf(floatArrayOf(-x, -y, z), floatArrayOf(x, -y, z), floatArrayOf(x, y, z), floatArrayOf(-x, y, z))
        return (0..3).map { seg(t, c[it], c[(it + 1) % 4]) }
    }

    /** A dashed ring round the subject, facing the hero camera (as the game marks a light's target). */
    private fun dashedCircle(o: Pose, dist: Float, r: Float): List<Pair<FloatArray, FloatArray>> {
        val n = 36
        return (0 until n step 2).map { k ->
            val a0 = k / n.toFloat() * 2 * Math.PI.toFloat(); val a1 = (k + 1) / n.toFloat() * 2 * Math.PI.toFloat()
            seg(o, floatArrayOf(r * kotlin.math.cos(a0), r * kotlin.math.sin(a0), -dist), floatArrayOf(r * kotlin.math.cos(a1), r * kotlin.math.sin(a1), -dist))
        }
    }

    // ---- saving ----
    private fun save(frame: Frame, name: String) {
        val cam = frame.camera
        val img = runCatching { frame.acquireCameraImage() }.getOrElse { Trace.w("parallax: no camera image for $name", it); return }
        val iw = img.width; val ih = img.height
        val nv21 = try { toNv21(img) } finally { img.close() }
        val intr = cam.imageIntrinsics
        val pm = FloatArray(16); cam.pose.toMatrix(pm, 0)                 // the sensor's pose (the image as it lies)
        val dm = FloatArray(16); cam.displayOrientedPose.toMatrix(dm, 0)
        val meta = JSONObject().apply {
            put("file", "$name.jpg"); put("width", iw); put("height", ih)
            put("timestampNs", frame.timestamp)
            put("focal", JSONArray(intr.focalLength.toList())); put("principal", JSONArray(intr.principalPoint.toList()))
            put("pose", JSONArray(pm.toList())); put("displayPose", JSONArray(dm.toList()))
        }
        // ARCore's own depth at the centre: a check on the stereo depth later.
        if (name == "centre") runCatching {
            frame.acquireRawDepthImage16Bits().use { d ->
                val b = d.planes[0].buffer; val a = ByteArray(b.remaining()); b.get(a)
                File(dir, "centre.depth16").writeBytes(a)
                meta.put("depth16", JSONObject().put("width", d.width).put("height", d.height).put("rowStride", d.planes[0].rowStride))
            }
        }.onFailure { Trace.i("parallax: no raw depth yet (${it.javaClass.simpleName})") }
        shots[name] = meta
        saver.execute {
            runCatching {
                File(dir, "$name.jpg").outputStream().use { YuvImage(nv21, ImageFormat.NV21, iw, ih, null).compressToJpeg(Rect(0, 0, iw, ih), 92, it) }
            }.onFailure { Trace.w("parallax: $name not saved", it) }
        }
        Trace.i("parallax: $name frame ${iw}x$ih at ${cam.pose.tx().fmt()}, ${cam.pose.ty().fmt()}, ${cam.pose.tz().fmt()}")
    }

    private fun writeMeta() {
        val j = JSONObject().put("subjectM", subject.toDouble()).put("baselineM", baseline.toDouble())
        val fr = JSONObject(); for ((k, v) in shots) fr.put(k, v); j.put("frames", fr)
        File(dir, "parallax.json").writeText(j.toString(1))
    }

    private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun norm(a: FloatArray): FloatArray { val l = kotlin.math.sqrt(dot(a, a)); return if (l < 1e-6f) floatArrayOf(0f, 0f, 0f) else floatArrayOf(a[0] / l, a[1] / l, a[2] / l) }

    private fun Float.fmt() = "%.3f".format(this)

    private fun toNv21(img: Image): ByteArray {
        val w = img.width; val h = img.height
        val out = ByteArray(w * h * 3 / 2)
        val y = img.planes[0]; val u = img.planes[1]; val v = img.planes[2]
        val yb = y.buffer; var o = 0
        if (y.pixelStride == 1) for (row in 0 until h) { yb.position(row * y.rowStride); yb.get(out, o, w); o += w }
        else for (row in 0 until h) for (col in 0 until w) out[o++] = yb.get(row * y.rowStride + col * y.pixelStride)
        val ub = u.buffer; val vb = v.buffer
        for (row in 0 until h / 2) for (col in 0 until w / 2) {
            out[o++] = vb.get(row * v.rowStride + col * v.pixelStride)
            out[o++] = ub.get(row * u.rowStride + col * u.pixelStride)
        }
        return out
    }

    private fun floatBuf(a: FloatArray) = ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(a); position(0) }
}
