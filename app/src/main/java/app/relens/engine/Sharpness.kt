package app.relens.engine

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Where the phone's picture is sharp, by depth: the contrast-normalised
 * Laplacian of the picture the depth network saw, grouped by its disparity.
 * What is sharpest is what the lens was focused on.
 */
object Sharpness {
    /**
     * The disparity (0...1) whose pixels are sharpest, and how much sharper they
     * are than the median depth's; null when no depth clearly stands out (the
     * whole picture soft, or evenly sharp).
     */
    fun peak(picture: Bitmap, depth: DepthMap, bins: Int = 24): Pair<Float, Float>? {
        val w = depth.w; val h = depth.h
        val bmp = if (picture.width == w && picture.height == h) picture else Bitmap.createScaledBitmap(picture, w, h, true)
        val px = IntArray(w * h).also { bmp.getPixels(it, 0, w, 0, 0, w, h) }
        val l = FloatArray(w * h) { i -> val c = px[i]; sqrt((0.2126f * ((c shr 16) and 0xff) + 0.7152f * ((c shr 8) and 0xff) + 0.0722f * (c and 0xff)) / 255f) }
        val sum = DoubleArray(bins); val n = IntArray(bins)
        for (y in 2 until h - 2) for (x in 2 until w - 2) {
            val i = y * w + x
            val lap = abs(4 * l[i] - l[i - 1] - l[i + 1] - l[i - w] - l[i + w])
            // local contrast: a step two pixels out, so texture, not just noise, counts
            val mean = (l[i - 2] + l[i + 2] + l[i - 2 * w] + l[i + 2 * w]) / 4
            val s = lap / (mean + 0.05f)
            val b = (depth.data[i] * bins).toInt().coerceIn(0, bins - 1)
            sum[b] += s; n[b]++
        }
        val total = n.sum().coerceAtLeast(1)
        val means = (0 until bins).map { if (n[it] >= total * 0.01) sum[it] / n[it] else -1.0 }
        val valid = means.filter { it > 0 }.sorted()
        if (valid.size < 3) return null
        val best = means.indices.maxBy { means[it] }
        val median = valid[valid.size / 2]
        val prominence = (means[best] / median).toFloat()
        return if (prominence >= 1.6f) Pair((best + 0.5f) / bins, prominence) else null
    }
}
