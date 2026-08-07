package com.adskipper.core.detect

import android.graphics.Bitmap
import android.graphics.Rect
import com.adskipper.core.vlm.BboxParser
import com.adskipper.core.vlm.VlmEngine
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * L3: local VLM grounding for complex cases (countdown rings, obfuscated
 * or image-only skip buttons). ~0.5-2s on CPU with an INT4 2B model.
 */
class VlmDetector(private val engine: VlmEngine) {

    suspend fun findSkipButton(bitmap: Bitmap, timeoutMs: Long): Rect? {
        if (!engine.isReady) return null
        val scaled = downscale(bitmap, MAX_DIM)
        val output = withTimeoutOrNull(timeoutMs) {
            engine.infer(scaled, PROMPT, MAX_TOKENS)
        } ?: return null
        Timber.d("VLM raw output: %s", output)
        val norm = BboxParser.parse(output) ?: return null
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
        private const val MAX_DIM = 448
        private const val MAX_TOKENS = 64
        private const val PROMPT =
            "This is a screenshot of a mobile app showing a splash advertisement. " +
            "Locate the button used to skip or close the ad (usually labeled " +
            "'跳过', 'Skip', '关闭' or shown as a countdown circle). " +
            "Reply with only the bounding box as [ymin, xmin, ymax, xmax] " +
            "normalized to 0-1000."
    }
}
