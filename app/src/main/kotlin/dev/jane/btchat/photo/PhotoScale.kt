package dev.jane.btchat.photo

import kotlin.math.roundToInt

object PhotoScale {
    const val MAX_LONG_EDGE = 1280
    const val THUMB_LONG_EDGE = 256
    const val JPEG_QUALITY = 80
    const val THUMB_QUALITY = 70

    /** Target size so the long edge is at most [maxLongEdge]. Never upscales, never returns zero. */
    fun fit(w: Int, h: Int, maxLongEdge: Int): Pair<Int, Int> {
        val long = maxOf(w, h)
        if (long <= maxLongEdge) return w to h
        val scale = maxLongEdge.toDouble() / long
        return maxOf(1, (w * scale).roundToInt()) to maxOf(1, (h * scale).roundToInt())
    }

    /** Power-of-two decode sample size that keeps the decoded long edge at or above [maxLongEdge]. */
    fun sampleSize(w: Int, h: Int, maxLongEdge: Int): Int {
        var sample = 1
        while (maxOf(w, h) / (sample * 2) >= maxLongEdge) sample *= 2
        return sample
    }
}
