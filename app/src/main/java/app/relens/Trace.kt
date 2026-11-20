package app.relens

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The app's log, also kept in a file on the phone (files/relens.log, last
 * ~2 MB): the phone's own log is flooded by the camera drivers within seconds,
 * so what happened to a shot is read from here afterwards.
 */
object Trace {
    private var file: File? = null
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun init(c: Context) { file = File(c.getExternalFilesDir(null), "relens.log") }

    @Synchronized private fun write(level: String, msg: String, t: Throwable? = null) {
        val f = file ?: return
        runCatching {
            if (f.length() > 2_000_000) f.renameTo(File(f.parentFile, "relens.old.log"))
            f.appendText("${fmt.format(Date())} $level $msg${t?.let { " : " + Log.getStackTraceString(it).lines().take(6).joinToString(" | ") } ?: ""}\n")
        }
    }

    fun i(msg: String) { Log.i("Relens", msg); write("I", msg) }
    fun w(msg: String, t: Throwable? = null) { Log.w("Relens", msg, t); write("W", msg, t) }
    fun e(msg: String, t: Throwable? = null) { Log.e("Relens", msg, t); write("E", msg, t) }
}
