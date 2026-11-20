package app.relens.spike

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.opengl.GLES30
import android.os.Build
import android.util.Log
import app.relens.BuildConfig
import app.relens.engine.DepthMap
import app.relens.engine.Gl
import app.relens.engine.depthToBitmap
import app.relens.engine.LensEngine
import app.relens.engine.Look
import app.relens.engine.floatBuffer
import app.relens.engine.readBack
import app.relens.film.FilmStock
import app.relens.film.filmStocks
import app.relens.optics.Lens
import app.relens.optics.lensBag
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * The performance spike: depth network backends, then the GPU lens + film at
 * preview and export sizes, then readback and JPEG encode. Everything goes to
 * logcat (tag RelensBench) and to files/bench.txt; pictures to files/out/.
 *
 * Inputs (adb push to /sdcard/Android/data/app.relens.spike/files/):
 *   input.jpg       a test photo (else a synthetic 4000 x 3000 scene is drawn)
 *   models/         .onnx (ONNX Runtime) and .tflite (LiteRT) models
 */
/** The spike's four lenses, from the catalogue. */
val spikeLenses: List<Lens> by lazy {
    listOf("Ohi Kor 50mm f/1.4", "Ohi Kor 85mm f/1.4", "Krasnogorsk Solnce 58mm f/2", "Ohi Reflex-Kor 500mm f/8")
        .map { n -> lensBag.first { it.name == n } }
}

class Bench(private val ctx: Context, private val ui: (String) -> Unit) {
    private val dir = ctx.getExternalFilesDir(null)!!
    private val outDir = File(dir, "out").also { it.mkdirs() }
    private val report = StringBuilder()

    private fun log(s: String) {
        Log.i("RelensBench", s)
        report.appendLine(s)
        ui(s)
    }

    fun run(flags: Set<String>) {
        // The app owns files/models (a folder adb creates is not listable by the app).
        File(dir, "models").mkdirs()
        if ("setup" in flags) { log("SETUP DONE"); log("BENCH DONE"); return }
        if ("develop" in flags) { developOnce(flags); File(dir, "bench.txt").writeText(report.toString()); log("BENCH DONE"); return }
        val t0 = System.nanoTime()
        try {
            body(flags)
        } catch (t: Throwable) {
            log("BENCH FAILED: ${Log.getStackTraceString(t)}")
        }
        log("total bench time %.1f s".format((System.nanoTime() - t0) / 1e9))
        File(dir, "bench.txt").writeText(report.toString())
        log("BENCH DONE")
    }

    private fun body(flags: Set<String>) {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        log("device ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        if (Build.VERSION.SDK_INT >= 31) log("soc ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
        CameraProbe.report(ctx).forEach(::log)
        log("ram total %.1f GB, avail %.1f GB, java heap max %d MB".format(
            mi.totalMem / 1e9, mi.availMem / 1e9, Runtime.getRuntime().maxMemory() / 1048576))

        // ---- the picture ----
        val inFile = File(dir, "input.jpg")
        val photo: Bitmap
        if (inFile.exists()) {
            val t = System.nanoTime()
            photo = BitmapFactory.decodeFile(inFile.absolutePath, BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888 })
            log("decode input.jpg ${photo.width}x${photo.height}: %.0f ms".format((System.nanoTime() - t) / 1e6))
        } else {
            photo = synthetic(4000, 3000)
            log("no input.jpg: synthetic 4000x3000 scene")
        }

        // ---- depth ----
        val models = if ("nodepth" in flags) emptyList() else File(dir, "models").listFiles()?.sortedBy { it.name } ?: emptyList()
        val runs = 5
        val results = ArrayList<DepthResult>()
        // Depth Anything wants sides that are multiples of 14; 518 on the long side.
        val longSide = 518
        val (dw, dh) = if (photo.width >= photo.height)
            longSide to ((longSide * photo.height / photo.width.toFloat() / 14).toInt() * 14)
        else ((longSide * photo.width / photo.height.toFloat() / 14).toInt() * 14) to longSide
        val small = Bitmap.createScaledBitmap(photo, dw, dh, true)
        log("depth input ${dw}x$dh")
        val eps = buildList {
            add("xnnpack"); add("cpu"); add("nnapi")
            if (BuildConfig.QNN) add("qnn")
        }
        val fast = "fast" in flags   // only the winner: ORT fp32 on the CPU
        for (m in models.filter { it.name.endsWith(".onnx") && !it.name.startsWith("._") && (!fast || it.name.startsWith("da2s_fp32")) }) {
            for (ep in if (fast) listOf("cpu") else eps) {
                if (ep == "cpu" && "slow" !in flags && m.name.contains("fp16")) continue
                // Qualcomm's quantized graph is meant for the NPU; on the CPU it only says how slow that is.
                if (m.name.contains("qai") && ep == "nnapi") continue
                val r = runOrt(m, ep, photo, runs, ::log)
                log(r.line()); results.add(r)
            }
        }
        for (m in models.filter { it.name.endsWith(".tflite") && !it.name.startsWith("._") && !fast }) {
            for (gpu in listOf(true, false)) {
                val r = runLiteRt(m, gpu, photo, runs, ::log)
                log(r.line()); results.add(r)
            }
        }
        val ok = results.filter { it.map != null }
        val best = ok.minByOrNull { it.medianMs }
        for (r in ok) {
            val name = r.label.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_')
            save(depthToBitmap(r.map!!), File(outDir, "depth_$name.png"))
        }
        if (best != null) log("FASTEST DEPTH: ${best.label} %.0f ms".format(best.medianMs))
        val depth = best?.map ?: syntheticDepth(dw, dh).also { log("no depth model ran: synthetic depth") }

        // ---- GPU lens + film ----
        Gl(ctx.assets).use { gl -> gpu(gl, photo, small, depth, flags, best) }
    }

