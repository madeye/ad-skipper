package com.adskipper.core.detect

enum class SkipLayer { L1_NODE, L2_OCR, L3_YOLO, L3_VLM }

data class SkipTarget(
    val x: Float,
    val y: Float,
    val layer: SkipLayer,
    val detail: String = "",
)
