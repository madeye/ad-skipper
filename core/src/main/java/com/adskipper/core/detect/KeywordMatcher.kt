package com.adskipper.core.detect

/**
 * Pure keyword matching shared by L1 (UI nodes) and L2 (OCR lines).
 * Returns the matched keyword, or null.
 */
object KeywordMatcher {
    fun matches(text: String?, keywords: Collection<String>): String? {
        if (text.isNullOrBlank()) return null
        val lower = text.trim().lowercase()
        return keywords.firstOrNull { kw ->
            kw.isNotBlank() && lower.contains(kw.trim().lowercase())
        }
    }

    /** Ad skip buttons are short — long prose mentioning "跳过" is usually
     *  content text, not a button. */
    fun isPlausibleButtonText(text: String?): Boolean =
        !text.isNullOrBlank() && text.trim().length <= 16
}
