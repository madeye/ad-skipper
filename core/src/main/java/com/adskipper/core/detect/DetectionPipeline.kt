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
    private val l2: suspend (image: I, keywords: Collection<String>) -> Pair<Float, Float>?,
    private val l3: suspend (image: I) -> Pair<Float, Float>?,
    private val l3yolo: suspend (image: I) -> Pair<Float, Float>? = { null },
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {

    data class Result(
        val target: SkipTarget?,
        /** Per-layer elapsed time, in evaluation order. */
        val timings: List<Pair<SkipLayer, Long>>,
    )

    suspend fun detect(
        root: AccessibilityNodeInfo?,
        screenshot: suspend () -> I?,
        settings: AppSettings,
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

        // L2/L3 need pixels; skip if screenshot is unavailable (e.g. FLAG_SECURE).
        val bitmap = screenshot() ?: return Result(null, timings)

        if (settings.layer2Enabled) {
            val t0 = clock()
            val hit = l2(bitmap, settings.keywords)
            timings += SkipLayer.L2_OCR to (clock() - t0)
            if (hit != null) {
                return Result(SkipTarget(hit.first, hit.second, SkipLayer.L2_OCR), timings)
            }
        }

        if (settings.layer3Enabled) {
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
                    ocr.findSkipButton(bitmap, keywords, selfLabels)
                        ?.let { it.x.toFloat() to it.y.toFloat() }
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
