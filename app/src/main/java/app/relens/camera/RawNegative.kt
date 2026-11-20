package app.relens.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.media.Image
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * A RAW frame for the developer: the sensor's values as packed little-endian
 * 16-bit (width x height), and a JSON of what reading them needs: black and
 * white levels, the colour filter layout, the white balance (as-shot neutral),
 * the colour matrices and their illuminants, the lens shading map, the sensor's
 * orientation. (The DNG beside it is the negative for the photographer.)
 */
object RawNegative {
    fun write(bin: File, json: File, img: Image, c: CameraCharacteristics, r: CaptureResult, orientation: Int) {
        val w = img.width; val h = img.height
        val plane = img.planes[0]
        val src = plane.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val row = ByteBuffer.allocateDirect(w * 2).order(ByteOrder.LITTLE_ENDIAN)
        FileOutputStream(bin).channel.use { ch: FileChannel ->
            for (y in 0 until h) {
                src.limit(y * plane.rowStride + w * 2); src.position(y * plane.rowStride)
                row.clear(); row.put(src); row.flip(); ch.write(row)
                src.limit(src.capacity())
            }
        }
        fun cst(t: ColorSpaceTransform?): JSONArray? = t?.let { m ->
            JSONArray().also { a -> for (i in 0 until 3) for (j in 0 until 3) a.put(m.getElement(j, i).toDouble()) }
        }
        val o = JSONObject()
        o.put("width", w); o.put("height", h); o.put("orientation", orientation)
        o.put("cfa", c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 0)
        val black = r.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)?.map { it.toDouble() }
            ?: c.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)?.let { p -> (0 until 4).map { i -> p.getOffsetForIndex(i % 2, i / 2).toDouble() } }
            ?: listOf(64.0, 64.0, 64.0, 64.0)
        o.put("black", JSONArray(black))
        o.put("white", (r.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL) ?: c.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023).toDouble())
        r.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)?.let { n -> o.put("neutral", JSONArray(n.map { it.toDouble() })) }
        cst(c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1))?.let { o.put("cm1", it) }
        cst(c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2))?.let { o.put("cm2", it) }
        cst(c.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1))?.let { o.put("fm1", it) }
        cst(c.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2))?.let { o.put("fm2", it) }
        o.put("illum1", c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1) ?: 17)
        o.put("illum2", (c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2) ?: 21).toInt())
        r.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)?.let { m ->
            o.put("shadingW", m.columnCount); o.put("shadingH", m.rowCount)
            val a = JSONArray()
            for (y in 0 until m.rowCount) for (x in 0 until m.columnCount) for (ch in 0 until 4)
                a.put(m.getGainFactor(ch, x, y).toDouble())
            o.put("shading", a)
        }
        json.writeText(o.toString())
    }
}
