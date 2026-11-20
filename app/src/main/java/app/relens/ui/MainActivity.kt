package app.relens.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import app.relens.spike.BenchActivity
import java.io.File

class MainActivity : ComponentActivity() {
    private val body: Body by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // The app owns files/models (scripts/install.sh pushes the depth model there).
        File(getExternalFilesDir(null), "models").mkdirs()
        val haptics = Haptics(this)
        setContent { App(body, haptics) }
        // Testing from the Mac: --es testshot "<lens name part>": a real shot into the test folder (no roll, no gallery).
        intent.getStringExtra("testshot")?.let { want ->
            window.decorView.postDelayed({
                app.relens.optics.lensBag.indexOfFirst { it.name.contains(want, ignoreCase = true) }.takeIf { it >= 0 }?.let { body.mount(it) }
                window.decorView.postDelayed({
                    lifecycleScope.launch {
                        val r = runCatching { body.rig.shoot(java.io.File(getExternalFilesDir("tests"), ""), "testshot") }
                        android.util.Log.i("Relens", "test shot: ${r.getOrNull()?.let { "jpeg ${it.jpeg.length()} dng ${it.dng?.length()}" } ?: r.exceptionOrNull()?.message}")
                        val exp = r.getOrNull() ?: return@launch
                        val fr = body.rig.framing.value ?: return@launch
                        val dev = runCatching { body.developer.develop(app.relens.engine.DevelopJob(exp.jpeg, exp.dng, body.focusX, body.focusY,
                            exp.cameraFocusD, body.lens, body.fNumber, body.focusM, body.stock, body.push, 0,
                            publish = false, filter = body.filter, crop = fr.crop,
                            focalOverLong = fr.camera.focalMM / fr.camera.sensorLongMM)) }
                        dev.getOrNull()?.file?.copyTo(java.io.File(getExternalFilesDir("tests"), "testprint.jpg"), overwrite = true)
                        android.util.Log.i("Relens", "test print: ${dev.getOrNull()?.timings ?: dev.exceptionOrNull()?.message}")
                    }
                }, 3000)
            }, 3000)
        }
        // Testing from the Mac: --es rawtest "<lens name part>": a RAW-only capture to a test folder (no roll, no gallery).
        intent.getStringExtra("rawtest")?.let { want ->
            window.decorView.postDelayed({
                app.relens.optics.lensBag.indexOfFirst { it.name.contains(want, ignoreCase = true) }.takeIf { it >= 0 }?.let { body.mount(it) }
                window.decorView.postDelayed({
                    lifecycleScope.launch { body.rig.rawTest(java.io.File(getExternalFilesDir("tests"), "rawtest.dng")) }
                }, 3000)
            }, 3000)
        }
        // Testing from the Mac: adb shell am start -n app.relens/.ui.MainActivity --es autoshoot "<lens name part>"
        intent.getStringExtra("autoshoot")?.let { want ->
            window.decorView.postDelayed({
                app.relens.optics.lensBag.indexOfFirst { it.name.contains(want, ignoreCase = true) }.takeIf { it >= 0 }?.let { body.mount(it) }
                window.decorView.postDelayed({ body.shoot() }, 3000)
            }, 3000)
        }
    }

    @Composable
    private fun App(body: Body, haptics: Haptics) {
        var granted by remember {
            mutableStateOf(ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        }
        val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
        LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }
        if (!granted) {
            Box(Modifier.fillMaxSize().background(Palette.leather).clickable { ask.launch(Manifest.permission.CAMERA) },
                contentAlignment = Alignment.Center) {
                Text("The camera needs permission to see.\nTap to ask again.", color = Palette.cream, fontSize = 16.sp,
                    modifier = Modifier.padding(24.dp))
            }
            return
        }
        // The picture follows how the phone is held; the body is drawn upright always.
        androidx.compose.runtime.DisposableEffect(Unit) {
            val l = object : android.view.OrientationEventListener(this@MainActivity) {
                override fun onOrientationChanged(deg: Int) {
                    if (deg == ORIENTATION_UNKNOWN) return
                    body.rig.deviceRotation = ((deg + 45) / 90 % 4) * 90
                }
            }
            l.enable()
            onDispose { l.disable() }
        }
        var screen by rememberSaveable { mutableStateOf("camera") }
        when (screen) {
            "bag" -> LensBagScreen(body, haptics) { screen = "camera" }
            "film" -> FilmScreen(body, haptics) { screen = "camera" }
            "darkroom" -> DarkroomScreen(body, onBack = { screen = "camera" },
                openBench = { startActivity(Intent(this, BenchActivity::class.java)) })
            else -> CameraScreen(body, haptics,
                openBag = { screen = "bag" }, openFilm = { screen = "film" }, openDarkroom = { screen = "darkroom" })
        }
    }
}
