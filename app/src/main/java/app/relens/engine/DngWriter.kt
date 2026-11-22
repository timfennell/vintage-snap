package app.relens.engine

import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * A linear DNG of the print in high dynamic range: three 16-bit float samples a pixel
 * (LinearRaw, so no demosaic; half floats, as Vintage Click writes), in linear sRGB primaries with a D65 white, with the lens
 * and the film in it (linear.comp: the stock's colour, contrast, grain, halation) but
 * not squeezed into a print's range. Editable as a raw file (white balance, exposure,
 * tone) in Lightroom, Camera Raw, darktable and the like.
 *
 * Highlights are kept: 1.0 is diffuse white and brighter values are written as they are
 * (a half float reaches 65504), so a converter can pull them back. Android's DngCreator
 * only takes a sensor's Bayer data, hence this.
 *
 * Rows are streamed in strips from [rows] (y0, count -> RGBA floats, top row first),
 * so a 12 MP picture never sits in memory as floats all at once.
 */
object DngWriter {
    private const val STRIP = 128

    fun write(file: File, w: Int, h: Int, make: String, model: String, software: String,
              rows: (y0: Int, count: Int, into: ByteBuffer) -> Unit) {
        val stripCount = (h + STRIP - 1) / STRIP
        val stripBytes = IntArray(stripCount) { minOf(STRIP, h - it * STRIP) * w * 6 }

        // ---- the IFD, laid out first (its values after it, then the pixels) ----
        class Entry(val tag: Int, val type: Int, val count: Int, val data: ByteArray)
        val entries = ArrayList<Entry>()
        fun le(n: Int) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
        fun shorts(vararg v: Int) = le(v.size * 2).apply { v.forEach { putShort(it.toShort()) } }.array()
        fun longs(vararg v: Int) = le(v.size * 4).apply { v.forEach { putInt(it) } }.array()
        fun ascii(s: String) = (s + "\u0000").toByteArray(Charsets.US_ASCII)
        fun srational(vararg v: Float) = le(v.size * 8).apply { v.forEach { putInt((it * 10000).roundToInt()); putInt(10000) } }.array()
        fun rational(vararg v: Float) = le(v.size * 8).apply { v.forEach { putInt((it * 10000).roundToInt()); putInt(10000) } }.array()
        val SHORT = 3; val LONG = 4; val ASCII = 2; val BYTE = 1; val RATIONAL = 5; val SRATIONAL = 10

        entries += Entry(254, LONG, 1, longs(0))                       // NewSubfileType: the main image
        entries += Entry(256, LONG, 1, longs(w))
        entries += Entry(257, LONG, 1, longs(h))
        entries += Entry(258, SHORT, 3, shorts(16, 16, 16))            // BitsPerSample
        entries += Entry(259, SHORT, 1, shorts(1))                     // no compression
        entries += Entry(262, SHORT, 1, shorts(34892))                 // LinearRaw
        entries += Entry(271, ASCII, ascii(make).size, ascii(make))
        entries += Entry(272, ASCII, ascii(model).size, ascii(model))
        entries += Entry(273, LONG, stripCount, ByteArray(stripCount * 4))   // StripOffsets: filled in below
        entries += Entry(274, SHORT, 1, shorts(1))                     // upright already
        entries += Entry(277, SHORT, 1, shorts(3))                     // SamplesPerPixel
        entries += Entry(278, LONG, 1, longs(STRIP))                   // RowsPerStrip
        entries += Entry(279, LONG, stripCount, longs(*stripBytes))    // StripByteCounts
        entries += Entry(284, SHORT, 1, shorts(1))                     // chunky
        entries += Entry(305, ASCII, ascii(software).size, ascii(software))
        val date = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date())
        entries += Entry(306, ASCII, ascii(date).size, ascii(date))
        entries += Entry(339, SHORT, 3, shorts(3, 3, 3))               // SampleFormat: IEEE float
        entries += Entry(50706, BYTE, 4, byteArrayOf(1, 4, 0, 0))      // DNGVersion
        entries += Entry(50707, BYTE, 4, byteArrayOf(1, 4, 0, 0))      // DNGBackwardVersion: float data needs 1.4
        val ucm = "$make $model"
        entries += Entry(50708, ASCII, ascii(ucm).size, ascii(ucm))    // UniqueCameraModel
        entries += Entry(50717, LONG, 3, longs(1, 1, 1))               // WhiteLevel: 1.0 for float data (brighter is kept)
        // ColorMatrix1: XYZ (D65) -> the data's space, linear sRGB.
        entries += Entry(50721, SRATIONAL, 9, srational(3.2406f, -1.5372f, -0.4986f, -0.9689f, 1.8758f, 0.0415f, 0.0557f, -0.2040f, 1.0570f))
        entries += Entry(50728, RATIONAL, 3, rational(1f, 1f, 1f))     // AsShotNeutral: already balanced
        entries += Entry(50730, SRATIONAL, 1, srational(0f))           // BaselineExposure
        entries += Entry(50778, SHORT, 1, shorts(21))                  // CalibrationIlluminant1: D65
        // ForwardMatrix1: white-balanced data -> XYZ D50 (linear sRGB, Bradford-adapted).
        entries += Entry(50964, SRATIONAL, 9, srational(0.4361f, 0.3851f, 0.1431f, 0.2225f, 0.7169f, 0.0606f, 0.0139f, 0.0971f, 0.7141f))
        entries.sortBy { it.tag }

