package app.relens.camera

import java.io.File

/** A frame on the roll: the camera's files and its own focus distance (dioptres). */
data class Exposure(val jpeg: File, val dng: File?, val cameraFocusD: Float?)
