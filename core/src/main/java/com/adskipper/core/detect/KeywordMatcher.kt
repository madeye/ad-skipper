package com.adskipper.core.detect

/**
 * Pure keyword matching shared by L1 (UI nodes) and L2 (OCR lines).
 * Returns the matched keyword, or null.
 */
object KeywordMatcher {
    /** [excluded]: exact texts that must never count as a hit — e.g. this
     *  app's own label "广告跳过" contains the keyword "跳过", so every surface
     *  listing installed apps (launcher search, app stores) would match it. */
    fun matches(
        text: String?,
        keywords: Collection<String>,
        excluded: Collection<String> = emptySet(),
    ): String? {
        if (text.isNullOrBlank()) return null
        val lower = text.trim().lowercase()
        if (excluded.any { lower == it.trim().lowercase() }) return null
        return keywords.firstOrNull { kw ->
            kw.isNotBlank() && lower.contains(kw.trim().lowercase())
        }
    }

    /** Ad skip buttons are short — long prose mentioning "跳过" is usually
     *  content text, not a button. */
    fun isPlausibleButtonText(text: String?): Boolean =
        !text.isNullOrBlank() && text.trim().length <= 16

    /** L2: first OCR line that reads like a skip button. */
    fun findSkipLine(
        lines: List<OcrLine>,
        keywords: Collection<String>,
        excluded: Collection<String> = emptySet(),
    ): OcrLine? = lines.firstOrNull { line ->
        isPlausibleButtonText(line.text) && matches(line.text, keywords, excluded) != null
    }
}