        val ifdStart = 8
        val ifdSize = 2 + entries.size * 12 + 4
        var extra = ifdStart + ifdSize
        val extraOffsets = HashMap<Entry, Int>()
        for (e in entries) if (e.data.size > 4) { extraOffsets[e] = extra; extra += e.data.size + (e.data.size and 1) }
        val pixelStart = extra
        val offsets = IntArray(stripCount); var o = pixelStart
        for (i in 0 until stripCount) { offsets[i] = o; o += stripBytes[i] }
        val so = entries.first { it.tag == 273 }
        ByteBuffer.wrap(so.data).order(ByteOrder.LITTLE_ENDIAN).apply { offsets.forEach { putInt(it) } }

        BufferedOutputStream(file.outputStream(), 1 shl 20).use { out ->
            val head = le(ifdSize + 8)
            head.put('I'.code.toByte()); head.put('I'.code.toByte()); head.putShort(42); head.putInt(ifdStart)
            head.putShort(entries.size.toShort())
            for (e in entries) {
                head.putShort(e.tag.toShort()); head.putShort(e.type.toShort()); head.putInt(e.count)
                if (e.data.size <= 4) { head.put(e.data); repeat(4 - e.data.size) { head.put(0) } }
                else head.putInt(extraOffsets[e]!!)
            }
            head.putInt(0)                                              // no next IFD
            out.write(head.array())
            for (e in entries) if (e.data.size > 4) { out.write(e.data); if (e.data.size and 1 == 1) out.write(0) }
            writePixels(out, w, h, rows)
        }
    }

    private fun writePixels(out: OutputStream, w: Int, h: Int, rows: (Int, Int, ByteBuffer) -> Unit) {
        val fbuf = ByteBuffer.allocateDirect(w * STRIP * 16).order(ByteOrder.nativeOrder())
        val obuf = ByteArray(w * STRIP * 6)
        var y = 0
        while (y < h) {
            val n = minOf(STRIP, h - y)
            rows(y, n, fbuf)
            val fb = fbuf.asFloatBuffer()
            var k = 0
            for (i in 0 until w * n) {
                for (c in 0..2) {
                    val f = fb.get(i * 4 + c)
                    val v = android.util.Half.toHalf(if (f.isNaN()) 0f else f.coerceIn(0f, 65504f)).toInt()
                    obuf[k++] = (v and 0xFF).toByte(); obuf[k++] = (v shr 8).toByte()
                }
            }
            out.write(obuf, 0, k)
            y += n
        }
    }
}
