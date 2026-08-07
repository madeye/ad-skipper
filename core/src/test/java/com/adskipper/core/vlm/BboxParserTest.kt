package com.adskipper.core.vlm

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BboxParserTest {

    @Test
    fun `parses 0-1000 scale`() {
        val r = BboxParser.parse("[100, 200, 300, 400]")!!
        assertArrayEquals(floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f), r, 1e-6f)
    }

    @Test
    fun `parses 0-100 scale`() {
        val r = BboxParser.parse("[10, 20, 30, 40]")!!
        assertArrayEquals(floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f), r, 1e-6f)
    }

    @Test
    fun `parses 0-1 scale`() {
        val r = BboxParser.parse("[0.1, 0.2, 0.3, 0.4]")!!
        assertArrayEquals(floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f), r, 1e-6f)
    }

    @Test
    fun `tolerates surrounding prose`() {
        val r = BboxParser.parse("The skip button is at [500, 800, 600, 950].")!!
        assertArrayEquals(floatArrayOf(0.5f, 0.8f, 0.6f, 0.95f), r, 1e-6f)
    }

    @Test
    fun `clamps out-of-range values`() {
        val r = BboxParser.parse("[0, 0, 1200, 1500]")!!
        assertArrayEquals(floatArrayOf(0f, 0f, 1f, 1f), r, 1e-6f)
    }

    @Test
    fun `rejects too few numbers`() {
        assertNull(BboxParser.parse("[1, 2, 3]"))
    }

    @Test
    fun `rejects no numbers`() {
        assertNull(BboxParser.parse("no button found"))
    }

    @Test
    fun `rejects inverted box`() {
        assertNull(BboxParser.parse("[500, 100, 100, 500]"))
    }
}