    private fun gpu(gl: Gl, photo: Bitmap, small: Bitmap, depth: DepthMap, flags: Set<String>, best: DepthResult?) {
        log("gl ${gl.vendor} | ${gl.renderer} | ${gl.version} | max texture ${gl.maxTextureSize}")
        log("EXT_color_buffer_float ${gl.hasExtension("GL_EXT_color_buffer_float")}, OES_texture_float_linear ${gl.hasExtension("GL_OES_texture_float_linear")}")

        val tUp = System.nanoTime()
        val src = gl.texture(photo.width, photo.height, GLES30.GL_RGBA8)
        val pbuf = ByteBuffer.allocateDirect(photo.width * photo.height * 4).order(ByteOrder.nativeOrder())
        photo.copyPixelsToBuffer(pbuf); pbuf.position(0)
        gl.upload(src, photo.width, photo.height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pbuf)
        gl.finish()
        log("upload ${photo.width}x${photo.height} RGBA8: %.0f ms".format((System.nanoTime() - tUp) / 1e6))
        val dispLo = gl.texture(depth.w, depth.h, GLES30.GL_R32F, linear = false)
        gl.upload(dispLo, depth.w, depth.h, GLES30.GL_RED, GLES30.GL_FLOAT, floatBuffer(depth.data))
        val guide = Bitmap.createScaledBitmap(small, depth.w, depth.h, true)
        val gbuf = ByteBuffer.allocateDirect(depth.w * depth.h * 4).order(ByteOrder.nativeOrder())
        guide.copyPixelsToBuffer(gbuf); gbuf.position(0)
        val guideLo = gl.texture(depth.w, depth.h, GLES30.GL_RGBA8)
        gl.upload(guideLo, depth.w, depth.h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, gbuf)

        // Focus point: flag focus=x:y (fractions of the frame), else the middle.
        val fp = flags.firstOrNull { it.startsWith("focus=") }?.removePrefix("focus=")?.split(":")?.map { it.toFloat() } ?: listOf(0.5f, 0.5f)
        val focusDisp = run {
            val v = ArrayList<Float>()
            val cx = (fp[0] * depth.w).toInt(); val cy = (fp[1] * depth.h).toInt()
            for (y in maxOf(0, cy - depth.h / 20) until minOf(depth.h, cy + depth.h / 20))
                for (x in maxOf(0, cx - depth.w / 20) until minOf(depth.w, cx + depth.w / 20)) v.add(depth.data[y * depth.w + x])
            v.sort(); v[v.size / 2]
        }
        log("focus at ${fp[0]},${fp[1]}: disparity %.3f".format(focusDisp))
        val engine = LensEngine(gl)
        val lens = spikeLenses[1]                      // 85 mm f/1.4: the biggest blur, the worst case
        val stock = filmStocks.first { it.name == "Rochester Portrait 400" }
        fun num(k: String, d: Float) = flags.firstOrNull { it.startsWith("$k=") }?.removePrefix("$k=")?.toFloat() ?: d
        val nearM = num("near", 0.8f); val farM = num("far", 50f)
        log("scene near %.2f m, far %.1f m".format(nearM, farM))
        fun look(n: Int, s: FilmStock = stock, l: Lens = lens) =
            Look.nearFar(l, l.maxAperture, nearM, farM, focusDisp, s, n)

        if ("debug" in flags) {
            engine.dump = { name, tex, w, h, level ->
                val f = gl.readFloat(tex, w, h, level)
                val st = (0 until 4).map { c ->
                    var mn = Float.MAX_VALUE; var mx = -Float.MAX_VALUE; var sum = 0.0; var nan = 0
                    for (i in c until f.size step 4) { val v = f[i]; if (v.isNaN()) { nan++; continue }; mn = minOf(mn, v); mx = maxOf(mx, v); sum += v }
                    "%.3f/%.3f/%.3f%s".format(mn, sum / (w * h), mx, if (nan > 0) " nan$nan" else "")
                }
                // Mean of each quadrant's red, to spot partial writes.
                fun q(x0: Int, y0: Int): String { var s = 0.0; var n = 0
                    for (y in y0 until y0 + h / 2 step 4) for (x in x0 until x0 + w / 2 step 4) { s += f[4 * (y * w + x)]; n++ }
                    return "%.3f".format(s / n) }
                log("DUMP $name ${w}x$h L$level: r ${st[0]} g ${st[1]} b ${st[2]} a ${st[3]} | quadrants r ${q(0, 0)} ${q(w / 2, 0)} ${q(0, h / 2)} ${q(w / 2, h / 2)}")
            }
            for (mode in listOf(1, 2)) {
                log("-- mip mode $mode --")
                engine.mipMode = mode
                gl.deleteTextures(engine.develop(src, dispLo, guideLo, 720, 540, look(48)))
            }
            engine.mipMode = 2
            engine.dump = null
            return
        }
        val aspect = photo.height / photo.width.toFloat()
        // Preview: long side 1440. Full: the photo's own size. 2x: the 50 MP case (last: it may lose the context).
        val pw0 = photo.width; val ph0 = photo.height
        val ps = 1440f / maxOf(pw0, ph0)
        val sizes = buildList {
            add("preview" to ((pw0 * ps).toInt() to (ph0 * ps).toInt()))
            add("full" to (pw0 to ph0))
            add("2x" to (pw0 * 2 to ph0 * 2))
        }
        // A few stocks and lenses at preview size, to look at (before the 2x test, which may lose the context).
        val (pw, ph) = sizes[0].second
        for ((i, s) in listOf("Backlot 800T", "Rochester Tri-Pan 400", "Rochester Aero-Colour IR 400", "Ashigara Velvet 50").withIndex()) {
            val l = spikeLenses[i % spikeLenses.size]
            val out = engine.develop(src, dispLo, guideLo, pw, ph, look(96, filmStocks.first { it.name == s }, l))
            val bmp = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(readBack(gl, out, pw, ph))
            save(bmp, File(outDir, "look_${i}_${s.replace(Regex("[^A-Za-z0-9]+"), "_")}.jpg"))
            gl.deleteTextures(out)
        }
        val summary = LinkedHashMap<String, Double>()
        for ((label, wh) in sizes) {
            val (w, h) = wh
            for (n in listOf(48, 96, 160)) {
                try {
                    // warm-up, then the median of 3 runs per pass
                    gl.deleteTextures(engine.develop(src, dispLo, guideLo, w, h, look(n)))
                    val per = LinkedHashMap<String, MutableList<Double>>()
                    var lastOut = 0
                    repeat(3) { i ->
                        val out = engine.develop(src, dispLo, guideLo, w, h, look(n))
                        for ((k, v) in engine.timings) per.getOrPut(k) { ArrayList() }.add(v)
                        if (i == 2) lastOut = out else gl.deleteTextures(out)
                    }
                    val med = per.mapValues { (_, v) -> v.sorted()[1] }
                    val total = med.values.sum()
                    log("GPU $label ${w}x$h N=$n: total %.0f ms | %s".format(total,
                        med.entries.joinToString(", ") { "%s %.1f".format(it.key, it.value) }))
                    summary["$label N=$n"] = total
                    if (n == 96 && label != "2x") {
                        val tr = System.nanoTime()
                        val buf = readBack(gl, lastOut, w, h)
                        val readMs = (System.nanoTime() - tr) / 1e6
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.copyPixelsFromBuffer(buf)
                        val te = System.nanoTime()
                        save(bmp, File(outDir, "relens_${label}.jpg"))
                        val encMs = (System.nanoTime() - te) / 1e6
                        log("  readback %.0f ms, JPEG q95 encode+write %.0f ms".format(readMs, encMs))
                        summary["$label readback"] = readMs
                        summary["$label encode"] = encMs
                    }
                    gl.deleteTextures(lastOut)
                } catch (t: Throwable) {
                    log("GPU $label N=$n FAILED: ${t.message}")
                    break
                }
            }
        }

        gl.deleteTextures(src, dispLo, guideLo)

        // ---- the estimate ----
        val depthMs = best?.medianMs ?: Double.NaN
        val exp12 = (summary["full N=96"] ?: Double.NaN) + (summary["full readback"] ?: 0.0) + (summary["full encode"] ?: 0.0)
        log("ESTIMATE preview re-render (lens+film only, N=48): %.0f ms".format(summary["preview N=48"] ?: Double.NaN))
        log("ESTIMATE full-res export: depth %.0f + GPU/readback/encode %.0f = %.1f s".format(depthMs, exp12, (depthMs + exp12) / 1000))
        log("ESTIMATE 2x export GPU (N=96): %.1f s".format((summary["2x N=96"] ?: Double.NaN) / 1000))
    }

