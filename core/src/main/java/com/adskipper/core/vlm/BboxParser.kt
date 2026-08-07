package com.adskipper.core.vlm

import android.graphics.Rect
import com.adskipper.core.model.CoordSpace

/**
 * Parses a VLM grounding response into a normalized [x1, y1, x2, y2] bbox.
 *
 * Models answer in the xyxy order (benchmarked: InternVL3, Qwen2.5-VL and
 * MiniCPM-V all do), typically wrapped in JSON like {"bbox_2d": [x1,y1,x2,y2]}.
 * Numbers are taken from the first bracket [...] group so that digits embedded
 * in identifiers (the `2` in `bbox_2d`) can't corrupt the coordinates.
 */
object BboxParser {

    private val bracketRegex = Regex("\\[([^\\[\\]]*)]")
    private val numberRegex = Regex("-?\\d+(?:\\.\\d+)?")

    // Digits that are part of an identifier ("bbox_2d", "x1") don't count.
    private val standaloneNumberRegex = Regex("(?<![A-Za-z_0-9])-?\\d+(?:\\.\\d+)?")

    /**
     * Returns FloatArray(x1, y1, x2, y2) normalized to 0..1, or null.
     *
     * [imgW]/[imgH] are the dimensions of the image the model actually saw
     * (the downscaled screenshot) — required to interpret [CoordSpace.PIXELS]
     * answers.
     */
    fun parse(text: String, coordSpace: CoordSpace, imgW: Int, imgH: Int): FloatArray? {
        val v = extractNumbers(text) ?: return null
        val n = when (coordSpace) {
            CoordSpace.NORM -> {
                val scale = when {
                    v.all { it in 0f..1f } -> 1f
                    v.all { it in 0f..100f } -> 100f
                    else -> 1000f
                }
                FloatArray(4) { (v[it] / scale).coerceIn(0f, 1f) }
            }
            CoordSpace.PIXELS -> {
                if (imgW <= 0 || imgH <= 0) return null
                floatArrayOf(
                    (v[0] / imgW).coerceIn(0f, 1f),
                    (v[1] / imgH).coerceIn(0f, 1f),
                    (v[2] / imgW).coerceIn(0f, 1f),
                    (v[3] / imgH).coerceIn(0f, 1f),
                )
            }
        }
        if (n[0] >= n[2] || n[1] >= n[3]) return null
        return n
    }

    /** First four coordinates: prefer a bracket group holding >= 4 numbers,
     *  else standalone numbers anywhere in the text. */
    private fun extractNumbers(text: String): FloatArray? {
        for (group in bracketRegex.findAll(text)) {
            val nums = numberRegex.findAll(group.groupValues[1])
                .map { it.value.toFloat() }.toList()
            if (nums.size >= 4) return nums.take(4).toFloatArray()
        }
        val nums = standaloneNumberRegex.findAll(text).map { it.value.toFloat() }.toList()
        if (nums.size < 4) return null
        return nums.take(4).toFloatArray()
    }

    fun toPixelRect(norm: FloatArray, width: Int, height: Int): Rect = Rect(
        (norm[0] * width).toInt().coerceIn(0, width),
        (norm[1] * height).toInt().coerceIn(0, height),
        (norm[2] * width).toInt().coerceIn(0, width),
        (norm[3] * height).toInt().coerceIn(0, height),
    )
}
