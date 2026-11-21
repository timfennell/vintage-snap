package app.relens.engine

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.opengl.GLES30
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import app.relens.film.FilmStock
import app.relens.Trace
import app.relens.optics.Lens
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** One frame to develop: the camera's files and what the body was set to. */
data class DevelopJob(
    val jpeg: File,
    val dng: File?,
    /** Where the photographer focused, fractions of the upright frame. */
    val focusX: Float,
    val focusY: Float,
    /** The phone camera's own focus distance for this frame, dioptres (null: unknown). */
    val cameraFocusD: Float?,
    val lens: Lens,
    val fNumber: Float,
    /** The focus ring, metres; null = on the tapped subject. */
    val focusM: Float?,
    val stock: FilmStock,
    val push: Int,
    val frame: Int,
    /** A tilt (or whacked) lens's tilt, degrees: x turns the focus plane across the frame, y up and down. */
    val tiltDeg: FloatArray = floatArrayOf(0f, 0f),
    /** A shift lens's shift, mm: right and up. */
    val shiftMM: FloatArray = floatArrayOf(0f, 0f),
    /** Copy the print into the gallery (the bench's test developments don't). */
    val publish: Boolean = true,
    /** The filter on the lens. */
    val filter: app.relens.optics.LensFilter = app.relens.optics.lensFilters[0],
    /** The lens's field of view: this much of the camera's frame (long side), cropped to 3:2. 0 = no crop (old shots). */
    val crop: Float = 0f,
    /** The camera's focal length over its sensor's long side (the uncropped frame). */
    val focalOverLong: Float = 0.68f,
    /** Autofocus locks from recent taps: measured distances at points of the frame. */
    val afSamples: List<app.relens.camera.AfSample> = emptyList(),
    /** Stops over (+) or under (-) as the body's meter and shutter set it (0: as the phone metered). */
    val exposureStops: Float = 0f,
    /** Also save a linear DNG of the relensed picture (before the film) beside the print. */
    val saveDng: Boolean = false,
    /** Use the parallax side frames for what lies behind the foreground (off: compare). */
    val plate: Boolean = true,
)

/** The smallest long side a cropped print is developed at (it's enlarged up to 2x to get there). */
private const val PRINT_LONG_MIN = 3456f

class Print(
    val uri: Uri?,
    val file: File,
    val preview: Bitmap,
    /** The camera's picture at the same size, for before / after. */
    val original: Bitmap,
    val timings: String,
    /** ARCore's measured distance at the focus point (dioptres), when the frame had parallax. */
    val arFocusD: Float? = null,
)

/**
 * Develops frames on one thread that owns the GL context and the depth
 * session: JPEG -> depth -> the lens -> the film -> a print in the gallery.
 */
class Developer(private val context: Context) {
    private val dispatcher = Executors.newSingleThreadExecutor { Thread(it, "relens-gpu") }.asCoroutineDispatcher()
    private var gl: Gl? = null
    private var engine: LensEngine? = null
    private var depth: DepthEstimator? = null
    private var metric: MetricDepth? = null
    val metricFile: File get() = File(context.getExternalFilesDir(null), "models/metric3d_vits.onnx")
    /** The phone camera's focal length over its sensor's long side (the Nothing main camera: 5.56 / 8.19 mm). */
    var viewFocal = 0.68f

    /** The depth model: the 1036 px export (5.6 s, resolves chair legs) if present, else the 518 px one. */
    val modelFile: File get() {
        val dir = File(context.getExternalFilesDir(null), "models")
        return listOf("da2s_fp32_770x1036.onnx", "da2s_fp32_392x518.onnx").map { File(dir, it) }.firstOrNull { it.exists() }
            ?: File(dir, "da2s_fp32_770x1036.onnx")
    }

    /**
     * Test (from the Mac): the RAW negative developed alone (no lens, no film), written as
     * little-endian half floats (RGBA, upright, row 0 first) + `<out>.json` with the size and time.
     * The iOS port develops the same negative and compares (vintage_snap_ios).
     */
    suspend fun rawDevelopTest(raw16: File, meta: File, out: File) = withContext(dispatcher) {
        val g = gl ?: Gl(context.assets).also { gl = it; engine = LensEngine(it) }
        val t0 = System.nanoTime()
        val rp = RawDeveloper.develop(g, raw16, meta)
        val ms = (System.nanoTime() - t0) / 1_000_000
        writeHalf(g, rp.tex, rp.width, rp.height, out)
        g.deleteTextures(rp.tex)
        File(out.path + ".json").writeText("{\"width\":${rp.width},\"height\":${rp.height},\"ms\":$ms}")
    }

