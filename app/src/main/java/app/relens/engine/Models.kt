package app.relens.engine

import android.content.Context
import app.relens.Trace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The depth models the developer needs, too big for the APK: fetched once from the project's
 * GitHub release (models-v1) into the app's files/models, each checked against its SHA-256.
 *  - Depth Anything V2 Small (Apache-2.0), free size: the relative depth (prints, live finder).
 *  - Metric3D v2 ViT-S (BSD-2): the scale, when a frame has no parallax depth.
 */
class Models(private val context: Context) {
    class Model(val name: String, val sha256: String, val bytes: Long)

    // The original free-size Depth Anything serves both the prints (run at 770 x 1036) and the
    // live finder (a small size); phones that already have the older fixed-size export keep it
    // for prints and fetch this one for the finder.
    val models = listOf(
        Model("da2s_fp32.onnx", "afb6a5c28f3b6bf1618c6e43f02073ef9dfdc70e937502d51603e57b0a1df10c", 99_060_839),
        Model("metric3d_vits.onnx", "674a665052f01bb2b64687200182f3380f0a462f4b6ef2e51fefd06c84d8ee75", 150_739_433),
    )
    private val base = "https://github.com/timfennell/vintage-snap/releases/download/models-v1/"
    private val dir get() = File(context.getExternalFilesDir(null), "models").also { it.mkdirs() }

    val totalBytes get() = models.sumOf { it.bytes }
    fun missing(): List<Model> = models.filter { m -> File(dir, m.name).let { !it.exists() || it.length() != m.bytes } }

    sealed class State {
        object Idle : State()
        class Downloading(val done: Long, val total: Long) : State()
        class Failed(val why: String) : State()
        object Ready : State()
    }
    private val _state = MutableStateFlow<State>(if (missing().isEmpty()) State.Ready else State.Idle)
    val state: StateFlow<State> = _state

    /** Fetches what's missing; true when all are in place. */
    suspend fun download(): Boolean = withContext(Dispatchers.IO) {
        val todo = missing()
        val total = todo.sumOf { it.bytes }
        var done = 0L
        try {
            for (m in todo) {
                val part = File(dir, m.name + ".part")
                val md = MessageDigest.getInstance("SHA-256")
                var url = URL(base + m.name)
                var conn: HttpURLConnection
                // GitHub hands release assets over by redirect to its storage host
                var hops = 0
                while (true) {
                    conn = url.openConnection() as HttpURLConnection
                    conn.instanceFollowRedirects = false
                    conn.connectTimeout = 20_000; conn.readTimeout = 30_000
                    conn.setRequestProperty("User-Agent", "VintageSnap")
                    val code = conn.responseCode
                    if (code in 300..399 && hops++ < 6) { url = URL(url, conn.getHeaderField("Location")); conn.disconnect(); continue }
                    if (code != 200) error("${m.name}: HTTP $code")
                    break
                }
                conn.inputStream.use { inp -> part.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = inp.read(buf); if (n < 0) break
                        out.write(buf, 0, n); md.update(buf, 0, n); done += n
                        _state.value = State.Downloading(done, total)
                    }
                } }
                val hex = md.digest().joinToString("") { "%02x".format(it) }
                if (hex != m.sha256) { part.delete(); error("${m.name}: checksum mismatch") }
                part.renameTo(File(dir, m.name))
                Trace.i("model ${m.name} downloaded")
            }
            _state.value = State.Ready
            true
        } catch (t: Throwable) {
            Trace.w("model download failed", t)
            _state.value = State.Failed(t.message ?: t.javaClass.simpleName)
            false
        }
    }
}
