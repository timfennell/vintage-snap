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
        LaunchedEffect(Unit) { body.camera.bind(this@MainActivity) }
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