    /**
     * Test (from the Mac): Depth Anything on a whole JPEG (no lens crop), the normalised
     * disparity written as little-endian float32 (row 0 first) + `<out>.json` with the size
     * and time. The iOS port runs its Core ML conversion on the same JPEG and compares.
     */
    suspend fun depthTest(jpeg: File, out: File) = withContext(dispatcher) {
        val photo = decodeUpright(jpeg)
        val t0 = System.nanoTime()
        val est = (depth ?: DepthEstimator(modelFile).also { depth = it }).estimate(photo.bmp)
        val ms = (System.nanoTime() - t0) / 1_000_000
        val b = java.nio.ByteBuffer.allocate(est.depth.data.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.asFloatBuffer().put(est.depth.data)
        out.writeBytes(b.array())
        // Metric3D on the same picture (the main camera's focal over sensor long side) and the
        // relative-to-metric fit (disparity = a * rel + b), written beside it.
        var fit = ""
        if (metricFile.exists()) {
            val md = (metric ?: MetricDepth(metricFile).also { metric = it }).estimate(photo.bmp, 5.56f / 8.19f)
            val mb = java.nio.ByteBuffer.allocate(md.data.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            mb.asFloatBuffer().put(md.data)
            File(out.parentFile, out.name.replace("depth_", "metric_")).writeBytes(mb.array())
            val ab = MetricDepth.fit(est.depth, md)
            fit = ",\"metricWidth\":${md.w},\"metricHeight\":${md.h},\"a\":${ab[0]},\"b\":${ab[1]}"
        }
        // The depth at full resolution along the picture's edges (LensEngine's jbu + refine,
        // the same sigmas, no shift-lens warp), on the RAW developed alone: jbu_<neg>.f32.
        val raw16 = File(jpeg.parentFile, jpeg.nameWithoutExtension + ".raw16")
        val rawMeta = File(jpeg.parentFile, jpeg.nameWithoutExtension + ".rawmeta.json")
        if (raw16.exists() && rawMeta.exists()) {
            val g = gl ?: Gl(context.assets).also { gl = it; engine = LensEngine(it) }
            val rp = RawDeveloper.develop(g, raw16, rawMeta)
            val dm = est.depth
            val dispLo = g.texture(dm.w, dm.h, android.opengl.GLES30.GL_R32F, linear = false)
            g.upload(dispLo, dm.w, dm.h, android.opengl.GLES30.GL_RED, android.opengl.GLES30.GL_FLOAT, floatBuffer(dm.data))
            val guideLo = g.texture(dm.w, dm.h, android.opengl.GLES30.GL_RGBA8)
            upload(g, guideLo, Bitmap.createScaledBitmap(est.seen, dm.w, dm.h, true))
            val w = rp.width; val h = rp.height
            var disp = g.texture(w, h, android.opengl.GLES30.GL_R32F, linear = false)
            val p = g.use("jbu")
            g.u4f(p, "uWarp", 0f, 1f, 0f, 1f); g.u2f(p, "uWarpFZ", 1f, 1f)
            g.sampler(p, "uDispLo", 0, dispLo); g.sampler(p, "uGuideLo", 1, guideLo); g.sampler(p, "uSrc", 2, rp.tex)
            g.image(0, disp, android.opengl.GLES30.GL_R32F)
            g.u1f(p, "uSigmaRange", 0.08f)
            g.dispatch(w, h)
            val tmp = g.texture(w, h, android.opengl.GLES30.GL_R32F, linear = false)
            val pr = g.use("refine")
            g.u1f(pr, "uSigmaRange", 0.05f)
            var from = disp; var to = tmp
            for (step in intArrayOf(1, 2, 4)) {
                g.sampler(pr, "uDisp", 0, from); g.sampler(pr, "uSrc", 1, rp.tex)
                g.u1i(pr, "uStep", step); g.image(0, to, android.opengl.GLES30.GL_R32F)
                g.dispatch(w, h)
                val t = from; from = to; to = t
            }
            disp = from
            g.finish()
            val rows = 64
            val buf = java.nio.ByteBuffer.allocateDirect(w * rows * 16).order(java.nio.ByteOrder.nativeOrder())
            val o = java.nio.ByteBuffer.allocate(w * rows * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            java.io.FileOutputStream(File(out.parentFile, out.name.replace("depth_", "jbu_"))).channel.use { ch ->
                var y = 0
                while (y < h) {
                    val n = minOf(rows, h - y)
                    g.readFloatRows(disp, w, y, n, buf)
                    val f = buf.asFloatBuffer()
                    o.clear()
                    for (i in 0 until w * n) o.putFloat(f.get(i * 4))
                    o.flip(); ch.write(o)
                    y += n
                }
            }
            g.deleteTextures(rp.tex, dispLo, guideLo, disp, to)
        }
        File(out.path + ".json").writeText("{\"width\":${est.depth.w},\"height\":${est.depth.h},\"ms\":$ms,\"model\":\"${modelFile.name}\"$fit}")
    }

    /** Test (from the Mac): where to write the picture right after the gather (before flare, filter, film). */
    var gatherDump: File? = null

    /** An RGBA float texture as little-endian half floats, row 0 first (for the iOS port's checks). */
    private fun writeHalf(g: Gl, tex: Int, w: Int, h: Int, out: File) {
        val rows = 64
        val buf = java.nio.ByteBuffer.allocateDirect(w * rows * 16).order(java.nio.ByteOrder.nativeOrder())
        val half = java.nio.ByteBuffer.allocate(w * rows * 8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        java.io.FileOutputStream(out).channel.use { ch ->
            var y = 0
            while (y < h) {
                val n = minOf(rows, h - y)
                g.readFloatRows(tex, w, y, n, buf)
                val f = buf.asFloatBuffer()
                half.clear()
                for (i in 0 until w * n * 4) half.putShort(android.util.Half.toHalf(f.get(i)))
                half.flip(); ch.write(half)
                y += n
            }
        }
    }

    suspend fun develop(job: DevelopJob): Print = withContext(dispatcher) {
        val t = LinkedHashMap<String, Long>()
        var t0 = System.nanoTime()
        fun lap(name: String) { val n = System.nanoTime(); t[name] = (n - t0) / 1_000_000; t0 = n }

        val full = decodeUpright(job.jpeg); lap("decode")
        // The lens's field of view on a 3:2 frame; the rest of the camera's frame is kept
        // to find lights just outside the picture (their flare still reaches the lens).
        val crop = cropRect(full.bmp.width, full.bmp.height, job.crop)
        val cropped = if (job.crop > 0f) Bitmap.createBitmap(full.bmp, crop.left, crop.top, crop.width(), crop.height()) else full.bmp
        // A long lens on a shorter camera (a 50-70 mm on the main camera) leaves a small crop: the
        // print is developed larger (up to twice), so the blur, grain and flare are drawn at print
        // size rather than enlarged after. No detail is invented: the camera's is resampled.
        val up = if (job.crop > 0f) (PRINT_LONG_MIN / maxOf(cropped.width, cropped.height).toFloat()).coerceIn(1f, 2f) else 1f
        val photo = Upright(if (up > 1.01f) Bitmap.createScaledBitmap(cropped, (cropped.width * up).toInt(), (cropped.height * up).toInt(), true)
            .also { if (cropped !== full.bmp) cropped.recycle() } else cropped)
        if (up > 1.01f) Trace.i("print developed at x%.2f the crop: %dx%d".format(up, photo.bmp.width, photo.bmp.height))
        val focalOverLong = job.focalOverLong * (if (job.crop > 0f) job.crop else 1f)
        val est = (depth ?: DepthEstimator(modelFile).also { depth = it }).estimate(photo.bmp); lap("depth")
        val g = gl ?: Gl(context.assets).also { gl = it; engine = LensEngine(it) }
        val eng = engine!!
        val w = photo.bmp.width; val h = photo.bmp.height

        // From the RAW negative where there is one (scene-linear, highlights not clipped),
        // else from the JPEG.
        val raw16 = job.dng?.let { File(it.parentFile, it.nameWithoutExtension + ".raw16") }?.takeIf { it.exists() }
        val rawMeta = job.dng?.let { File(it.parentFile, it.nameWithoutExtension + ".rawmeta.json") }?.takeIf { it.exists() }
        var rawPic: RawPicture? = null
        var exposure = 1f
        var srcRect = floatArrayOf(0f, 0f, 1f, 1f)
        val src: Int
        if (raw16 != null && rawMeta != null) {
            val rp = runCatching { RawDeveloper.develop(g, raw16, rawMeta) }.onFailure { app.relens.Trace.w("raw develop failed", it) }.getOrNull()
            rawPic = rp
        }
        val rp = rawPic
        if (rp != null) {
            src = rp.tex
            srcRect = floatArrayOf(crop.left / full.bmp.width.toFloat(), crop.top / full.bmp.height.toFloat(),
                crop.width() / full.bmp.width.toFloat(), crop.height() / full.bmp.height.toFloat())
            // Exposure: the RAW's mean brightness over the crop matched to the JPEG's (linearised):
            // what the photographer saw, with the highlights the JPEG clipped still there.
            exposure = matchExposure(g, rp, photo.bmp, srcRect)
            lap("raw")
        } else {
            src = g.texture(w, h, GLES30.GL_RGBA8)
            upload(g, src, photo.bmp)
        }
        val dm = est.depth
        // A parallax capture's own measured depth (ARCore), when there is one: it makes the
        // relative depth metric and corrects it region by region (before it goes to the GPU).
        val ar = ArDepth.load(job.jpeg, photo.bmp, full.bmp.width, full.bmp.height, crop, job.focalOverLong,
            gl = g, debugDir = if (job.publish) null else context.getExternalFilesDir("prints"))
        // ARCore's distance right at the focus point, for the camera's focus calibration
        val arFocusD = ar?.disparityAround(job.focusX, job.focusY)
        val arFit = ar?.calibrate(dm)?.also { lap("ar depth") }
        val dispLo = g.texture(dm.w, dm.h, GLES30.GL_R32F, linear = false)
        g.upload(dispLo, dm.w, dm.h, GLES30.GL_RED, GLES30.GL_FLOAT, floatBuffer(dm.data))
        val guide = Bitmap.createScaledBitmap(est.seen, dm.w, dm.h, true)
        val guideLo = g.texture(dm.w, dm.h, GLES30.GL_RGBA8)
        upload(g, guideLo, guide)

        // Scene scale. Depth Anything's disparity is relative (unknown scale and shift); it is
        // pinned by two anchors, a line through both:
        //  - the focus: what is sharp in the phone's picture is at the distance its lens was
        //    focused at. With autofocus, the tapped point at the camera's distance (when it is
        //    calibrated and plausible); with the ring set by hand, the sharpest depth in the
        //    picture at the ring's distance (if one clearly stands out);
        //  - the far end: the background's distance from Metric3D (sound for far things).
        // Without Metric3D: disparity ~ 1 / distance, the far end at infinity.
        val dTap = dm.medianAround(job.focusX, job.focusY)
        val camD = job.cameraFocusD?.takeIf { it > 0.05f }
        var dispA: Float; var dispB: Float
        if (arFit != null) {
            // Measured: ARCore's line, with the focus point pinned exactly on its distance when
            // the two agree (within half again: a lock elsewhere, or a ring set off the subject, doesn't).
            dispB = arFit[0]; dispA = arFit[1]
            val fitTap = dispA + dispB * dTap
            val anchor: Pair<Float, Float>? = if (job.focusM == null) camD?.let { Pair(dTap, it) }
            else {
                val ringD = if (job.focusM.isInfinite()) 0f else 1f / job.focusM
                Sharpness.peak(est.seen, dm)?.let { Pair(it.first, ringD) }
            }
            val agree = anchor != null && (anchor.second / maxOf(dispA + dispB * anchor.first, 1e-3f)) in 0.67f..1.5f
            if (anchor != null && agree) dispA = anchor.second - dispB * anchor.first
            Trace.i("depth from ARCore: tap at %.2f m%s".format(1 / maxOf(fitTap, 1e-3f),
                anchor?.let { " · focus %.2f m %s".format(1 / maxOf(it.second, 1e-3f), if (agree) "(pinned)" else "(disagrees: ARCore kept)") } ?: ""))
            val minRel = dm.data.minOrNull() ?: 0f
            dispA = maxOf(dispA, 1f / 60f - dispB * minRel)
        } else if (metricFile.exists()) {
            val md = (metric ?: MetricDepth(metricFile).also { metric = it }).estimate(photo.bmp, focalOverLong); lap("metric")
            val ab = MetricDepth.fit(dm, md)
            val fitB = maxOf(ab[0], 1e-3f); val fitA = ab[1]
            dispB = fitB; dispA = fitA
            val sorted = dm.data.copyOf().also { it.sort() }
            val dFar = sorted[(sorted.size * 0.05).toInt()]
            val farD = maxOf(fitA + fitB * dFar, 1f / 60f)
            val anchor: Pair<Float, Float>? = if (job.focusM == null) {
                val fitTap = fitA + fitB * dTap
                // Metric3D misjudges very close things (a hand at 0.2 m put at 0.66 m): allow 5x.
                val ok = camD != null && (camD / maxOf(fitTap, 1e-3f)) in 0.2f..5f
                app.relens.Trace.i("AF anchor: tap rel %.2f, camera %s, fit %.2f m%s".format(dTap,
                    camD?.let { "%.2f m".format(1 / it) } ?: "-", 1 / maxOf(fitTap, 1e-3f), if (camD != null && !ok) " (disagree: fit kept)" else ""))
                Pair(dTap, if (ok) camD!! else fitTap)
            } else {
                val ringD = if (job.focusM.isInfinite()) 0f else 1f / job.focusM
                val sharp = Sharpness.peak(est.seen, dm)
                app.relens.Trace.i("ring anchor: ring %.2f m, sharpest rel %s".format(job.focusM,
                    sharp?.let { "%.2f (x%.1f over the rest)".format(it.first, it.second) } ?: "none clear"))
                // The sharpest depth is at the ring's distance, unless that is absurd against
                // Metric3D (5x: the lens wasn't where the ring says, or nothing is sharp there).
                sharp?.takeIf { (ringD / maxOf(fitA + fitB * it.first, 1e-3f)) in 0.2f..5f }?.let { Pair(it.first, ringD) }
                    ?: run { if (sharp != null) app.relens.Trace.w("sharpest depth disagrees with the ring: fit kept"); null }
            }
            // Tim's idea: every tap measured a distance at a point. With two or more at different
            // depths, they fix scale and shift directly (a weighted line through them, the
            // shutter's own focus point included); Metric3D only fills in otherwise.
            val pts = job.afSamples.map { Pair(dm.medianAround(it.fx, it.fy), it.dioptres) } +
                listOfNotNull(anchor)
            val spread = (pts.maxOfOrNull { it.first } ?: 0f) - (pts.minOfOrNull { it.first } ?: 0f)
            if (pts.size >= 2 && spread > 0.08f) {
                val mx = pts.map { it.first }.average(); val my = pts.map { it.second }.average()
                var sxy = 0.0; var sxx = 0.0
                for ((x, y) in pts) { sxy += (x - mx) * (y - my); sxx += (x - mx) * (x - mx) }
                val b = (sxy / maxOf(sxx, 1e-9)).toFloat()
                if (b > 0f) {
                    dispB = b; dispA = (my - b * mx).toFloat()
                    // keep the shutter's focus point exactly on its distance
                    anchor?.let { dispA = it.second - dispB * it.first }
                    Trace.i("depth from ${pts.size} focus samples: " + pts.joinToString { "(rel %.2f, %.2f m)".format(it.first, 1 / maxOf(it.second, 1e-3f)) })
                }
            } else if (anchor != null && anchor.first - dFar > 0.05f) {
                dispB = maxOf((anchor.second - farD) / (anchor.first - dFar), 1e-3f)
                dispA = anchor.second - dispB * anchor.first
            } else if (anchor != null) {
                dispA = anchor.second - dispB * anchor.first
            }
            app.relens.Trace.i("depth scale: rel 1 at %.2f m, rel 0 at %.2f m (fit: %.2f m, %.2f m)".format(
                1 / maxOf(dispA + dispB, 1e-3f), 1 / maxOf(dispA, 1e-3f), 1 / maxOf(fitA + fitB, 1e-3f), 1 / maxOf(fitA, 1e-3f)))
            // Nothing beyond 60 m.
            val minRel = dm.data.minOrNull() ?: 0f
            dispA = maxOf(dispA, 1f / 60f - dispB * minRel)
        } else {
            dispA = 0f; dispB = (camD ?: (1f / 1.5f)) / maxOf(dTap, 0.02f)
        }
        val subjectD = maxOf(dispA + dispB * dTap, 0.02f)
        // What lies behind the foreground, from the parallax side frames (for the blur's edges).
        eng.plateTex = 0
        ar?.takeIf { job.plate }?.plate(dm, dispA, dispB, photo.bmp)?.let { pl ->
            val t = g.texture(dm.w, dm.h, GLES30.GL_RGBA16F)
            g.upload(t, dm.w, dm.h, GLES30.GL_RGBA, GLES30.GL_FLOAT, floatBuffer(pl))
            eng.plateTex = t; lap("plate")
        }
        if (!job.publish) {
            // Bench: the calibrated depth as a picture (near white, 0.25 m ... 8 m on a log scale).
            val vis = DepthMap(dm.w, dm.h, FloatArray(dm.data.size) {
                val z = 1f / maxOf(dispA + dispB * dm.data[it], 1f / 8f)
                (1f - kotlin.math.ln(z / 0.25f) / kotlin.math.ln(32f)).coerceIn(0f, 1f)
            })
            File(context.getExternalFilesDir("prints"), "last_depth.png").outputStream().use {
                depthToBitmap(vis).compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            app.relens.Trace.i("subject at the tap %.2f m".format(1 / subjectD))
        }
        val tiltD = Movements.tiltDioptres(job.lens, job.tiltDeg)
        var focusD = job.focusM?.let { if (it.isInfinite()) 0f else 1f / it } ?: subjectD
        // On the subject: with a tilted plane, keep the tapped point (not the frame's centre) sharp.
        if (job.focusM == null) {
            val hd = kotlin.math.hypot(w / 2f, h / 2f)
            val qx = (job.focusX * w - w / 2f) / hd; val qy = (job.focusY * h - h / 2f) / hd
            focusD -= tiltD[0] * qx + tiltD[1] * qy
        }
        // The frame now matches the lens: its own focal length over the frame's 36 mm long side
        // (old uncropped shots: the phone's framing, the "equivalent" stand-in).
        val vf = if (job.crop > 0f) job.lens.focalMM / 36f else viewFocal
        val look = Look(job.lens, job.fNumber, dispA = dispA, dispB = dispB, focusD = focusD, viewFocal = vf,
            tiltD = tiltD, keystone = Movements.keystone(job.lens, job.shiftMM),
            stock = job.stock, push = job.push, samples = 256, levelBias = -1f, filter = job.filter,
            tiltDeg = job.tiltDeg, flareGain = if (rp != null) 1f else 3f,
            srcRect = srcRect, srcLinear = rp != null, exposure = exposure * Math.pow(2.0, job.exposureStops.toDouble()).toFloat(),
            pointBoost = if (rp != null) 0f else 8f)
        if (!job.publish) eng.dump = { name, tex, dw, dh, level ->
            gatherDump?.takeIf { name == "gather" }?.let { f ->
                writeHalf(g, tex, dw, dh, f)
                File(f.path + ".json").writeText("{\"width\":$dw,\"height\":$dh,\"dispA\":$dispA,\"dispB\":$dispB,\"focusD\":$focusD,\"exposure\":$exposure}")
            }
            if (name == "coc work L0") {
                // Bench: the signed blur radius along a horizontal line at the focus point and at 70 % down.
                val f = g.readFloat(tex, dw, dh, level)
                for (fy in floatArrayOf(job.focusY, 0.7f)) {
                    val y = (fy * dh).toInt().coerceIn(0, dh - 1)
                    app.relens.Trace.i("coc at y=%.2f: ".format(fy) + (0 until 20).joinToString(" ") { i ->
                        "%.0f".format(f[4 * (y * dw + i * dw / 20) + 3]) })
                }
            }
        } else eng.dump = null
        // Lights over the whole camera frame, in the print's pixels (outside it: off the frame).
        if (rp != null) {
            // Over the RAW's whole frame (true light, not clipped), into the print's pixels.
            val sx = full.bmp.width / rp.width.toFloat(); val sy = full.bmp.height / rp.height.toFloat()
            eng.extraLights = eng.findLights(rp.tex, rp.width, rp.height, srgb = false, threshold = 0.9f / exposure).map {
                LightSource((it.x * sx - crop.left) * up, (it.y * sy - crop.top) * up, it.radius * sx * up,
                    FloatArray(3) { c -> it.energy[c] * exposure * sx * sy * up * up }, it.blur, (it.pixels * sx * sy * up * up).toInt())
            }
        } else if (job.crop > 0f) {
            val ft = g.texture(full.bmp.width, full.bmp.height, GLES30.GL_RGBA8)
            upload(g, ft, full.bmp)
            eng.extraLights = eng.findLights(ft, full.bmp.width, full.bmp.height).map {
                LightSource((it.x - crop.left) * up, (it.y - crop.top) * up, it.radius * up,
                    FloatArray(3) { c -> it.energy[c] * up * up }, it.blur, (it.pixels * up * up).toInt())
            }
            g.deleteTextures(ft)
        } else eng.extraLights = null
        // The ultra-wide's view of the same moment: lights beyond even the hero camera's frame.
        val uwJpg = job.jpeg.let { File(it.parentFile, it.nameWithoutExtension + ".uw.jpg") }
        val uwJson = job.jpeg.let { File(it.parentFile, it.nameWithoutExtension + ".uw.json") }
        if (uwJpg.exists() && uwJson.exists()) runCatching {
            val meta = org.json.JSONObject(uwJson.readText())
            val raw0 = BitmapFactory.decodeFile(uwJpg.absolutePath)
            val rot = meta.optInt("orientation", 90).toFloat()
            val uw = if (rot == 0f) raw0 else Bitmap.createBitmap(raw0, 0, 0, raw0.width, raw0.height, Matrix().apply { postRotate(rot) }, true)
            val ut = g.texture(uw.width, uw.height, GLES30.GL_RGBA8); upload(g, ut, uw)
            val found = eng.findLights(ut, uw.width, uw.height)
            g.deleteTextures(ut)
            // By angle: on the 36 mm scale a direction sits at f x tan(angle), so the ultra-wide's
            // positions scale by the lens's focal length over its own.
            val scale = job.lens.focalMM / meta.getDouble("f36").toFloat()
            val uwLong = maxOf(uw.width, uw.height).toFloat(); val printLong = maxOf(w, h).toFloat()
            val px = scale * printLong / uwLong                       // print px per ultra-wide px
            val expoRatio = if (meta.has("exposure") && meta.has("heroExposure"))
                (meta.getDouble("heroExposure") / meta.getDouble("exposure")).toFloat() else 1f
            val heroLeft = -crop.left * up; val heroTop = -crop.top * up
            val heroRight = heroLeft + full.bmp.width * up; val heroBottom = heroTop + full.bmp.height * up
            val extra = found.mapNotNull { l ->
                val x = (l.x - uw.width / 2f) * px + w / 2f; val y = (l.y - uw.height / 2f) * px + h / 2f
                // Inside the hero camera's own frame: its own (better) view of the light stands.
                if (x in heroLeft..heroRight && y in heroTop..heroBottom) null
                // Too far off the picture for its flare to reach in (Flare.swift fades them out by 0.6 frames).
                else if (x / w !in -0.6f..1.6f || y / h !in -0.6f..1.6f) null
                else LightSource(x, y, l.radius * px, FloatArray(3) { l.energy[it] * px * px * expoRatio * exposure }, 0f, l.pixels)
            }
            app.relens.Trace.i("ultra-wide map: ${found.size} lights, ${extra.size} beyond the hero frame (scale %.2f, exposure x%.2f)".format(scale, expoRatio))
            eng.extraLights = (eng.extraLights ?: emptyList()) + extra
            if (uw !== raw0) raw0.recycle(); uw.recycle()
        }.onFailure { app.relens.Trace.w("ultra-wide map failed", it) }
        // The lights' flare is exposed as the picture is.
        val stopGain = Math.pow(2.0, job.exposureStops.toDouble()).toFloat()
        eng.extraLights = eng.extraLights?.map { LightSource(it.x, it.y, it.radius, FloatArray(3) { c -> it.energy[c] * stopGain }, it.blur, it.pixels) }
        val out = eng.develop(src, dispLo, guideLo, w, h, look, linear = job.saveDng); lap("lens+film")
        val linTex = eng.lastLinear
        Trace.i("exposure: %+.1f stops by the meter and shutter%s".format(job.exposureStops, if (rp != null) ", RAW matched x%.2f".format(exposure) else ""))
        app.relens.Trace.i("lights: " + eng.lights.joinToString { "(%.0f,%.0f r%.0f e%.0f blur%.0f)".format(it.x, it.y, it.radius, it.luma, it.blur) })
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        g.readRgba8(out, w, h, buf); buf.position(0)
        if (eng.plateTex != 0) { g.deleteTextures(eng.plateTex); eng.plateTex = 0 }
        g.deleteTextures(src, dispLo, guideLo, out)
        val print = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.copyPixelsFromBuffer(buf) }
        lap("readback")

        val name = "VINTAGESNAP_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + "_%02d".format(job.frame)
        // The relensed picture as a linear DNG (no film): streamed off the GPU in strips.
        if (linTex != 0) {
            runCatching {
                val dng = File(context.getExternalFilesDir("prints"), "$name.dng")
                DngWriter.write(dng, w, h, "Vintage Snap", job.lens.name, "Vintage Snap (${job.stock.name} not applied)") { y0, n, into ->
                    g.readFloatRows(linTex, w, y0, n, into)
                }
                if (job.publish) publish(dng, name, "dng", "image/x-adobe-dng")
                Trace.i("DNG: ${dng.length() / 1_000_000} MB")
            }.onFailure { Trace.w("DNG not saved", it) }
            g.deleteTextures(linTex)
            lap("dng")
        }
        val file = File(context.getExternalFilesDir("prints"), "$name.jpg")
        file.outputStream().use { print.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        writeExif(file, job, look)
        val uri = if (!job.publish) null else runCatching { publish(file, name) }.onFailure { app.relens.Trace.w("gallery save failed", it) }.getOrNull()
        lap("save")
        val ps = 1440f / maxOf(w, h)
        val preview = Bitmap.createScaledBitmap(print, (w * ps).toInt(), (h * ps).toInt(), true)
        val original = Bitmap.createScaledBitmap(photo.bmp, (w * ps).toInt(), (h * ps).toInt(), true)
        print.recycle(); photo.bmp.recycle(); if (full.bmp !== photo.bmp) full.bmp.recycle()
        val timings = t.entries.joinToString("  ") { "${it.key} ${it.value} ms" }
        app.relens.Trace.i("developed $name: $timings (subject %.2f m, focus %.2f m)".format(1 / subjectD, look.focusM))
        Print(uri, file, preview, original, timings, arFocusD)
    }

    private class Upright(val bmp: Bitmap)


    private fun decodeUpright(f: File): Upright {
        val bmp = BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
        val deg = when (ExifInterface(f).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (deg == 0f) return Upright(bmp)
        val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true)
        bmp.recycle()
        return Upright(r)
    }

    /** The centred 3:2 crop of an upright frame for a lens [crop] times narrower than the camera (0: the whole frame). */
    private fun cropRect(w: Int, h: Int, crop: Float): android.graphics.Rect {
        if (crop <= 0f) return android.graphics.Rect(0, 0, w, h)
        val long = (maxOf(w, h) / crop).toInt(); val short = long * 2 / 3
        val cw = if (w >= h) long else short; val ch = if (w >= h) short else long
        return android.graphics.Rect((w - cw) / 2, (h - ch) / 2, (w - cw) / 2 + cw, (h - ch) / 2 + ch)
    }

    /** The RAW's exposure that gives the crop the JPEG's mean brightness (linear), from small mips of both. */
    private fun matchExposure(g: Gl, rp: RawPicture, jpegCrop: Bitmap, rect: FloatArray): Float {
        val lvl = rp.levels - 1
        val lw = maxOf(1, rp.width shr lvl); val lh = maxOf(1, rp.height shr lvl)
        val f = g.readFloat(rp.tex, lw, lh, lvl)
        var rawSum = 0.0; var n = 0
        val x0 = (rect[0] * lw).toInt(); val y0 = (rect[1] * lh).toInt()
        val x1 = ((rect[0] + rect[2]) * lw).toInt().coerceAtMost(lw); val y1 = ((rect[1] + rect[3]) * lh).toInt().coerceAtMost(lh)
        for (y in y0 until y1) for (x in x0 until x1) {
            val i = 4 * (y * lw + x); rawSum += 0.2126 * f[i] + 0.7152 * f[i + 1] + 0.0722 * f[i + 2]; n++
        }
        val small = Bitmap.createScaledBitmap(jpegCrop, 64, maxOf(1, 64 * jpegCrop.height / jpegCrop.width), true)
        val px = IntArray(small.width * small.height).also { small.getPixels(it, 0, small.width, 0, 0, small.width, small.height) }
        fun lin(c: Int): Double { val v = c / 255.0; return if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4) }
        var jSum = 0.0
        for (c in px) jSum += 0.2126 * lin((c shr 16) and 0xff) + 0.7152 * lin((c shr 8) and 0xff) + 0.0722 * lin(c and 0xff)
        val raw = rawSum / maxOf(n, 1); val jpg = jSum / px.size
        val k = (jpg / maxOf(raw, 1e-5)).toFloat().coerceIn(0.05f, 50f)
        app.relens.Trace.i("exposure match: jpeg mean %.4f, raw mean %.4f -> x%.2f".format(jpg, raw, k))
        return k
    }

    private fun upload(g: Gl, tex: Int, b: Bitmap) {
        val buf = ByteBuffer.allocateDirect(b.width * b.height * 4).order(ByteOrder.nativeOrder())
        b.copyPixelsToBuffer(buf); buf.position(0)
        g.upload(tex, b.width, b.height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
    }

    private fun writeExif(f: File, job: DevelopJob, look: Look) {
        // EXIF text is ASCII: the "·" in a lens name becomes "-".
        fun ascii(t: String) = t.replace('·', '-').replace('°', ' ').filter { it.code in 32..126 }
        runCatching {
            val e = ExifInterface(f)
            e.setAttribute(ExifInterface.TAG_MAKE, "Vintage Snap")
            e.setAttribute(ExifInterface.TAG_LENS_MAKE, job.lens.name.substringBefore(' '))
            e.setAttribute(ExifInterface.TAG_LENS_MODEL, ascii(job.lens.name))
            e.setAttribute(ExifInterface.TAG_F_NUMBER, "%.1f".format(job.fNumber))
            e.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "${job.lens.focalMM.toInt()}/1")
            e.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, ascii("${job.lens.name}%s at f/%.1f, focused %.2f m, on %s%s".format(
                if (job.filter.isNone) "" else " + " + job.filter.name,
                job.fNumber, look.focusM, job.stock.name, if (job.push != 0) " (push %+d)".format(job.push) else "")))
            e.saveAttributes()
        }
    }

    /** Copies the print into the gallery (Pictures/Relens). */
    private fun publish(f: File, name: String, ext: String = "jpg", mime: String = "image/jpeg"): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.$ext")
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Vintage Snap")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val cr = context.contentResolver
        val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        cr.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
        values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
        cr.update(uri, values, null, null)
        return uri
    }
}
