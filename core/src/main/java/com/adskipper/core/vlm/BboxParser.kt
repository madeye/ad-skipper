package com.adskipper.core.vlm

import android.graphics.Rect

/**
 * Parses a VLM grounding response like "[123, 456, 234, 567]" into a
 * normalized [ymin, xmin, ymax, xmax] bbox, then maps it to pixels.
 */
object BboxParser {

    private val numberRegex = Regex("-?\\d+(?:\\.\\d+)?")

    /** Returns FloatArray(ymin, xmin, ymax, xmax) normalized to 0..1, or null. */
    fun parse(text: String): FloatArray? {
        val nums = numberRegex.findAll(text).map { it.value.toFloat() }.toList()
        if (nums.size < 4) return null
        val v = nums.take(4)
        val scale = when {
            v.all { it in 0f..1f } -> 1f
            v.all { it in 0f..100f } -> 100f
            else -> 1000f
        }
        val n = FloatArray(4) { (v[it] / scale).coerceIn(0f, 1f) }
        if (n[0] >= n[2] || n[1] >= n[3]) return null
        return n
    }

    fun toPixelRect(norm: FloatArray, width: Int, height: Int): Rect = Rect(
        (norm[1] * width).toInt().coerceIn(0, width),
        (norm[0] * height).toInt().coerceIn(0, height),
        (norm[3] * width).toInt().coerceIn(0, width),
        (norm[2] * height).toInt().coerceIn(0, height),
    )
}
