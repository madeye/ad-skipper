package com.adskipper.core.detect

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * L1: scan the accessibility node tree for a "skip" button (<10ms).
 */
object NodeMatcher {

    fun findSkipNode(
        root: AccessibilityNodeInfo?,
        keywords: Collection<String>,
        excluded: Collection<String> = emptySet(),
    ): Rect? {
        root ?: return null
        // A keyword match whose bounds cover (nearly) the whole screen is
        // never the skip button — e.g. Douban's `id/skip` is a full-screen,
        // text-less container; tapping its center hits the ad click-through
        // area. Real skip buttons are small.
        val rootBounds = Rect().also { root.getBoundsInScreen(it) }
        val maxArea = rootBounds.width() * rootBounds.height() / 3
        val clickableHits = ArrayList<Rect>()
        val otherHits = ArrayList<Rect>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isVisibleToUser) {
                val textMatch =
                    KeywordMatcher.matches(node.text?.toString(), keywords, excluded)
                        ?.takeIf { KeywordMatcher.isPlausibleButtonText(node.text?.toString()) }
                val descMatch =
                    KeywordMatcher.matches(node.contentDescription?.toString(), keywords, excluded)
                        ?.takeIf { KeywordMatcher.isPlausibleButtonText(node.contentDescription?.toString()) }
                val idMatch = KeywordMatcher.matches(
                    node.viewIdResourceName?.substringAfterLast('/'), keywords)

                if (textMatch != null || descMatch != null || idMatch != null) {
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    if (!bounds.isEmpty && bounds.width() * bounds.height() <= maxArea) {
                        (if (node.isClickable) clickableHits else otherHits).add(bounds)
                    }
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let(queue::addLast)
            }
        }
        // Prefer explicitly clickable nodes; a tap gesture at the bounds
        // center works even when the node itself is not marked clickable.
        return clickableHits.firstOrNull() ?: otherHits.firstOrNull()
    }
}
