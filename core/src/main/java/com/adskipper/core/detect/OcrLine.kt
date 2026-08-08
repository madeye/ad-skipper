package com.adskipper.core.detect

/**
 * One recognized text line from an OCR pass, in screen coordinates.
 * Plain data (no android.graphics) so detection logic stays unit-testable.
 */
data class OcrLine(
    val text: String,
    val centerX: Int,
    val centerY: Int,
    val width: Int,
    val height: Int,
)
