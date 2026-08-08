package com.adskipper.core.detect

import java.util.concurrent.ConcurrentHashMap

/**
 * Session-scoped accumulator of "the current screen is a splash ad" evidence.
 *
 * The image-based L3 detectors are the pipeline's false-positive risk: on a
 * screen that is not an ad they can hallucinate a skip button and tap real
 * UI. Instead of trusting time-since-launch, an image-layer tap requires
 * positive evidence that an ad is actually showing:
 *
 *  - a standalone 「广告」 badge — CN regulation mandates it on splash ads;
 *  - a ticking countdown: a number in a stable screen region that decreases
 *    across polling ticks (~1/s). Normal UI essentially never does this;
 *  - a known splash-ad SDK marker (穿山甲/优量汇/快手联盟/…) in the window
 *    class name or view ids, reported via [noteSdkSignal].
 *
 * Keyword layers L1/L2 stay ungated: they are precise, and some ads carry no
 * marker OCR can read — the keyword path must remain the always-on fallback.
 *
 * Evidence is cleared at each app session start ([onSessionStart]); an ad
 * confirmed once stays confirmed for the rest of that session. The tree-size
 * guard in the service is the second, independent gate that keeps a stale
 * confirmation from enabling taps on real app UI.
 */
class AdEvidenceTracker(private val clock: () -> Long) {

    private class State {
        @Volatile var sdkSeen = false
        @Volatile var badgeSeen = false
        @Volatile var countdownConfirmed = false

        /** Absolute [clock] time the confirmed countdown says the ad ends. */
        @Volatile var countdownEndsAt = 0L

        /** regionKey -> (last numeric value, seen at). */
        val numbers = HashMap<Long, Pair<Int, Long>>()
    }

    private val states = ConcurrentHashMap<String, State>()

    fun onSessionStart(pkg: String) {
        states.remove(pkg)
    }

    fun noteSdkSignal(pkg: String) {
        state(pkg).sdkSeen = true
    }

    fun isAdConfirmed(pkg: String): Boolean = states[pkg]?.let {
        it.sdkSeen || it.badgeSeen || it.countdownConfirmed
    } ?: false

    /** Absolute [clock] time polling should continue until so the whole
     *  countdown is covered, or 0 when no countdown has been confirmed. */
    fun pollUntil(pkg: String): Long = states[pkg]?.countdownEndsAt ?: 0L

    /** Feed one tick's OCR lines. Cheap (string scans only). */
    fun observeOcr(pkg: String, lines: List<OcrLine>) {
        if (lines.isEmpty()) return
        val s = state(pkg)
        val now = clock()
        if (!s.badgeSeen && lines.any { isAdBadge(it.text) }) {
            s.badgeSeen = true
        }
        for (line in lines) {
            val value = countdownValue(line.text) ?: continue
            val key = regionKey(line)
            synchronized(s.numbers) {
                val prev = s.numbers.put(key, value to now)
                if (prev != null &&
                    value < prev.first &&
                    prev.first - value <= MAX_COUNTDOWN_STEP &&
                    now - prev.second <= MAX_TICK_GAP_MS
                ) {
                    s.countdownConfirmed = true
                    s.countdownEndsAt = maxOf(
                        s.countdownEndsAt,
                        now + value * 1000L + COUNTDOWN_SLACK_MS,
                    )
                }
            }
        }
    }

    private fun state(pkg: String) = states.getOrPut(pkg) { State() }

    companion object {
        /** Countdowns step by 1 (occasionally a missed tick or two); a larger
         *  drop is some other number changing, not a countdown. */
        private const val MAX_COUNTDOWN_STEP = 3

        /** Max age of the previous observation for a decrement to count —
         *  a few poll ticks. Slow drifters (battery %, clock) never qualify. */
        private const val MAX_TICK_GAP_MS = 4000L

        /** Poll a little past the countdown's nominal end. */
        private const val COUNTDOWN_SLACK_MS = 3000L

        /** OCR boxes jitter a few px between frames; quantize to this grid so
         *  the same on-screen countdown maps to a stable region key. */
        private const val REGION_GRID_PX = 80

        /** Standalone 「广告」 badge: the two characters plus at most a couple
         *  of decoration chars. Longer phrases embedding the word（个性化广告、
         *  关闭广告推送）are ordinary UI text and must not count. */
        fun isAdBadge(text: String): Boolean {
            val t = text.filterNot { it.isWhitespace() || it in DECORATION_CHARS }
            return t.length <= 4 && t.contains("广告")
        }

        /** Extracts a plausible countdown value (in seconds) from a short OCR
         *  line, or null. Deliberately strict: clock times (12:34), percents,
         *  prices and long prose all return null. */
        fun countdownValue(text: String): Int? {
            val t = text.filterNot { it.isWhitespace() || it in DECORATION_CHARS }
            if (t.length > 8 || t.any { it in EXCLUDED_CHARS }) return null
            val m = COUNTDOWN_RE.matchEntire(t) ?: return null
            return m.groupValues[1].toIntOrNull()?.takeIf { it in 1..60 }
        }

        private const val DECORATION_CHARS = "|·•[]()【】〈〉<>「」"
        private const val EXCLUDED_CHARS = ":：.%¥\$元/"

        /** Digits, optionally wrapped in the words that accompany splash
         *  countdowns（跳过 5、广告 3、5s、5秒后跳过）. */
        private val COUNTDOWN_RE =
            Regex("^(?:跳过|广告|跳过广告)?(\\d{1,2})(?:s|S|秒)?(?:后?跳过|后关闭)?$")

        private fun regionKey(line: OcrLine): Long =
            (line.centerX / REGION_GRID_PX).toLong() shl 32 or
                (line.centerY / REGION_GRID_PX).toLong()
    }
}
