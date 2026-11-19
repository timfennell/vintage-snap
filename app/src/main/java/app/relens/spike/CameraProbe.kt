package app.relens.spike

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import kotlin.math.atan
import kotlin.math.hypot

/**
 * What the phone's cameras offer, from Camera2 characteristics (no camera
 * permission needed to read them): RAW and its size, the phone's own depth
 * outputs (DEPTH_JPEG = Dynamic Depth, DEPTH16 streams), and the optics the
 * blur maths needs (focal length, sensor size, aperture).
 */
object CameraProbe {
    fun report(ctx: Context): List<String> = try {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ids = cm.cameraIdList.toMutableSet()
        // Logical multi-cameras hide their physical lenses; list those too.
        for (id in cm.cameraIdList) ids += cm.getCameraCharacteristics(id).physicalCameraIds
        ids.sortedBy { it.toIntOrNull() ?: 999 }.map { id -> describe(id, cm.getCameraCharacteristics(id)) }
    } catch (t: Throwable) {
        listOf("camera probe failed: ${t.message}")
    }

    private fun describe(id: String, c: CameraCharacteristics): String {
        val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
            CameraMetadata.LENS_FACING_BACK -> "back"; CameraMetadata.LENS_FACING_FRONT -> "front"; else -> "ext"
        }
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet() ?: emptySet()
        val raw = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW in caps
        val depthCap = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT in caps
        val logical = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in caps
        val ultraHighRes = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR in caps
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        fun sizes(fmt: Int) = runCatching { map?.getOutputSizes(fmt)?.sortedByDescending { it.width * it.height } }.getOrNull()
        val rawSizes = sizes(ImageFormat.RAW_SENSOR)
        // Full-resolution (unbinned) RAW on quad-Bayer sensors lives in the maximum-resolution map.
        val maxMap = runCatching { c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION) }.getOrNull()
        val rawMax = runCatching { maxMap?.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width * it.height } }.getOrNull()
        val jpeg = sizes(ImageFormat.JPEG)?.firstOrNull()
        val depthJpeg = sizes(ImageFormat.DEPTH_JPEG)?.firstOrNull()
        val depth16 = sizes(ImageFormat.DEPTH16)?.firstOrNull()
        val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.joinToString("/") { "%.2f".format(it) }
        val ap = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.joinToString("/") { "f%.2f".format(it) }
        val phys = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val f0 = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        val equiv = if (phys != null && f0 != null) " (~%.0f mm equiv)".format(f0 * hypot(36.0, 24.0) / hypot(phys.width.toDouble(), phys.height.toDouble())) else ""
        val hfov = if (phys != null && f0 != null) " hfov %.0f°".format(Math.toDegrees(2 * atan(phys.width / (2.0 * f0)))) else ""
        val minFocus = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)?.let { if (it > 0) " min focus %.2f m".format(1 / it) else " fixed focus" } ?: ""
        val hw = when (c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"; CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"; CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
            else -> "?"
        }
        return buildString {
            append("camera $id $facing $hw${if (logical) " logical" else ""}: f=$focal mm$equiv $ap$hfov$minFocus")
            phys?.let { append(", sensor %.2fx%.2f mm".format(it.width, it.height)) }
            append(", jpeg ${jpeg ?: "-"}")
            append(", RAW ${if (raw) (rawSizes?.firstOrNull() ?: "cap but no size") else "no"}")
            if (ultraHighRes || rawMax != null) append(", max-res RAW ${rawMax ?: "?"}")
            append(", DEPTH_JPEG ${depthJpeg ?: "no"}, DEPTH16 ${depth16 ?: "no"}${if (depthCap) " (depth cap)" else ""}")
        }
    }
}
