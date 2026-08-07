package com.adskipper.core.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeywordMatcherTest {

    private val keywords = setOf("跳过", "Skip", "skip_ad")

    @Test
    fun `matches substring`() {
        assertEquals("跳过", KeywordMatcher.matches("跳过 3s", keywords))
    }

    @Test
    fun `matches case insensitively`() {
        assertEquals("Skip", KeywordMatcher.matches("SKIP AD", keywords))
        // first matching keyword wins — "Skip" also matches "SKIP_AD"
        assertEquals("Skip", KeywordMatcher.matches("SKIP_AD", keywords))
    }

    @Test
    fun `no match returns null`() {
        assertNull(KeywordMatcher.matches("立即开通会员", keywords))
    }

    @Test
    fun `blank text returns null`() {
        assertNull(KeywordMatcher.matches(null, keywords))
        assertNull(KeywordMatcher.matches("   ", keywords))
    }

    @Test
    fun `plausible button text length`() {
        assertTrue(KeywordMatcher.isPlausibleButtonText("跳过广告"))
        assertFalse(KeywordMatcher.isPlausibleButtonText("这是一段非常长的正文内容，其中提到了跳过按钮这个东西"))
        assertFalse(KeywordMatcher.isPlausibleButtonText(""))
    }
}