    /**
     * The app's own developer on files/input.jpg, settings from flags:
     *   lens=<name part> f=<f-number> fx=, fy= (focus point) subject=<m> focusm=<m>
     *   stock=<name part> tilt=x:y shift=x:y
     * The print lands in files/out/ (not the gallery).
     */
    private fun developOnce(flags: Set<String>) {
        fun str(k: String) = flags.firstOrNull { it.startsWith("$k=") }?.removePrefix("$k=")?.replace('_', ' ')
        fun num(k: String) = str(k)?.toFloatOrNull()
        fun pair(k: String) = str(k)?.split(":")?.map { it.toFloat() }?.toFloatArray() ?: floatArrayOf(0f, 0f)
        val lens = lensBag.first { it.name.contains(str("lens") ?: "Ohi Kor 50mm f/1.4", ignoreCase = true) }
        val stock = filmStocks.first { it.name.contains(str("stock") ?: "Portrait 400", ignoreCase = true) }
        val job = app.relens.engine.DevelopJob(
            jpeg = File(dir, "input.jpg"), dng = null,
            focusX = num("fx") ?: 0.5f, focusY = num("fy") ?: 0.5f,
            cameraFocusD = num("subject")?.let { 1f / it },
            lens = lens, fNumber = num("f") ?: lens.maxAperture, focusM = num("focusm"),
            stock = stock, push = 0, frame = 1, tiltDeg = pair("tilt"), shiftMM = pair("shift"), publish = false)
        log("develop ${lens.name} f/${job.fNumber} on ${stock.name}, focus ${job.focusX},${job.focusY}")
        val dev = app.relens.engine.Developer(ctx)
        val print = kotlinx.coroutines.runBlocking { dev.develop(job) }
        print.file.copyTo(File(outDir, "develop_${str("tag") ?: "print"}.jpg"), overwrite = true)
        log("developed: ${print.timings}")
    }

