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
        app.relens.Trace.init(this)
        if (intent.getBooleanExtra("logical", false)) body.rig.useLogical = true
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // The app owns files/models (scripts/install.sh pushes the depth model there).
        File(getExternalFilesDir(null), "models").mkdirs()
        val haptics = Haptics(this)
        setContent {
            // The body's lettering: Jost, a free geometric face after Futura.
            androidx.compose.material3.ProvideTextStyle(androidx.compose.ui.text.TextStyle(fontFamily = Futuraish)) { App(body, haptics) }
        }
        // Testing from the Mac: --es testshot "<lens name part>": a real shot into the test folder (no roll, no gallery).
        intent.getStringExtra("testshot")?.let { want ->
            window.decorView.postDelayed({
                app.relens.optics.lensBag.indexOfFirst { it.name.contains(want, ignoreCase = true) }.takeIf { it >= 0 }?.let { body.mount(it) }
                window.decorView.postDelayed({
                    lifecycleScope.launch {
                        val r = runCatching { body.rig.shoot(java.io.File(getExternalFilesDir("tests"), ""), "testshot") }
                        app.relens.Trace.i("test shot: ${r.getOrNull()?.let { "jpeg ${it.jpeg.length()} dng ${it.dng?.length()}" } ?: r.exceptionOrNull()?.message}")
                        val exp = r.getOrNull() ?: return@launch
                        body.rig.grabLightMap(java.io.File(getExternalFilesDir("tests"), ""), "testshot")
                        val fr = body.rig.framing.value ?: return@launch
                        val dev = runCatching { body.developer.develop(app.relens.engine.DevelopJob(exp.jpeg, exp.dng, body.focusX, body.focusY,
                            exp.cameraFocusD, body.lens, body.fNumber, body.focusM, body.stock, body.push, 0,
                            publish = false, filter = body.filter, crop = fr.crop,
                            focalOverLong = fr.camera.focalMM / fr.camera.sensorLongMM)) }
                        dev.getOrNull()?.file?.copyTo(java.io.File(getExternalFilesDir("tests"), "testprint.jpg"), overwrite = true)
                        app.relens.Trace.i("test print: ${dev.getOrNull()?.timings ?: dev.exceptionOrNull()?.message}")
                    }
                }, 3000)
            }, 3000)
        }
        // Testing from the Mac: re-develop a negative with given settings into the test folder.
        //   --es redevelop R016_F07_225747 --es lens "Kor 35mm" --ef f 1.4 --ef focusm 0.3 --ef crop 1.43 --es stock "E-Chrome"
        intent.getStringExtra("redevelop")?.let { neg ->
            window.decorView.postDelayed({
                lifecycleScope.launch {
                    val dir = java.io.File(getExternalFilesDir("negatives"), "")
                    val lens = app.relens.optics.lensBag.first { it.name.contains(intent.getStringExtra("lens") ?: "Kor 50mm", ignoreCase = true) }
                    val stock = app.relens.film.filmStocks.first { it.name.contains(intent.getStringExtra("stock") ?: "Portrait 400", ignoreCase = true) }
                    val fm = intent.getFloatExtra("focusm", -1f).takeIf { it > 0 }
                    val job = app.relens.engine.DevelopJob(java.io.File(dir, "$neg.jpg"), java.io.File(dir, "$neg.dng").takeIf { it.exists() },
                        intent.getFloatExtra("fx", 0.5f), intent.getFloatExtra("fy", 0.5f), null, lens,
                        intent.getFloatExtra("f", lens.maxAperture), fm, stock, 0, 0, publish = false,
                        crop = intent.getFloatExtra("crop", 0f), focalOverLong = 5.56f / 8.19f, saveDng = intent.getBooleanExtra("dng", false))
                    val r = runCatching { body.developer.develop(job) }
                    r.getOrNull()?.file?.copyTo(java.io.File(getExternalFilesDir("tests"), "redevelop_$neg.jpg"), overwrite = true)
                    android.util.Log.i("Relens", "redevelop $neg: ${r.getOrNull()?.timings ?: r.exceptionOrNull()?.message}")
                }
            }, 3000)
        }
        // --ef parallaxtest <subject m>: the parallax guide on its own, into files/tests/par.
        if (intent.hasExtra("parallaxtest")) window.decorView.postDelayed({
            body.parallaxTest(java.io.File(getExternalFilesDir("tests"), "par"), intent.getFloatExtra("parallaxtest", 1.5f))
        }, 4000)
        // --es mount "<lens>" [--ef tiltx d --ef tilty d]: mount a lens (and rock it) for a look at the finder
        intent.getStringExtra("mount")?.let { want ->
            window.decorView.postDelayed({
                app.relens.optics.lensBag.indexOfFirst { it.name.contains(want, ignoreCase = true) }.takeIf { it >= 0 }?.let { body.mount(it) }
                body.tiltX = intent.getFloatExtra("tiltx", 0f); body.tiltY = intent.getFloatExtra("tilty", 0f)
            }, 2500)
        }
        if (intent.hasExtra("logicaltest")) window.decorView.postDelayed({ lifecycleScope.launch { body.rig.logicalTest() } }, 9000)
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
        // --es screen darkroom (testing from the Mac): open on that screen
        var screen by rememberSaveable { mutableStateOf(intent.getStringExtra("screen") ?: "camera") }
        when (screen) {
            "bag" -> LensBagScreen(body, haptics) { screen = "camera" }
            "film" -> FilmScreen(body, haptics) { screen = "camera" }
            "darkroom" -> LightTableScreen(body, initialScale = intent.getFloatExtra("tablezoom", 1f), onBack = { screen = "camera" },
                openBench = { startActivity(Intent(this, BenchActivity::class.java)) })
            else -> CameraScreen(body, haptics,
                openBag = { screen = "bag" }, openFilm = { screen = "film" }, openDarkroom = { screen = "darkroom" })
        }
    }
}
