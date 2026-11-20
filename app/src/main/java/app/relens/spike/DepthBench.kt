package app.relens.spike

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import android.graphics.Bitmap
import app.relens.engine.DepthMap
import app.relens.engine.normalise
import app.relens.engine.toTensor
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.channels.FileChannel
import java.util.EnumSet

/** One way of running the network, timed. */
class DepthResult(
    val label: String,
    val loadMs: Double,
    val preMs: Double,
    val inferMs: List<Double>,
    val postMs: Double,
    val map: DepthMap?,
    val error: String? = null,
) {
    val medianMs: Double get() = inferMs.sorted().let { if (it.isEmpty()) Double.NaN else it[it.size / 2] }
    fun line(): String = if (error != null) "$label: FAILED ($error)" else
        "%s: load %.0f ms, pre %.0f ms, infer median %.0f ms (runs %s), post %.0f ms".format(
            label, loadMs, preMs, medianMs, inferMs.joinToString("/") { "%.0f".format(it) }, postMs)
}

private inline fun ms(block: () -> Unit): Double {
    val t = System.nanoTime(); block(); return (System.nanoTime() - t) / 1e6
}

/** ONNX Runtime with one execution provider. [ep] = "cpu", "xnnpack", "nnapi", "qnn". */
fun runOrt(model: File, ep: String, photo: Bitmap, runs: Int, log: (String) -> Unit): DepthResult {
    val label = "ORT ${model.name} [$ep]"
    return try {
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions()
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        // NNAPI / QNN need static shapes: scripts/fix_shapes.py writes *_HxW.onnx copies.
        when (ep) {
            "cpu" -> opts.setIntraOpNumThreads(4)
            "xnnpack" -> { opts.setIntraOpNumThreads(1); opts.addXnnpack(mapOf("intra_op_num_threads" to "4")) }
            "nnapi" -> opts.addNnapi(EnumSet.of(NNAPIFlags.USE_FP16))
            "qnn" -> {
                // The EP itself is only in the onnxruntime-android-qnn build (-Pqnn=true).
                opts.addQnn(mapOf("backend_path" to "libQnnHtp.so", "htp_performance_mode" to "burst",
                    "enable_htp_fp16_precision" to "1"))
            }
        }
        val session: OrtSession
        val loadMs = ms { } + run {
            val t = System.nanoTime(); val s = env.createSession(model.absolutePath, opts)
            session = s; (System.nanoTime() - t) / 1e6
        }
        val inName = session.inputNames.first()
        val info = session.inputInfo[inName]?.info as ai.onnxruntime.TensorInfo
        log("  $label input $inName $info")
        // Static shape from the model file (scripts/fix_shapes.py, float_io.py): [1, 3, H, W].
        val ih = info.shape[2].toInt(); val iw = info.shape[3].toInt()
        val bmp = Bitmap.createScaledBitmap(photo, iw, ih, true)
        // onnx-community exports want ImageNet-normalised input; Qualcomm's take RGB 0...1.
        val imagenet = !model.name.contains("qai")
        lateinit var tensor: OnnxTensor
        val preMs = ms {
            val fb = toTensor(bmp, nchw = true, imagenet = imagenet)
            tensor = OnnxTensor.createTensor(env, fb, longArrayOf(1, 3, ih.toLong(), iw.toLong()))
        }
        val times = ArrayList<Double>()
        var raw: FloatArray? = null
        var oh = 0; var ow = 0
        repeat(runs + 1) { i ->
            val t = System.nanoTime()
            session.run(mapOf(inName to tensor)).use { r ->
                val dt = (System.nanoTime() - t) / 1e6
                if (i > 0) times.add(dt) else log("  $label first run %.0f ms".format(dt))
                if (raw == null) {
                    val o = r[0] as OnnxTensor
                    val shape = o.info.shape
                    oh = shape[shape.size - 2].toInt(); ow = shape[shape.size - 1].toInt()
                    val fb = o.floatBuffer
                    raw = FloatArray(fb.remaining()).also { fb.get(it) }
                }
            }
        }
        var map: DepthMap? = null
        val postMs = ms { map = normalise(raw!!, ow, oh) }
        tensor.close(); session.close(); opts.close()
        DepthResult(label, loadMs, preMs, times, postMs, map)
    } catch (t: Throwable) {
        DepthResult(label, 0.0, 0.0, emptyList(), 0.0, null, t.cause?.message ?: t.message ?: t.javaClass.simpleName)
    }
}

/** LiteRT (TFLite) Interpreter, GPU delegate or CPU (XNNPACK). */
fun runLiteRt(model: File, gpu: Boolean, src: Bitmap, runs: Int, log: (String) -> Unit): DepthResult {
    val label = "LiteRT ${model.name} [${if (gpu) "GPU" else "CPU xnnpack x4"}]"
    var delegate: GpuDelegate? = null
    return try {
        val buf = RandomAccessFile(model, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()) }
        val opts = Interpreter.Options()
        if (gpu) {
            delegate = GpuDelegate(GpuDelegate.Options().setPrecisionLossAllowed(true)
                .setInferencePreference(GpuDelegate.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED))
            opts.addDelegate(delegate)
        } else {
            opts.setNumThreads(4); opts.setUseXNNPACK(true)
        }
        lateinit var interp: Interpreter
        val loadMs = ms { interp = Interpreter(buf, opts) }
        val inT = interp.getInputTensor(0)
        val shape = inT.shape()
        val nchw = shape[1] == 3
        val h = if (nchw) shape[2] else shape[1]; val w = if (nchw) shape[3] else shape[2]
        log("  $label input ${shape.contentToString()} ${inT.dataType()}, output ${interp.getOutputTensor(0).shape().contentToString()}")
        val bmp = Bitmap.createScaledBitmap(src, w, h, true)
        lateinit var input: FloatBuffer
        // Qualcomm AI Hub models take RGB 0...1 and normalise inside.
        val preMs = ms { input = toTensor(bmp, nchw, imagenet = false) }
        val outShape = interp.getOutputTensor(0).shape()
        val n = outShape.fold(1) { a, b -> a * b }
        val out = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder())
        val times = ArrayList<Double>()
        repeat(runs + 1) { i ->
            input.position(0); out.position(0)
            val dt = ms { interp.run(input, out) }
            if (i > 0) times.add(dt) else log("  $label first run %.0f ms".format(dt))
        }
        var map: DepthMap? = null
        val postMs = ms {
            out.position(0)
            val raw = FloatArray(n).also { out.asFloatBuffer().get(it) }
            // [1, H, W], [1, 1, H, W] or NHWC [1, H, W, 1]
            val nhwc = outShape.size == 4 && outShape[3] == 1
            val oh = if (nhwc) outShape[1] else outShape[outShape.size - 2]
            val ow = if (nhwc) outShape[2] else outShape[outShape.size - 1]
            map = normalise(raw, ow, oh)
        }
        interp.close()
        DepthResult(label, loadMs, preMs, times, postMs, map)
    } catch (t: Throwable) {
        DepthResult(label, 0.0, 0.0, emptyList(), 0.0, null, t.message ?: t.javaClass.simpleName)
    } finally {
        delegate?.close()
    }
}

