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

        val photo = decodeUpright(job.jpeg); lap("decode")
        val est = (depth ?: DepthEstimator(modelFile).also { depth = it }).estimate(photo.bmp); lap("depth")
        val g = gl ?: Gl(context.assets).also { gl = it; engine = LensEngine(it) }
        val eng = engine!!
        val w = photo.bmp.width; val h = photo.bmp.height

        val src = g.texture(w, h, GLES30.GL_RGBA8)
        upload(g, src, photo.bmp)
        val dm = est.depth
        val dispLo = g.texture(dm.w, dm.h, GLES30.GL_R32F, linear = false)
        g.upload(dispLo, dm.w, dm.h, GLES30.GL_RED, GLES30.GL_FLOAT, floatBuffer(dm.data))
        val guide = Bitmap.createScaledBitmap(est.seen, dm.w, dm.h, true)
        val guideLo = g.texture(dm.w, dm.h, GLES30.GL_RGBA8)
        upload(g, guideLo, guide)

        // Scene scale: the network's disparity is relative. Anchor it on the camera's
        // own focus distance at the tapped point (disparity ~ 1 / distance, far ~ 0).
        val dTap = maxOf(dm.medianAround(job.focusX, job.focusY), 0.02f)
        val subjectD = job.cameraFocusD?.takeIf { it > 0.05f } ?: (1f / 1.5f)
        val dispB = subjectD / dTap
        val tiltD = Movements.tiltDioptres(job.lens, job.tiltDeg)
        var focusD = job.focusM?.let { if (it.isInfinite()) 0f else 1f / it } ?: subjectD
        // On the subject: with a tilted plane, keep the tapped point (not the frame's centre) sharp.
        if (job.focusM == null) {
            val hd = kotlin.math.hypot(w / 2f, h / 2f)
            val qx = (job.focusX * w - w / 2f) / hd; val qy = (job.focusY * h - h / 2f) / hd
            focusD -= tiltD[0] * qx + tiltD[1] * qy
        }
        val look = Look(job.lens, job.fNumber, dispA = 0f, dispB = dispB, focusD = focusD,
            tiltD = tiltD, keystone = Movements.keystone(job.lens, job.shiftMM),
            stock = job.stock, push = job.push, samples = 256, levelBias = -1f,
            pointBoost = if (job.dng != null) 0f else 8f)
        val out = eng.develop(src, dispLo, guideLo, w, h, look); lap("lens+film")
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
        print.recycle(); photo.bmp.recycle()
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
            e.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, ascii("${job.lens.name} at f/%.1f, focused %.2f m, on %s%s".format(
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
