package com.adskipper.core.detect

import com.adskipper.core.data.AppSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionPipelineTest {

    private var l1Called = false
    private var l2Called = false
    private var l3Called = false
    private var screenshotCalled = false

    private var fakeTime = 0L
    private val clock = { fakeTime }

    private var yoloCalled = false

    private val ocrLines = listOf(OcrLine("广告", 900, 120, 80, 40))

    private fun pipeline(
        l1Hit: Pair<Float, Float>? = null,
        l2Hit: Pair<Float, Float>? = null,
        l3Hit: Pair<Float, Float>? = null,
        yoloHit: Pair<Float, Float>? = null,
    ): DetectionPipeline<Unit> {
        l1Called = false; l2Called = false; l3Called = false
        yoloCalled = false; screenshotCalled = false
        return DetectionPipeline(
            l1 = { _, _ -> l1Called = true; fakeTime += 5; l1Hit },
            l2 = { _, _ ->
                l2Called = true; fakeTime += 100
                DetectionPipeline.OcrPass(l2Hit, ocrLines)
            },
            l3 = { _ -> l3Called = true; fakeTime += 1000; l3Hit },
            l3yolo = { _ -> yoloCalled = true; fakeTime += 50; yoloHit },
            clock = clock,
        )
    }

    private val allOn = AppSettings(layer1Enabled = true, layer2Enabled = true, layer3Enabled = true)

    private suspend fun screenshotAvailable(): Unit? {
        screenshotCalled = true
        return Unit
    }

    @Test
    fun `L1 hit short-circuits L2 L3 and screenshot`() = runTest {
        val result = pipeline(l1Hit = 100f to 200f).detect(null, { screenshotCalled = true; null }, allOn)
        assertEquals(SkipLayer.L1_NODE, result.target?.layer)
        assertEquals(100f, result.target?.x)
        assertFalse(screenshotCalled)
        assertFalse(l2Called)
        assertFalse(l3Called)
        assertEquals(listOf(SkipLayer.L1_NODE), result.timings.map { it.first })
    }

    @Test
    fun `screenshot unavailable stops pipeline`() = runTest {
        val result = pipeline(l2Hit = 1f to 1f).detect(null, { screenshotCalled = true; null }, allOn)
        assertNull(result.target)
        assertTrue(screenshotCalled)
        assertFalse(l2Called)
        assertFalse(l3Called)
    }

    @Test
    fun `only L1 enabled takes no screenshot`() = runTest {
        val settings = AppSettings(layer1Enabled = true, layer2Enabled = false, layer3Enabled = false)
        val result = pipeline().detect(null, ::screenshotAvailable, settings)
        assertNull(result.target)
        assertTrue(l1Called)
        assertFalse(screenshotCalled)
    }

    @Test
    fun `L2 hit when L1 misses, L3 not consulted`() = runTest {
        val result = pipeline(l2Hit = 50f to 60f).detect(null, ::screenshotAvailable, allOn)
        assertEquals(SkipLayer.L2_OCR, result.target?.layer)
        assertTrue(l1Called)
        assertFalse(l3Called)
    }

    @Test
    fun `YOLO hit when L1 and L2 miss, VLM not consulted`() = runTest {
        val result = pipeline(yoloHit = 900f to 120f).detect(null, ::screenshotAvailable, allOn)
        assertEquals(SkipLayer.L3_YOLO, result.target?.layer)
        assertTrue(yoloCalled)
        assertFalse(l3Called)
        assertEquals(listOf(SkipLayer.L1_NODE, SkipLayer.L2_OCR, SkipLayer.L3_YOLO),
            result.timings.map { it.first })
    }

    @Test
    fun `L3 VLM hit when everything else misses`() = runTest {
        val result = pipeline(l3Hit = 700f to 80f).detect(null, ::screenshotAvailable, allOn)
        assertEquals(SkipLayer.L3_VLM, result.target?.layer)
        assertTrue(yoloCalled)
        assertEquals(
            listOf(SkipLayer.L1_NODE, SkipLayer.L2_OCR, SkipLayer.L3_YOLO, SkipLayer.L3_VLM),
            result.timings.map { it.first })
    }

    @Test
    fun `all layers miss returns null`() = runTest {
        val result = pipeline().detect(null, ::screenshotAvailable, allOn)
        assertNull(result.target)
        assertTrue(l1Called && l2Called && l3Called)
    }

    @Test
    fun `disabled layers are skipped`() = runTest {
        val settings = AppSettings(layer1Enabled = false, layer2Enabled = false, layer3Enabled = true)
        val result = pipeline(l3Hit = 1f to 2f).detect(null, ::screenshotAvailable, settings)
        assertEquals(SkipLayer.L3_VLM, result.target?.layer)
        assertFalse(l1Called)
        assertFalse(l2Called)
        assertTrue(l3Called)
    }

    @Test
    fun `closed image gate blocks YOLO and VLM but not keyword layers`() = runTest {
        val result = pipeline(l3Hit = 1f to 2f, yoloHit = 3f to 4f)
            .detect(null, ::screenshotAvailable, allOn, imageLayerGate = { false })
        assertNull(result.target)
        assertTrue(l1Called)
        assertTrue(l2Called)
        assertFalse(yoloCalled)
        assertFalse(l3Called)
    }

    @Test
    fun `gate sees the L2 OCR lines`() = runTest {
        var seen: List<OcrLine>? = null
        pipeline().detect(null, ::screenshotAvailable, allOn, imageLayerGate = { lines ->
            seen = lines
            true
        })
        assertEquals(ocrLines, seen)
        assertTrue(l3Called)
    }

    @Test
    fun `gate gets empty lines when L2 is disabled`() = runTest {
        val settings = AppSettings(layer1Enabled = false, layer2Enabled = false, layer3Enabled = true)
        var seen: List<OcrLine>? = null
        pipeline(l3Hit = 1f to 2f).detect(null, ::screenshotAvailable, settings, imageLayerGate = {
            seen = it
            true
        })
        assertEquals(emptyList<OcrLine>(), seen)
    }

    @Test
    fun `L2 hit returns before the gate can block`() = runTest {
        val result = pipeline(l2Hit = 50f to 60f)
            .detect(null, ::screenshotAvailable, allOn, imageLayerGate = { false })
        assertEquals(SkipLayer.L2_OCR, result.target?.layer)
    }
}
