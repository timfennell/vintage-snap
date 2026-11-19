package app.relens.spike

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * Runs the bench on launch and shows the log. Start it from the Mac with
 *   adb shell am start -n app.relens.spike/.MainActivity --es flags "slow"
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val text = TextView(this).apply { textSize = 11f; setPadding(24, 48, 24, 48); setTextIsSelectable(true) }
        val scroll = ScrollView(this).apply { addView(text) }
        setContentView(scroll)
        val flags = (intent.getStringExtra("flags") ?: "").split(',', ' ').filter { it.isNotBlank() }.toSet()
        thread(name = "bench") {
            Bench(this) { line ->
                runOnUiThread { text.append(line + "\n"); scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) } }
            }.run(flags)
        }
    }
}
