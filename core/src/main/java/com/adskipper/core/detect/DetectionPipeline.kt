package com.adskipper.core.detect

import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import com.adskipper.core.data.AppSettings
import com.adskipper.core.model.ModelInfo
import com.adskipper.core.vlm.VlmEngine

/**
 * Layered detection pipeline with short-circuit:
 *   L1 node tree match (<10ms) -> L2 OCR (~100ms) ->
 *   L3 bundled YOLO detector (tens of ms) -> L3 downloaded VLM (~seconds)
 *
 * Layers return tap coordinates (center x, y) directly so the pipeline
 * itself is free of android.graphics calls and stays unit-testable.
 */
class DetectionPipeline<I>(
    private val l1: suspend (root: AccessibilityNodeInfo?, keywords: Collection<String>) -> Pair<Float, Float>?,
    private val l2: suspend (image: I, keywords: Collection<String>) -> OcrPass,
    private val l3: suspend (image: I) -> Pair<Float, Float>?,
    private val l3yolo: suspend (image: I) -> Pair<Float, Float>? = { null },
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {

    /** L2's full output: the skip-button hit (if any) plus every OCR line,
     *  so callers can mine the same pass for ad evidence. */
    data class OcrPass(
        val hit: Pair<Float, Float>?,
        val lines: List<OcrLine> = emptyList(),
    )

    data class Result(
        val target: SkipTarget?,
        /** Per-layer elapsed time, in evaluation order. */
        val timings: List<Pair<SkipLayer, Long>>,
    )

    /** [imageLayerGate] decides — after seeing this tick's OCR lines —
     *  whether the image layers (YOLO/VLM) may run and tap. Keyword layers
     *  are never gated. Defaults to open for tests/tools. */
    suspend fun detect(
        root: AccessibilityNodeInfo?,
        screenshot: suspend () -> I?,
        settings: AppSettings,
        imageLayerGate: suspend (lines: List<OcrLine>) -> Boolean = { true },
    ): Result {
        val timings = ArrayList<Pair<SkipLayer, Long>>(3)

        if (settings.layer1Enabled) {
            val t0 = clock()
            val hit = l1(root, settings.keywords)
            timings += SkipLayer.L1_NODE to (clock() - t0)
            if (hit != null) {
                return Result(SkipTarget(hit.first, hit.second, SkipLayer.L1_NODE), timings)
            }
        }

        // Everything below needs pixels.
        if (!settings.layer2Enabled && !settings.layer3Enabled) return Result(null, timings)

        // Screenshot unavailable (e.g. FLAG_SECURE) stops the pipeline.
        val bitmap = screenshot() ?: return Result(null, timings)

        var lines: List<OcrLine> = emptyList()
        if (settings.layer2Enabled) {
            val t0 = clock()
            val pass = l2(bitmap, settings.keywords)
            lines = pass.lines
            timings += SkipLayer.L2_OCR to (clock() - t0)
            pass.hit?.let {
                return Result(SkipTarget(it.first, it.second, SkipLayer.L2_OCR), timings)
            }
        }

        if (settings.layer3Enabled && imageLayerGate(lines)) {
            val t0 = clock()
            val yoloHit = l3yolo(bitmap)
            timings += SkipLayer.L3_YOLO to (clock() - t0)
            if (yoloHit != null) {
                return Result(SkipTarget(yoloHit.first, yoloHit.second, SkipLayer.L3_YOLO), timings)
            }

            val t1 = clock()
            val hit = l3(bitmap)
            timings += SkipLayer.L3_VLM to (clock() - t1)
            if (hit != null) {
                return Result(SkipTarget(hit.first, hit.second, SkipLayer.L3_VLM), timings)
            }
        }

        return Result(null, timings)
    }

    companion object {
        fun create(
            engine: VlmEngine,
            settings: AppSettings,
            model: ModelInfo,
            yolo: YoloSkipDetector?,
            selfLabels: Collection<String> = emptySet(),
        ): DetectionPipeline<Bitmap> {
            val ocr = OcrDetector()
            val vlm = VlmDetector(engine, model)
            return DetectionPipeline<Bitmap>(
                l1 = { root, keywords ->
                    NodeMatcher.findSkipNode(root, keywords, selfLabels)
                        ?.let { it.exactCenterX() to it.exactCenterY() }
                },
                l2 = { bitmap, keywords ->
                    val lines = ocr.recognize(bitmap)
                    val hit = KeywordMatcher.findSkipLine(lines, keywords, selfLabels)
                    OcrPass(hit?.let { it.centerX.toFloat() to it.centerY.toFloat() }, lines)
                },
                l3yolo = { bitmap ->
                    yolo?.findSkipButton(bitmap)
                        ?.let { it.exactCenterX() to it.exactCenterY() }
                },
                l3 = { bitmap ->
                    vlm.findSkipButton(bitmap, settings.vlmTimeoutMs)
                        ?.let { it.exactCenterX() to it.exactCenterY() }
                },
            )
        }
    }
}
