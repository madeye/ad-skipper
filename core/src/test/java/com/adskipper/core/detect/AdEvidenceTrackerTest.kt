package com.adskipper.core.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdEvidenceTrackerTest {

    private var now = 10_000L
    private val tracker = AdEvidenceTracker { now }

    private fun line(text: String, x: Int = 900, y: Int = 120) =
        OcrLine(text, x, y, 120, 40)

    // --- badge ---

    @Test
    fun `standalone ad badge is evidence`() {
        tracker.observeOcr("a", listOf(line("广告")))
        assertTrue(tracker.isAdConfirmed("a"))
    }

    @Test
    fun `decorated badge matches`() {
        tracker.observeOcr("a", listOf(line("| 广告 |")))
        assertTrue(tracker.isAdConfirmed("a"))
    }

    @Test
    fun `phrases embedding the word are not badges`() {
        tracker.observeOcr("a", listOf(line("个性化广告"), line("关闭广告推送")))
        assertFalse(tracker.isAdConfirmed("a"))
    }

    @Test
    fun `badge check via helper`() {
        assertTrue(AdEvidenceTracker.isAdBadge("广告"))
        assertTrue(AdEvidenceTracker.isAdBadge("跳过广告"))
        assertFalse(AdEvidenceTracker.isAdBadge("个性化广告"))
        assertFalse(AdEvidenceTracker.isAdBadge("这是一条关于广告的说明"))
    }

    // --- countdown parsing ---

    @Test
    fun `countdown values parse`() {
        assertEquals(5, AdEvidenceTracker.countdownValue("5"))
        assertEquals(5, AdEvidenceTracker.countdownValue("05"))
        assertEquals(5, AdEvidenceTracker.countdownValue("5s"))
        assertEquals(5, AdEvidenceTracker.countdownValue("5 秒"))
        assertEquals(5, AdEvidenceTracker.countdownValue("跳过 5"))
        assertEquals(5, AdEvidenceTracker.countdownValue("跳过5s"))
        assertEquals(5, AdEvidenceTracker.countdownValue("5秒后跳过"))
        assertEquals(30, AdEvidenceTracker.countdownValue("跳过 30"))
    }

    @Test
    fun `non-countdown numbers do not parse`() {
        assertNull(AdEvidenceTracker.countdownValue("12:34"))   // clock
        assertNull(AdEvidenceTracker.countdownValue("85%"))     // battery
        assertNull(AdEvidenceTracker.countdownValue("2026"))    // year
        assertNull(AdEvidenceTracker.countdownValue("¥5"))      // price
        assertNull(AdEvidenceTracker.countdownValue("5.0"))
        assertNull(AdEvidenceTracker.countdownValue("0"))
        assertNull(AdEvidenceTracker.countdownValue("61"))
        assertNull(AdEvidenceTracker.countdownValue("第5章 冒险开始"))
    }

    // --- countdown tracking ---

    @Test
    fun `decreasing number in stable region confirms countdown`() {
        tracker.observeOcr("a", listOf(line("跳过 5")))
        assertFalse(tracker.isAdConfirmed("a"))
        now += 1000
        tracker.observeOcr("a", listOf(line("跳过 4")))
        assertTrue(tracker.isAdConfirmed("a"))
        // Polling deadline covers the remaining 4s plus slack.
        assertTrue(tracker.pollUntil("a") >= now + 4000)
    }

    @Test
    fun `same value then decrement still confirms`() {
        tracker.observeOcr("a", listOf(line("5")))
        now += 750
        tracker.observeOcr("a", listOf(line("5"))) // countdown slower than ticks
        now += 750
        tracker.observeOcr("a", listOf(line("4")))
        assertTrue(tracker.isAdConfirmed("a"))
    }

    @Test
    fun `increasing number is not a countdown`() {
        tracker.observeOcr("a", listOf(line("4")))
        now += 1000
        tracker.observeOcr("a", listOf(line("5")))
        assertFalse(tracker.isAdConfirmed("a"))
    }

    @Test
    fun `large jump is not a countdown`() {
        tracker.observeOcr("a", listOf(line("30")))
        now += 1000
        tracker.observeOcr("a", listOf(line("2")))
        assertFalse(tracker.isAdConfirmed("a"))
    }

    @Test
    fun `stale observation does not confirm`() {
        tracker.observeOcr("a", listOf(line("5")))
        now += 10_000
        tracker.observeOcr("a", listOf(line("4")))
        assertFalse(tracker.isAdConfirmed("a"))
    }

    @Test
    fun `different regions do not pair`() {
        tracker.observeOcr("a", listOf(line("5", x = 100, y = 100)))
        now += 1000
        tracker.observeOcr("a", listOf(line("4", x = 900, y = 1800)))
        assertFalse(tracker.isAdConfirmed("a"))
    }

    // --- sdk + lifecycle ---

    @Test
    fun `sdk signal confirms immediately`() {
        tracker.noteSdkSignal("a")
        assertTrue(tracker.isAdConfirmed("a"))
        assertFalse(tracker.isAdConfirmed("b"))
    }

    @Test
    fun `session start clears evidence`() {
        tracker.noteSdkSignal("a")
        tracker.onSessionStart("a")
        assertFalse(tracker.isAdConfirmed("a"))
        assertEquals(0L, tracker.pollUntil("a"))
    }
}
