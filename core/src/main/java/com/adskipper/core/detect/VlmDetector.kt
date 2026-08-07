package com.adskipper.core.detect

import android.graphics.Bitmap
import android.graphics.Rect
import com.adskipper.core.model.ModelInfo
import com.adskipper.core.vlm.BboxParser
import com.adskipper.core.vlm.VlmEngine
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * L3: local VLM grounding for complex cases (countdown rings, obfuscated
 * or image-only skip buttons).
 *
 * Input size and coordinate convention come from the active [ModelInfo]:
 * 448px input is too small for any model to see the skip button (vlm-bench
 * 2026-08: InternVL3-2B goes 4/20 -> 16/20 hits from 448 -> 896px).
 */
class VlmDetector(
    private val engine: VlmEngine,
    private val model: ModelInfo,
) {

    suspend fun findSkipButton(bitmap: Bitmap, timeoutMs: Long): Rect? {
        if (!engine.isReady) return null
        val scaled = downscale(bitmap, model.maxDim)
        val output = withTimeoutOrNull(timeoutMs) {
            engine.infer(scaled, PROMPT, MAX_TOKENS)
        } ?: return null
        Timber.d("VLM raw output: %s", output)
        val norm = BboxParser.parse(output, model.coordSpace, scaled.width, scaled.height)
            ?: return null
        // Normalized coords scale proportionally back to the full screenshot.
        return BboxParser.toPixelRect(norm, bitmap.width, bitmap.height)
    }

    private fun downscale(src: Bitmap, maxDim: Int): Bitmap {
        val max = maxOf(src.width, src.height)
        if (max <= maxDim) return src
        val scale = maxDim.toFloat() / max
        return Bitmap.createScaledBitmap(
            src,
            (src.width * scale).toInt().coerceAtLeast(1),
            (src.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    companion object {
        private const val MAX_TOKENS = 64

        // Native grounding format: models answer {"bbox_2d": [x1,y1,x2,y2]}.
        // Asking for the app's old [ymin,xmin,ymax,xmax]-normalized format
        // scored 0/20 across all benchmarked models.
        private const val PROMPT =
            "This is a screenshot of a mobile app splash advertisement. " +
            "Locate the button used to skip or close the ad (usually labeled " +
            "'跳过', 'Skip', '关闭' or shown as a countdown circle). " +
            "Output only its bounding box in JSON: {\"bbox_2d\": [x1, y1, x2, y2]}"
    }
}
