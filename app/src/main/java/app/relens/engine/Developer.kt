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
)

class Print(
    val uri: Uri?,
    val file: File,
    val preview: Bitmap,
    /** The camera's picture at the same size, for before / after. */
    val original: Bitmap,
    val timings: String,
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

    suspend fun develop(job: DevelopJob): Print = withContext(dispatcher) {
        val t = LinkedHashMap<String, Long>()
        var t0 = System.nanoTime()
        fun lap(name: String) { val n = System.nanoTime(); t[name] = (n - t0) / 1_000_000; t0 = n }

        val full = decodeUpright(job.jpeg); lap("decode")
        // The lens's field of view on a 3:2 frame; the rest of the camera's frame is kept
        // to find lights just outside the picture (their flare still reaches the lens).
        val crop = cropRect(full.bmp.width, full.bmp.height, job.crop)
        val photo = Upright(if (job.crop > 0f) Bitmap.createBitmap(full.bmp, crop.left, crop.top, crop.width(), crop.height()) else full.bmp)
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
            val rp = runCatching { RawDeveloper.develop(g, raw16, rawMeta) }.onFailure { Log.w("Relens", "raw develop failed", it) }.getOrNull()
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
        val dispLo = g.texture(dm.w, dm.h, GLES30.GL_R32F, linear = false)
        g.upload(dispLo, dm.w, dm.h, GLES30.GL_RED, GLES30.GL_FLOAT, floatBuffer(dm.data))
        val guide = Bitmap.createScaledBitmap(est.seen, dm.w, dm.h, true)
        val guideLo = g.texture(dm.w, dm.h, GLES30.GL_RGBA8)
        upload(g, guideLo, guide)

        // Scene scale: Depth Anything's disparity is relative (unknown scale and shift).
        // Fit it to Metric3D's metres over the frame, then let the camera's own focus
        // distance at the tapped point set the scale (it measured that one).
        val dTap = dm.medianAround(job.focusX, job.focusY)
        val camD = job.cameraFocusD?.takeIf { it > 0.05f }
        var dispA: Float; var dispB: Float
        if (metricFile.exists()) {
            val md = (metric ?: MetricDepth(metricFile).also { metric = it }).estimate(photo.bmp, focalOverLong); lap("metric")
            val ab = MetricDepth.fit(dm, md)
            dispB = maxOf(ab[0], 1e-3f); dispA = ab[1]
            val fitTap = dispA + dispB * dTap
            if (camD != null) {
                // The camera measured the subject: pass through it exactly; the fit gives the slope.
                // Unless they disagree wildly (an AF distance off by more than 3x is not to be trusted).
                val ratio = camD / maxOf(fitTap, 1e-3f)
                Log.i("Relens", "AF %.2f m, fit %.2f m at the tap (x%.2f)".format(1 / camD, 1 / maxOf(fitTap, 1e-3f), ratio))
                if (ratio in (1f / 3f)..3f) dispA = camD - dispB * dTap
                else Log.w("Relens", "AF and depth disagree: keeping the depth fit")
            }
            // Nothing beyond 60 m (a fit's shift can push the far end past infinity).
            val minRel = dm.data.minOrNull() ?: 0f
            dispA = maxOf(dispA, 1f / 60f - dispB * minRel)
        } else {
            // No metric model: disparity ~ 1 / distance with the far end at infinity.
            dispA = 0f; dispB = (camD ?: (1f / 1.5f)) / maxOf(dTap, 0.02f)
        }
        val subjectD = maxOf(dispA + dispB * dTap, 0.02f)
        if (!job.publish) {
            // Bench: the calibrated depth as a picture (near white, 0.25 m ... 8 m on a log scale).
            val vis = DepthMap(dm.w, dm.h, FloatArray(dm.data.size) {
                val z = 1f / maxOf(dispA + dispB * dm.data[it], 1f / 8f)
                (1f - kotlin.math.ln(z / 0.25f) / kotlin.math.ln(32f)).coerceIn(0f, 1f)
            })
            File(context.getExternalFilesDir("prints"), "last_depth.png").outputStream().use {
                depthToBitmap(vis).compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            Log.i("Relens", "subject at the tap %.2f m".format(1 / subjectD))
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
            srcRect = srcRect, srcLinear = rp != null, exposure = exposure,
            pointBoost = if (rp != null) 0f else 8f)
        if (!job.publish) eng.dump = { name, tex, dw, dh, level ->
            if (name == "coc work L0") {
                // Bench: the signed blur radius along a horizontal line at the focus point and at 70 % down.
                val f = g.readFloat(tex, dw, dh, level)
                for (fy in floatArrayOf(job.focusY, 0.7f)) {
                    val y = (fy * dh).toInt().coerceIn(0, dh - 1)
                    Log.i("Relens", "coc at y=%.2f: ".format(fy) + (0 until 20).joinToString(" ") { i ->
                        "%.0f".format(f[4 * (y * dw + i * dw / 20) + 3]) })
                }
            }
        } else eng.dump = null
        // Lights over the whole camera frame, in the print's pixels (outside it: off the frame).
        if (rp != null) {
            // Over the RAW's whole frame (true light, not clipped), into the print's pixels.
            val sx = full.bmp.width / rp.width.toFloat(); val sy = full.bmp.height / rp.height.toFloat()
            eng.extraLights = eng.findLights(rp.tex, rp.width, rp.height, srgb = false, threshold = 0.9f / exposure).map {
                LightSource(it.x * sx - crop.left, it.y * sy - crop.top, it.radius * sx,
                    FloatArray(3) { c -> it.energy[c] * exposure * sx * sy }, it.blur, it.pixels)
            }
        } else if (job.crop > 0f) {
            val ft = g.texture(full.bmp.width, full.bmp.height, GLES30.GL_RGBA8)
            upload(g, ft, full.bmp)
            eng.extraLights = eng.findLights(ft, full.bmp.width, full.bmp.height).map {
                LightSource(it.x - crop.left, it.y - crop.top, it.radius, it.energy, it.blur, it.pixels)
            }
            g.deleteTextures(ft)
        } else eng.extraLights = null
        val out = eng.develop(src, dispLo, guideLo, w, h, look); lap("lens+film")
        if (rp != null) Log.i("Relens", "developed from RAW, exposure x%.2f".format(exposure))
        Log.i("Relens", "lights: " + eng.lights.joinToString { "(%.0f,%.0f r%.0f e%.0f blur%.0f)".format(it.x, it.y, it.radius, it.luma, it.blur) })
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        g.readRgba8(out, w, h, buf); buf.position(0)
        g.deleteTextures(src, dispLo, guideLo, out)
        val print = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.copyPixelsFromBuffer(buf) }
        lap("readback")

        val name = "RELENS_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + "_%02d".format(job.frame)
        val file = File(context.getExternalFilesDir("prints"), "$name.jpg")
        file.outputStream().use { print.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        writeExif(file, job, look)
        val uri = if (!job.publish) null else runCatching { publish(file, name) }.onFailure { Log.w("Relens", "gallery save failed", it) }.getOrNull()
        lap("save")
        val ps = 1440f / maxOf(w, h)
        val preview = Bitmap.createScaledBitmap(print, (w * ps).toInt(), (h * ps).toInt(), true)
        val original = Bitmap.createScaledBitmap(photo.bmp, (w * ps).toInt(), (h * ps).toInt(), true)
        print.recycle(); photo.bmp.recycle(); if (full.bmp !== photo.bmp) full.bmp.recycle()
        val timings = t.entries.joinToString("  ") { "${it.key} ${it.value} ms" }
        Log.i("Relens", "developed $name: $timings (subject %.2f m, focus %.2f m)".format(1 / subjectD, look.focusM))
        Print(uri, file, preview, original, timings)
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
        Log.i("Relens", "exposure match: jpeg mean %.4f, raw mean %.4f -> x%.2f".format(jpg, raw, k))
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
            e.setAttribute(ExifInterface.TAG_MAKE, "Relens")
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
    private fun publish(f: File, name: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Relens")
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