    private fun save(b: Bitmap, f: File) {
        f.outputStream().use {
            b.compress(if (f.name.endsWith(".png")) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 95, it)
        }
    }

    /** A scene with depth cues and point lights for bokeh, when no photo is given. */
    private fun synthetic(w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), Color.rgb(40, 60, 110), Color.rgb(200, 150, 110), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        val rnd = Random(7)
        repeat(220) {
            p.color = Color.rgb(255, 200 + rnd.nextInt(55), 120 + rnd.nextInt(100))
            c.drawCircle(rnd.nextFloat() * w, rnd.nextFloat() * h * 0.55f, 4f + rnd.nextFloat() * 6f, p)
        }
        p.shader = RadialGradient(w / 2f, h * 0.55f, h * 0.22f, Color.rgb(230, 180, 150), Color.rgb(120, 70, 50), Shader.TileMode.CLAMP)
        c.drawCircle(w / 2f, h * 0.55f, h * 0.22f, p)
        p.shader = null
        p.color = Color.rgb(60, 110, 50)
        c.drawRect(0f, h * 0.85f, w.toFloat(), h.toFloat(), p)
        return b
    }

    private fun syntheticDepth(w: Int, h: Int) = DepthMap(w, h, FloatArray(w * h) { i ->
        val x = i % w; val y = i / w
        val dx = x - w / 2f; val dy = y - h * 0.55f
        if (dx * dx + dy * dy < (h * 0.22f) * (h * 0.22f)) 0.7f else y / h.toFloat() * 0.5f
    })
}
