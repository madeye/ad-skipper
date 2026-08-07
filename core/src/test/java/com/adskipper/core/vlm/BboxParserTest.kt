package com.adskipper.core.vlm

import com.adskipper.core.model.CoordSpace
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BboxParserTest {

    // -- CoordSpace.NORM (InternVL3 / MiniCPM answer 0-1000 normalized) --

    @Test
    fun `parses 0-1000 scale as xyxy`() {
        val r = BboxParser.parse("[100, 200, 300, 400]", CoordSpace.NORM, 896, 2016)!!
        assertArrayEquals(floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f), r, 1e-6f)
    }

    @Test
    fun `parses 0-100 scale`() {
        val r = BboxParser.parse("[10, 20, 30, 40]", CoordSpace.NORM, 896, 2016)!!
        assertArrayEquals(floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f), r, 1e-6f)
    }

    @Test
    fun `parses 0-1 scale`() {
        val r = BboxParser.parse("[0.1, 0.2, 0.3, 0.4]", CoordSpace.NORM, 896, 2016)!!
        assertArrayEquals(floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f), r, 1e-6f)
    }

    /** Real InternVL3-2B answer shape: the 2 in bbox_2d must not be parsed
     *  as a coordinate. */
    @Test
    fun `ignores digits inside identifiers like bbox_2d`() {
        val r = BboxParser.parse(
            "```json\n{\"bbox_2d\": [830, 40, 900, 80]}\n```",
            CoordSpace.NORM, 896, 2016,
        )!!
        assertArrayEquals(floatArrayOf(0.83f, 0.04f, 0.9f, 0.08f), r, 1e-6f)
    }

    @Test
    fun `tolerates surrounding prose`() {
        val r = BboxParser.parse(
            "The skip button is at [500, 80, 600, 150].",
            CoordSpace.NORM, 896, 2016,
        )!!
        assertArrayEquals(floatArrayOf(0.5f, 0.08f, 0.6f, 0.15f), r, 1e-6f)
    }

    @Test
    fun `falls back to standalone numbers without brackets`() {
        val r = BboxParser.parse(
            "bbox_2d: 100, 200, 300, 400",
            CoordSpace.NORM, 896, 2016,
        )!!
        assertArrayEquals(floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f), r, 1e-6f)
    }

    @Test
    fun `clamps out-of-range values`() {
        val r = BboxParser.parse("[0, 0, 1200, 1500]", CoordSpace.NORM, 896, 2016)!!
        assertArrayEquals(floatArrayOf(0f, 0f, 1f, 1f), r, 1e-6f)
    }

    // -- CoordSpace.PIXELS (Qwen2.5-VL answers in input-image pixels) --

    @Test
    fun `pixel coords normalize against image dimensions`() {
        // Real Qwen2.5-VL @672 answer shape; image the model saw is 302x672.
        val r = BboxParser.parse(
            "```json\n{\"bbox_2d\": [151, 336, 302, 672]}\n```",
            CoordSpace.PIXELS, 302, 672,
        )!!
        assertArrayEquals(floatArrayOf(0.5f, 0.5f, 1f, 1f), r, 1e-6f)
    }

    @Test
    fun `pixel coords clamp to image bounds`() {
        val r = BboxParser.parse("[0, 0, 400, 700]", CoordSpace.PIXELS, 302, 672)!!
        assertArrayEquals(floatArrayOf(0f, 0f, 1f, 1f), r, 1e-6f)
    }

    // -- rejection --

    @Test
    fun `rejects too few numbers`() {
        assertNull(BboxParser.parse("[1, 2, 3]", CoordSpace.NORM, 896, 2016))
    }

    @Test
    fun `rejects no numbers`() {
        assertNull(BboxParser.parse("There are none.", CoordSpace.NORM, 896, 2016))
    }

    @Test
    fun `rejects inverted box`() {
        assertNull(BboxParser.parse("[500, 100, 100, 500]", CoordSpace.NORM, 896, 2016))
    }

}
